package com.suarez.coder.docs

import com.suarez.coder.llm.Http
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Versions read from the active repo's build files. Lookup is local and free. */
data class DependencyVersionIndex(val versions: Map<String, String>) {
    fun versionOf(library: String): String? {
        val key = library.trim().lowercase()
        if (key.isEmpty()) return null
        versions[key]?.let { return it }
        return versions.entries.firstOrNull { key in it.key || it.key in key }?.value
    }

    companion object {
        fun extract(files: Map<String, String>): DependencyVersionIndex {
            val out = LinkedHashMap<String, String>()
            files["gradle/libs.versions.toml"]?.let { toml ->
                val versions = toml.substringAfter("[versions]", "").substringBefore("\n[")
                Regex("(?m)^\\s*([\\w.-]+)\\s*=\\s*\"([^\"]+)\"").findAll(versions).forEach {
                    out[it.groupValues[1].lowercase()] = it.groupValues[2]
                }
            }
            for (name in listOf("app/build.gradle.kts", "build.gradle.kts")) {
                files[name]?.let { gradle ->
                    Regex("\"([\\w.-]+):([\\w.-]+):(\\d[\\w.+-]*)\"").findAll(gradle).forEach {
                        out.putIfAbsent(it.groupValues[2].lowercase(), it.groupValues[3])
                    }
                }
            }
            files["package.json"]?.let { raw ->
                runCatching {
                    val o = JSONObject(raw)
                    for (k in listOf("dependencies", "devDependencies")) {
                        o.optJSONObject(k)?.keys()?.forEach { n ->
                            val v = o.optJSONObject(k)?.optString(n).orEmpty().trim().removePrefix("^").removePrefix("~")
                            if (v.isNotBlank()) out.putIfAbsent(n.lowercase().substringAfterLast('/'), v)
                        }
                    }
                }
            }
            files["Cargo.toml"]?.let { cargo ->
                val deps = cargo.substringAfter("[dependencies]", "").substringBefore("\n[")
                Regex("(?m)^\\s*([\\w-]+)\\s*=\\s*\"([^\"]+)\"").findAll(deps).forEach {
                    out.putIfAbsent(it.groupValues[1].lowercase(), it.groupValues[2])
                }
                Regex("(?m)^\\s*([\\w-]+)\\s*=\\s*\\{[^}]*version\\s*=\\s*\"([^\"]+)\"").findAll(deps).forEach {
                    out.putIfAbsent(it.groupValues[1].lowercase(), it.groupValues[2])
                }
            }
            files["project.godot"]?.let { godot ->
                Regex("config/features=PackedStringArray\\(\"(\\d+\\.\\d+)").find(godot)?.let { out.putIfAbsent("godot", it.groupValues[1]) }
                Regex("config/version=\"([^\"]+)\"").find(godot)?.let { out.putIfAbsent("godot", it.groupValues[1]) }
            }
            return DependencyVersionIndex(out)
        }

        val FILES = listOf("gradle/libs.versions.toml", "app/build.gradle.kts", "package.json", "Cargo.toml", "project.godot")
    }
}

data class DocFetch(val code: Int, val body: String, val location: String? = null)

fun interface DocFetcher {
    suspend fun get(url: String, headers: Map<String, String>): DocFetch
}

/**
 * Compact, version-aware docs. Disk cache is 7 days. A 302 is followed without the previous Authorization header
 * when the host changes. The text is capped at 6_000 characters and is returned as a tool result, not a system prefix.
 */
class LiveDocsProvider(
    private val cacheDir: File,
    private val fetcher: DocFetcher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun fetch(library: String, topic: String, version: String?, context7Key: String?, llmsUrl: String?): String {
        val ver = version?.trim()?.removePrefix("v").orEmpty()
        val key = digest("$library|$topic|$ver")
        readCache(key)?.let { return it }
        val headers = LinkedHashMap<String, String>()
        context7Key?.takeIf { it.isNotBlank() }?.let { headers["Authorization"] = "Bearer $it" }
        val query = listOf(library, ver, topic).filter { it.isNotBlank() }.joinToString(" ")
        val urls = ArrayList<String>()
        urls += "https://context7.com/api/v2/libs/search?libraryName=${enc(library)}&query=${enc(topic)}"
        llmsUrl?.takeIf { it.isNotBlank() }?.let { urls += it }
        if (urls.none { it.contains("llms.txt") }) urls += "https://context7.com/api/v1/search?query=${enc(query)}"
        var text: String? = null
        for (url in urls) {
            val body = getClean(url, headers) ?: continue
            if (body.trimStart().startsWith("{") && "\"error\"" in body && body.length < 400) continue
            text = "Kaynak: $url\nKonu: $library ${ver.ifBlank { "" }} $topic\n\n$body"
            break
        }
        val out = (text ?: "Belge bulunamadı: $library / $topic").take(MAX_CHARS)
        writeCache(key, out)
        return out
    }

    /** Follows redirects. Authorization is kept only while the host stays the same. */
    internal suspend fun getClean(url: String, headers: Map<String, String>): String? {
        var current = url
        var hdrs = headers
        var hops = 0
        while (hops++ < 5) {
            val res = runCatching { fetcher.get(current, hdrs) }.getOrNull() ?: return null
            if (res.code in 300..399 && !res.location.isNullOrBlank()) {
                val next = java.net.URI(current).resolve(res.location).toString()
                val same = runCatching { java.net.URI(next).host.equals(java.net.URI(current).host, true) }.getOrDefault(false)
                current = next
                hdrs = if (same) hdrs else hdrs.filterKeys { !it.equals("Authorization", true) }
                continue
            }
            if (res.code in 200..299 && res.body.isNotBlank()) return res.body
            return null
        }
        return null
    }

    private fun readCache(key: String): String? {
        val f = File(cacheDir, "$key.txt")
        if (!f.exists()) return null
        val raw = runCatching { f.readText() }.getOrNull() ?: return null
        val stamp = raw.lineSequence().firstOrNull()?.toLongOrNull() ?: return null
        if (now() - stamp > TTL_MS) return null
        return raw.substringAfter('\n').takeIf { it.isNotBlank() }
    }

    private fun writeCache(key: String, body: String) {
        runCatching {
            cacheDir.mkdirs()
            File(cacheDir, "$key.txt").writeText("${now()}\n$body")
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun digest(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.lowercase().toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(32)
    }

    companion object {
        const val MAX_CHARS = 6_000
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        private val clean by lazy { Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

        /** Redirects stay off. LiveDocsProvider.getClean follows them and strips Authorization across hosts. */
        fun httpFetcher(): DocFetcher = DocFetcher { url, headers ->
            val b = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> b.header(k, v) }
            val r = Http.execute(b.build(), clean)
            DocFetch(r.code, r.body, r.location)
        }
    }
}
