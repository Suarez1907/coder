package com.suarez.coder.apk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** An APK that can be fetched from a link found in the chat. */
sealed class ApkTarget {
    abstract val owner: String
    abstract val repo: String

    data class ReleaseFile(override val owner: String, override val repo: String, val tag: String, val name: String) : ApkTarget()
    data class ReleaseTag(override val owner: String, override val repo: String, val tag: String) : ApkTarget()
    data class ReleaseAsset(override val owner: String, override val repo: String, val id: Long) : ApkTarget()
    data class Artifact(override val owner: String, override val repo: String, val id: Long) : ApkTarget()
    data class Direct(val url: String) : ApkTarget() {
        override val owner = ""
        override val repo = ""
    }
}

object ApkLinks {
    private const val NAME = "[A-Za-z0-9_.-]+"
    private val releaseFile = Regex("https://github\\.com/($NAME)/($NAME)/releases/download/([^/\\s)]+)/([^/\\s)]+\\.apk)")
    private val releaseTag = Regex("https://github\\.com/($NAME)/($NAME)/releases/tag/([^/\\s)\\]>\"'`]+)")
    private val artifactWeb = Regex("https://github\\.com/($NAME)/($NAME)/actions/runs/\\d+/artifacts/(\\d+)")
    private val artifactApi = Regex("https://api\\.github\\.com/repos/($NAME)/($NAME)/actions/artifacts/(\\d+)(?:/zip)?")
    private val assetApi = Regex("https://api\\.github\\.com/repos/($NAME)/($NAME)/releases/assets/(\\d+)")
    private val directApk = Regex("https://[^\\s)\\]>\"'`]+\\.apk")

    /** Distinct downloadable APK links in [text], most specific first (at most [max]). */
    fun find(text: String, max: Int = 3): List<Pair<String, ApkTarget>> {
        val out = LinkedHashMap<String, ApkTarget>()
        releaseFile.findAll(text).forEach { m -> val (o, r, t, n) = m.destructured; out.putIfAbsent(m.value, ApkTarget.ReleaseFile(o, r, t, n)) }
        artifactWeb.findAll(text).forEach { m -> val (o, r, id) = m.destructured; out.putIfAbsent(m.value, ApkTarget.Artifact(o, r, id.toLong())) }
        artifactApi.findAll(text).forEach { m -> val (o, r, id) = m.destructured; out.putIfAbsent(m.value, ApkTarget.Artifact(o, r, id.toLong())) }
        assetApi.findAll(text).forEach { m -> val (o, r, id) = m.destructured; out.putIfAbsent(m.value, ApkTarget.ReleaseAsset(o, r, id.toLong())) }
        releaseTag.findAll(text).forEach { m ->
            val (o, r, t) = m.destructured
            out.putIfAbsent(m.value.trimEnd('.', ','), ApkTarget.ReleaseTag(o, r, t.trimEnd('.', ',')))
        }
        directApk.findAll(text).forEach { m -> if (out.keys.none { it == m.value }) out.putIfAbsent(m.value, ApkTarget.Direct(m.value)) }
        return out.entries.take(max).map { it.key to it.value }
    }
}

/**
 * Downloads APKs from GitHub, including private repos (token), release assets (Accept: application/octet-stream)
 * and Actions artifacts (.zip, the APK inside is extracted). Redirects are followed by hand so the Authorization
 * header only ever goes to GitHub, never to the storage host a download redirects to.
 */
class ApkDownloader(
    private val token: () -> String,
    private val dir: File,
    baseClient: OkHttpClient,
    private val apiBase: String = "https://api.github.com",
) {
    private val client = baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()

    class DownloadException(message: String) : Exception(message)

    suspend fun download(target: ApkTarget, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val (url, accept) = resolve(target)
        val tmp = File(dir, "download.tmp")
        fetch(url, accept, tmp, onProgress)
        val name = when (target) {
            is ApkTarget.ReleaseFile -> target.name
            is ApkTarget.Direct -> target.url.substringAfterLast('/')
            else -> "${target.repo}-${System.currentTimeMillis() / 1000}.apk"
        }.replace(Regex("[^A-Za-z0-9_.-]"), "_").let { if (it.endsWith(".apk")) it else "$it.apk" }
        val out = File(dir, name)
        if (isZip(tmp)) unzipApk(tmp, out) else { out.delete(); tmp.renameTo(out) }
        tmp.delete()
        out
    }

    private fun apiGet(path: String): JSONObject {
        val req = Request.Builder().url("$apiBase$path").header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Coder-Android").apply { auth()?.let { header("Authorization", it) } }.build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw DownloadException("GitHub ${r.code}: ${r.body?.string()?.take(200)}")
            return JSONObject(r.body!!.string())
        }
    }

    private fun auth(): String? = token().takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    /** Returns the URL to download and its Accept header. */
    internal fun resolve(target: ApkTarget): Pair<String, String> = when (target) {
        is ApkTarget.Direct -> target.url to "*/*"
        is ApkTarget.ReleaseAsset -> "$apiBase/repos/${target.owner}/${target.repo}/releases/assets/${target.id}" to "application/octet-stream"
        is ApkTarget.Artifact -> {
            if (auth() == null) throw DownloadException("Artifact indirmek için GitHub token gerekli (Ayarlar > GitHub & Bütçe).")
            "$apiBase/repos/${target.owner}/${target.repo}/actions/artifacts/${target.id}/zip" to "application/vnd.github+json"
        }
        is ApkTarget.ReleaseFile, is ApkTarget.ReleaseTag -> {
            val tag = if (target is ApkTarget.ReleaseFile) target.tag else (target as ApkTarget.ReleaseTag).tag
            val release = apiGet("/repos/${target.owner}/${target.repo}/releases/tags/$tag")
            val assets = release.optJSONArray("assets") ?: throw DownloadException("Sürümde dosya yok.")
            val wanted = (target as? ApkTarget.ReleaseFile)?.name
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { a -> if (wanted != null) a.optString("name") == wanted else a.optString("name").endsWith(".apk") }
                ?: throw DownloadException("Bu sürümde APK yok.")
            asset.getString("url") to "application/octet-stream"
        }
    }

    /** GET with manual redirects; Authorization is sent only to the original (GitHub) host. */
    internal suspend fun fetch(url: String, accept: String, dest: File, onProgress: (Int) -> Unit) {
        // The token may only go to the GitHub API origin itself: not to redirect targets (storage hosts)
        // and never to third-party hosts linked in the chat.
        val api = apiBase.toHttpUrl()
        var current = url
        repeat(MAX_REDIRECTS) {
            val target = current.toHttpUrl()
            val builder = Request.Builder().url(target).header("Accept", accept).header("User-Agent", "Coder-Android")
            if (target.scheme == api.scheme && target.host == api.host && target.port == api.port) {
                auth()?.let { builder.header("Authorization", it) }
            }
            client.newCall(builder.build()).execute().use { r ->
                if (r.isRedirect) {
                    current = r.header("Location")?.let { target.resolve(it)?.toString() } ?: throw DownloadException("Yönlendirme adresi yok")
                    return@use
                }
                if (!r.isSuccessful) throw DownloadException("İndirme başarısız (HTTP ${r.code})")
                val body = r.body ?: throw DownloadException("Boş yanıt")
                val total = body.contentLength()
                var read = 0L
                var lastPct = -1
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                }
                onProgress(100)
                return
            }
        }
        throw DownloadException("Çok fazla yönlendirme")
    }

    private fun isZip(f: File): Boolean = f.length() > 4 && f.inputStream().use { s ->
        val b = ByteArray(2); s.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
    }

    /** Actions artifacts are zips: extract the first .apk inside. (An .apk itself is also a zip, so check entries.) */
    private fun unzipApk(zip: File, out: File) {
        ZipInputStream(zip.inputStream()).use { z ->
            var hasManifest = false
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "AndroidManifest.xml") hasManifest = true
                if (!e.isDirectory && e.name.endsWith(".apk")) {
                    out.outputStream().use { z.copyTo(it) }
                    return
                }
            }
            // No inner .apk: the download itself is an APK (APKs are zip files).
            if (hasManifest) { out.delete(); zip.copyTo(out, overwrite = true); return }
        }
        throw DownloadException("Arşivde APK bulunamadı.")
    }

    companion object {
        const val MAX_REDIRECTS = 6
    }
}
