package com.suarez.coder.tools

import com.suarez.coder.llm.Http
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * lookup_docs: current documentation of a library, so code is written against the API of the version the project uses.
 * Tries Context7 (documentation index for coding agents; an API key is optional, raising rate limits), then falls back
 * to a web search for the official docs and reads the best page with the topic as the query.
 */
class DocsLookup(private val web: Web, private val apiKey: () -> String = { "" }) {
    data class Lib(val id: String, val title: String, val versions: List<String>)

    companion object {
        const val MAX_CHARS = 12_000
        private const val BASE = "https://context7.com/api"

        /** Library entries from a Context7 search response (several response shapes are accepted). */
        fun parseLibraries(body: String): List<Lib> {
            val arr: JSONArray = runCatching {
                val t = body.trim()
                if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { o -> o.optJSONArray("results") ?: o.optJSONArray("libraries") ?: JSONArray() }
            }.getOrDefault(JSONArray())
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { o.optString("libraryId") }.takeIf { it.startsWith("/") } ?: return@mapNotNull null
                val versions = o.optJSONArray("versions")?.let { v -> (0 until v.length()).map { v.optString(it) } }.orEmpty()
                Lib(id, o.optString("title").ifBlank { o.optString("name") }, versions)
            }
        }

        /** The closest published version: exact, then same major.minor, then same major. */
        fun pickVersion(available: List<String>, wanted: String?): String? {
            val w = wanted?.trim()?.removePrefix("v")?.removePrefix("^")?.removePrefix("~")?.takeIf { it.isNotBlank() } ?: return null
            val clean = available.associateBy { it.removePrefix("v") }
            clean[w]?.let { return it }
            val mm = w.split('.').take(2).joinToString(".")
            clean.keys.filter { it.startsWith("$mm.") || it == mm }.maxOrNull()?.let { return clean[it] }
            val major = w.substringBefore('.')
            return clean.keys.filter { it.substringBefore('.') == major }.maxOrNull()?.let { clean[it] }
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }

    private fun req(url: String): Request {
        val b = Request.Builder().url(url).header("Accept", "application/json, text/plain")
        apiKey().takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
        return b.build()
    }

    private suspend fun get(url: String): String? = runCatching {
        val r = Http.execute(req(url))
        if (r.code in 200..299 && r.body.isNotBlank()) r.body else null
    }.getOrNull()

    suspend fun lookup(library: String, topic: String, version: String?): String {
        context7(library, topic, version)?.let { return it }
        // Fallback: official documentation through the web.
        val q = listOfNotNull(library, version, topic, "documentation").joinToString(" ")
        val results = web.search(q, max = 5)
        val url = Regex("https?://\\S+").findAll(results).map { it.value }.firstOrNull { u -> listOf("medium.com", "reddit.com", "youtube.com").none { u.contains(it) } }
            ?: return "Belge bulunamadı. Arama sonuçları:\n$results"
        val page = web.fetch(url, 0, MAX_CHARS, query = topic)
        return "Kaynak: $url (web)\n\n$page"
    }

    private suspend fun context7(library: String, topic: String, version: String?): String? {
        val libs = (get("$BASE/v2/libs/search?libraryName=${enc(library)}&query=${enc(topic)}")
            ?: get("$BASE/v1/search?query=${enc(library)}"))?.let { parseLibraries(it) }.orEmpty()
        val lib = libs.firstOrNull() ?: return null
        val v = pickVersion(lib.versions, version)
        val idPath = lib.id + (v?.let { "/$it" } ?: "")
        val text = get("$BASE/v2/context?libraryId=${enc(idPath)}&query=${enc(topic)}&type=txt")
            ?: get("$BASE/v1${idPath}?type=txt&topic=${enc(topic)}&tokens=4000")
            ?: return null
        if (text.trimStart().startsWith("{") && text.contains("\"error\"")) return null
        return "Kaynak: Context7 ${lib.id}${v?.let { " @ $it" } ?: " (sürüm eşleşmedi; en güncel)"}\n\n" + text.take(MAX_CHARS)
    }
}
