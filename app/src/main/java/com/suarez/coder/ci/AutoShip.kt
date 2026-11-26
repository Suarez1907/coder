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

        /**
         * Yalnızca kesin kanıt: aynı commit (tam/kısa sha) ya da aynı sürüm adı
         * (v1.3.0 = 1.3.0; 1.3.0 ≠ 3.0). build.yml sürümü `target_commitish: github.sha` ile
         * açtığı için sha eşleşmesi yeterlidir. Dal adı ve gevşek "ile bitiyor" eşleşmesi
         * kaldırıldı: bunlar eski bir sürümü yeni build'in sürümü sayıp yanlış APK gösterirdi.
         */
        fun matches(ref: String): Boolean {
            val r = ref.trim()
            if (r.isEmpty()) return false
            if (isSha(r)) return sameCommit(commitish, r)
            if (!isSha(commitish)) return false
            val a = norm(version)
            return a.isNotEmpty() && a == norm(r)
        }

        /** "v1.3.0" ile "1.3.0" aynı sürüm; nokta/tire ve v ön eki yok sayılır. */
        private fun norm(s: String) = s.filter { it.isLetterOrDigit() }.lowercase().removePrefix("v")

        private fun sameCommit(a: String, b: String): Boolean {
            val x = a.trim().lowercase()
            val y = b.trim().lowercase()
            if (!isSha(x) || x.length < 7) return false
            return x == y || y.startsWith(x) || x.startsWith(y)
        }
    }

    enum class State { SHIPPED, NO_APK, NO_RELEASE }

    /**
     * [sha]/[branch] için yayınlanmış sürüm. Ref verilmiş ama eşleşen sürüm yoksa null:
     * başka commit'in sürümünü "bu build yayınlandı" diye göstermek yanlış APK demek olurdu.
     * Hiç ref verilmezse en yeni yayınlanmış sürüm seçilir. Taslak asla seçilmez.
     */
    fun pick(body: String, sha: String? = null, branch: String? = null): Ship? =
        pick(body, listOfNotNull(sha, branch))

    fun pick(body: String, refs: List<String>): Ship? {
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val ships = (0 until arr.length()).mapNotNull { parse(arr.optJSONObject(it)) }
        if (ships.isEmpty()) return null
        val wanted = refs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (ref in wanted) ships.firstOrNull { it.matches(ref) }?.let { return it }
        if (wanted.isNotEmpty()) return null
        return ships.firstOrNull { !it.prerelease } ?: ships.first()
    }

    private val SHA = Regex("^[0-9a-f]{7,40}$")

    private fun isSha(s: String): Boolean = SHA.matches(s)

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
