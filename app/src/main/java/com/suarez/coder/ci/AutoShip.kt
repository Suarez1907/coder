package com.suarez.coder.ci

import org.json.JSONArray
import org.json.JSONObject

/**
 * Auto-ship: build.yml her yeşil commit için bir sürüm yayınlar (tag build-<run_number>,
 * asset Coder-<n>.apk). Bu nesne o commit'e ait sürümü bulur ve yeşil build hiçbir şey
 * yayınlamadıysa (release adımı düştü / asset yüklenmedi) tek satırlık uyarı üretir.
 */
object AutoShip {

    data class Ship(
        val tag: String,
        val name: String,
        val apkUrl: String?,
        val pageUrl: String,
        val commitish: String,
        val prerelease: Boolean,
    ) {
        val version: String get() = tag.ifBlank { name }

        /** Aynı commit/dal ya da aynı sürüm numarası (v1.3.0 ≈ 1.3.0 ≈ coder-1.3.0). */
        fun matches(ref: String): Boolean {
            val r = ref.trim()
            if (r.isEmpty()) return false
            if (commitish.isNotBlank() && sameCommit(commitish, r)) return true
            val a = version.filter { it.isLetterOrDigit() }.lowercase()
            val b = r.substringAfterLast('/').filter { it.isLetterOrDigit() }.lowercase()
            return a.isNotEmpty() && b.isNotEmpty() && (a == b || a.endsWith(b) || b.endsWith(a))
        }

        private fun sameCommit(a: String, b: String): Boolean =
            a == b || (a.length >= 7 && b.startsWith(a)) || (b.length >= 7 && a.startsWith(b))
    }

    enum class State { SHIPPED, NO_APK, NO_RELEASE }

    /** Commit'e ([sha]/[branch]) ait sürüm; yoksa en yeni yayınlanmış sürüm. Taslak asla gönderilmez. */
    fun pick(body: String, sha: String? = null, branch: String? = null): Ship? {
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val ships = (0 until arr.length()).mapNotNull { parse(arr.optJSONObject(it)) }
        if (ships.isEmpty()) return null
        val refs = listOfNotNull(sha, branch).map { it.trim() }.filter { it.isNotEmpty() }
        for (ref in refs) ships.firstOrNull { it.matches(ref) }?.let { return it }
        return ships.firstOrNull { !it.prerelease } ?: ships.first()
    }

    private fun parse(o: JSONObject?): Ship? {
        if (o == null || o.optBoolean("draft")) return null
        var apk: String? = null
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (!a.optString("name").endsWith(".apk", ignoreCase = true)) continue
                val u = a.optString("browser_download_url").ifBlank { a.optString("url") }
                if (u.isNotBlank()) { apk = u; break }
            }
        }
        val tag = o.optString("tag_name")
        val page = o.optString("html_url")
        if (tag.isBlank() && page.isBlank()) return null
        return Ship(tag, o.optString("name"), apk, page, o.optString("target_commitish"), o.optBoolean("prerelease"))
    }

    fun state(ship: Ship?): State = when {
        ship == null -> State.NO_RELEASE
        ship.apkUrl.isNullOrBlank() -> State.NO_APK
        else -> State.SHIPPED
    }

    /** Yeşil build kurulabilir bir APK yayınlamadıysa tek satır uyarı; sürüm normalse null. */
    fun warning(ship: Ship?, repo: String, ref: String, runUrl: String?): String? {
        val page = runUrl ?: "https://github.com/$repo/actions"
        return when (state(ship)) {
            State.SHIPPED -> null
            State.NO_APK -> "Otomatik sürüm: ${ship!!.version} yayınlandı ama APK eki yok — ${ship.pageUrl.ifBlank { page }}"
            State.NO_RELEASE -> "Otomatik sürüm: CI yeşil ama ${ref.take(7)} için sürüm yayınlanmadı (release adımı düşmüş olabilir) — $page"
        }
    }
}
