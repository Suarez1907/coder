package com.suarez.coder.repomap

import com.suarez.coder.context.FileFilter
import com.suarez.coder.llm.Http
import com.suarez.coder.tools.GitHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Builds the text of a repo map from per-file outlines (deterministic, so it caches well). */
object RepoMapBuilder {
    const val DEFAULT_MAX_CHARS = 60_000

    fun build(files: Map<String, List<String>>, maxChars: Int = DEFAULT_MAX_CHARS): String {
        val paths = files.keys.sorted()
        fun priority(p: String): Int {
            val lp = p.lowercase()
            var s = p.count { it == '/' }
            if (lp.contains("test")) s += 5
            if (files[p].isNullOrEmpty()) s += 10
            return s
        }
        // Decide which files keep their outline, most useful first, within the budget.
        val detailed = HashSet<String>()
        var budget = maxChars - paths.sumOf { it.length + 1 }
        for (p in paths.sortedWith(compareBy({ priority(it) }, { it }))) {
            val cost = files[p].orEmpty().sumOf { it.length + 3 }
            if (cost == 0) continue
            if (cost <= budget) { detailed += p; budget -= cost }
        }
        val sb = StringBuilder()
        var omitted = 0
        for (p in paths) {
            if (sb.length > maxChars) { omitted++; continue }
            sb.append(p).append('\n')
            if (p in detailed) files[p]!!.forEach { sb.append("  ").append(it).append('\n') }
        }
        if (omitted > 0) sb.append("… ($omitted dosya daha, yalnızca ağaçta)\n")
        return sb.toString()
    }
}

/**
 * Downloads a repository once per commit (single tarball request), extracts outlines locally and caches the map
 * in memory and on disk.
 */
class RepoMapService(private val github: GitHub, private val dir: File?) {
    private val memory = HashMap<String, String>()

    data class Result(val map: String, val files: Int, val fromCache: Boolean, val sha: String)

    private fun cacheFile(repo: String, sha: String): File? = dir?.let {
        val name = MessageDigest.getInstance("SHA-1").digest("$repo@$sha".toByteArray()).joinToString("") { b -> "%02x".format(b) }
        File(File(it, "repomap").apply { mkdirs() }, "$name.txt")
    }

    /** Returns an already built map for the repo without network access. */
    fun peek(repo: String): String? = memory.entries.lastOrNull { it.key.startsWith("$repo@") }?.value

    suspend fun get(repo: String, ref: String?): Result {
        val sha = github.headSha(repo, ref)
        val key = "$repo@$sha"
        memory[key]?.let { return Result(it, it.lines().count { l -> l.isNotEmpty() && !l.startsWith(" ") }, true, sha) }
        cacheFile(repo, sha)?.takeIf { it.exists() }?.let { f ->
            val text = withContext(Dispatchers.IO) { f.readText() }
            memory[key] = text
            return Result(text, text.lines().count { l -> l.isNotEmpty() && !l.startsWith(" ") }, true, sha)
        }
        val outlines = HashMap<String, List<String>>()
        val sizes = HashMap<String, Long>()
        var gitignore: String? = null
        val req = github.request("GET", "/repos/$repo/tarball/$sha")
        withContext(Dispatchers.IO) {
            Http.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("Repo arşivi indirilemedi (HTTP ${res.code})")
                GZIPInputStream(res.body!!.byteStream()).use { input ->
                    TarStream.forEachFile(input) { e ->
                        sizes[e.path] = e.size
                        if (e.path == ".gitignore") { gitignore = String(e.read(), Charsets.UTF_8); return@forEachFile }
                        if (FileFilter().isExcluded(e.path, e.size)) return@forEachFile
                        val text = String(e.read(), Charsets.UTF_8)
                        outlines[e.path] = RepoSkeletonExtractor.extract(e.path, text)
                    }
                }
            }
        }
        val filter = FileFilter(gitignore)
        val kept = sizes.keys.filter { !filter.isExcluded(it, sizes[it]) }.associateWith { outlines[it].orEmpty() }
        val map = RepoMapBuilder.build(kept)
        memory[key] = map
        cacheFile(repo, sha)?.let { f -> withContext(Dispatchers.IO) { runCatching { f.writeText(map) } } }
        return Result(map, kept.size, false, sha)
    }
}
