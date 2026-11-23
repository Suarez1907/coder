package com.suarez.coder.context

import com.suarez.coder.llm.Http
import com.suarez.coder.tools.GitHub
import com.suarez.coder.tools.ToolException
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * semantic_search: finds code by meaning ("where is the payment flow"). Files are split into ~50-line chunks and
 * embedded with Gemini once; the index lives on the phone and only files whose blob sha changed are embedded again.
 */
class SemanticIndex(
    private val gh: GitHub,
    private val listFiles: suspend (repo: String, ref: String?) -> List<GitHub.TreeEntry>,
    private val dir: File,
    private val geminiKey: () -> String,
    private val embed: (suspend (texts: List<String>, query: Boolean) -> List<FloatArray>)? = null,
) {
    data class Chunk(val path: String, val start: Int, val end: Int, val text: String, val vec: FloatArray)
    data class Hit(val chunk: Chunk, val score: Double)

    companion object {
        const val MODEL = "gemini-embedding-001"
        const val DIMS = 256
        const val CHUNK_LINES = 50
        const val MAX_FILES = 400
        const val MAX_FILE_BYTES = 150_000L
        /** gemini-embedding-001 input price per 1M tokens. */
        const val USD_PER_M = 0.15
        private val CODE = Regex("\\.(kt|kts|java|py|js|jsx|ts|tsx|mjs|go|rs|swift|dart|gd|cs|c|cc|cpp|h|hpp|rb|php|lua|vue|svelte|html|css|scss|sql|sh|md|xml|gradle|toml|yaml|yml|json)$", RegexOption.IGNORE_CASE)

        fun indexable(e: GitHub.TreeEntry) = CODE.containsMatchIn(e.path) && e.size in 1..MAX_FILE_BYTES &&
            !e.path.contains("/build/") && !e.path.endsWith(".lock") && !e.path.endsWith("package-lock.json") && !e.path.endsWith(".min.js")

        /** ~50-line chunks that overlap by 5 lines; each carries its path so the embedding knows where it lives. */
        fun chunks(path: String, text: String): List<Triple<Int, Int, String>> {
            val lines = text.lines()
            if (lines.isEmpty()) return emptyList()
            val out = ArrayList<Triple<Int, Int, String>>()
            var i = 0
            while (i < lines.size) {
                val end = minOf(lines.size, i + CHUNK_LINES)
                val body = lines.subList(i, end).joinToString("\n")
                if (body.isNotBlank()) out += Triple(i + 1, end, body)
                if (end == lines.size) break
                i = end - 5
            }
            return out
        }

        fun cosine(a: FloatArray, b: FloatArray): Double {
            var d = 0.0; var x = 0.0; var y = 0.0
            for (i in 0 until minOf(a.size, b.size)) { d += a[i] * b[i]; x += a[i] * a[i]; y += b[i] * b[i] }
            return if (x == 0.0 || y == 0.0) 0.0 else d / (sqrt(x) * sqrt(y))
        }

        fun top(chunks: List<Chunk>, q: FloatArray, k: Int): List<Hit> {
            val scored = chunks.map { Hit(it, cosine(it.vec, q)) }.sortedByDescending { it.score }
            // At most 2 chunks per file, so one big file does not fill the answer.
            val perFile = HashMap<String, Int>()
            return scored.filter { h -> (perFile.merge(h.chunk.path, 1, Int::plus) ?: 0) <= 2 }.take(k)
        }

        private fun pack(v: FloatArray): String {
            val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { b.putFloat(it) }
            return java.util.Base64.getEncoder().encodeToString(b.array())
        }

        private fun unpack(s: String): FloatArray {
            val b = ByteBuffer.wrap(java.util.Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.remaining() / 4) { b.float }
        }
    }

    /** Tokens embedded by the last call (for the cost record). */
    var lastTokens = 0L
        private set

    private fun file(repo: String) = File(dir, repo.replace('/', '_') + ".json")

    private fun load(repo: String): MutableMap<String, Pair<String, List<Chunk>>> {
        val out = HashMap<String, Pair<String, List<Chunk>>>()
        val o = runCatching { JSONObject(file(repo).readText()) }.getOrNull() ?: return out
        for (path in o.keys()) {
            val f = o.getJSONObject(path)
            val a = f.getJSONArray("c")
            out[path] = f.getString("sha") to (0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                Chunk(path, c.getInt("s"), c.getInt("e"), c.getString("t"), unpack(c.getString("v")))
            }
        }
        return out
    }

    private fun save(repo: String, idx: Map<String, Pair<String, List<Chunk>>>) {
        dir.mkdirs()
        val o = JSONObject()
        idx.forEach { (path, v) ->
            o.put(path, JSONObject().put("sha", v.first).put("c", JSONArray().also { a ->
                v.second.forEach { c -> a.put(JSONObject().put("s", c.start).put("e", c.end).put("t", c.text).put("v", pack(c.vec))) }
            }))
        }
        file(repo).writeText(o.toString())
    }

    suspend fun search(repo: String, ref: String?, query: String, k: Int = 8): String {
        if (embed == null && geminiKey().isBlank()) throw ToolException("Anlamsal arama için Gemini API anahtarı gerekli (gömme modeli). github_search_code kullan.")
        lastTokens = 0
        val entries = listFiles(repo, ref).filter(::indexable).take(MAX_FILES)
        val idx = load(repo)
        val live = entries.associateBy { it.path }
        idx.keys.retainAll(live.keys)
        val stale = entries.filter { idx[it.path]?.first != it.sha || it.sha.isBlank() }
        var added = 0
        for (batch in stale.chunked(20)) {
            val parts = batch.flatMap { e ->
                val text = gh.readFileRaw(repo, e.path, ref) ?: return@flatMap emptyList()
                chunks(e.path, text).map { (s, en, body) -> Triple(e, s to en, body) }
            }
            for (group in parts.chunked(100)) {
                val vecs = embedAll(group.map { "${it.first.path} (lines ${it.second.first}-${it.second.second})\n${it.third.take(6_000)}" }, query = false)
                group.zip(vecs).groupBy({ it.first.first }) { (p, v) -> Chunk(p.first.path, p.second.first, p.second.second, p.third.take(2_500), v) }
                    .forEach { (e, cs) -> idx[e.path] = e.sha to (idx[e.path]?.takeIf { it.first == e.sha }?.second.orEmpty() + cs) }
                added += group.size
            }
            batch.filter { b -> parts.none { it.first == b } }.forEach { idx[it.path] = it.sha to emptyList() }
            save(repo, idx)
        }
        val all = idx.values.flatMap { it.second }
        if (all.isEmpty()) return "Dizinlenecek kod dosyası bulunamadı."
        val q = embedAll(listOf(query), query = true).first()
        val hits = top(all, q, k)
        val head = "Anlamsal arama: ${idx.size} dosya / ${all.size} parça" + (if (added > 0) ", $added parça yeni dizinlendi" else "") + "\n\n"
        return head + hits.joinToString("\n\n") { h ->
            "### ${h.chunk.path}:${h.chunk.start}-${h.chunk.end} (benzerlik ${"%.2f".format(java.util.Locale.US, h.score)})\n" + h.chunk.text.take(900)
        }
    }

    private suspend fun embedAll(texts: List<String>, query: Boolean): List<FloatArray> {
        lastTokens += texts.sumOf { it.length / 4L }
        embed?.let { return it(texts, query) }
        val reqs = JSONArray()
        texts.forEach { t ->
            reqs.put(JSONObject().put("model", "models/$MODEL").put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", t))))
                .put("taskType", if (query) "RETRIEVAL_QUERY" else "RETRIEVAL_DOCUMENT").put("outputDimensionality", DIMS))
        }
        val r = Http.execute(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:batchEmbedContents")
            .header("x-goog-api-key", geminiKey()).post(JSONObject().put("requests", reqs).toString().toRequestBody(Http.JSON)).build())
        if (r.code !in 200..299) throw ToolException("Gömme isteği başarısız (${r.code}): ${r.body.take(300)}")
        val a = JSONObject(r.body).getJSONArray("embeddings")
        return (0 until a.length()).map { i -> a.getJSONObject(i).getJSONArray("values").let { v -> FloatArray(v.length()) { v.getDouble(it).toFloat() } } }
    }
}
