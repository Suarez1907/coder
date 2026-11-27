package com.suarez.coder.qa

/**
 * One cheap vision pass over UI snapshots produced by CI or the hot sandbox.
 * No model is hard-wired: the caller supplies a judge from the user's providers.
 * With no judge, a deterministic check only flags empty or black images.
 */
object VisualQaGate {
    const val MAX_FIX_ROUNDS = 2
    const val PASS = "PASS"
    const val VISUAL_DEFECT = "VISUAL_DEFECT"

    data class Result(val verdict: String, val issues: List<String>) {
        val passed: Boolean get() = verdict == PASS
    }

    data class Probe(val name: String, val bytes: ByteArray?)

    /** UI / visual surfaces. A pure logic edit does not request a vision pass. */
    fun touchesUi(paths: Collection<String>): Boolean = paths.any { path ->
        val p = path.replace('\\', '/')
        p.contains("/ui/") || p.startsWith("ui/") ||
            p.contains("/res/layout/") || p.startsWith("res/layout/") ||
            p.endsWith(".tscn") || p.endsWith(".gd") ||
            (p.contains("/blender/") && p.endsWith(".py")) || p.startsWith("blender/") && p.endsWith(".py")
    }

    fun collectArtifacts(names: Collection<String>): List<String> =
        names.map { it.trim() }.filter { name ->
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            base.matches(Regex("(?i).*(preview|snapshot|roborazzi|paparazzi).*\\.png"))
        }.distinct().sorted()

    /** Strict JSON. Anything else is a defect so a sloppy model cannot sneak a merge through. */
    fun parse(json: String): Result {
        val o = runCatching { org.json.JSONObject(json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }.getOrNull()
            ?: return Result(VISUAL_DEFECT, listOf("Görsel QA JSON ayrışmadı."))
        val verdict = o.optString("verdict").trim()
        val issues = o.optJSONArray("issues")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        return when (verdict) {
            PASS -> Result(PASS, emptyList())
            VISUAL_DEFECT -> Result(VISUAL_DEFECT, issues.ifEmpty { listOf("Görsel kusur bildirildi.") })
            else -> Result(VISUAL_DEFECT, listOf("Beklenmeyen hüküm: $verdict"))
        }
    }

    /** $0 fallback. Empty or near-black payloads are defects; a normal PNG is not guessed into a failure. */
    fun heuristic(images: List<Probe>): Result {
        if (images.isEmpty()) return Result(VISUAL_DEFECT, listOf("Görsel artifact yok."))
        val issues = ArrayList<String>()
        for (img in images) {
            val b = img.bytes
            if (b == null || b.size < 32) {
                issues += "${img.name}: boş veya siyah render"
                continue
            }
            val sample = b.copyOfRange(0, minOf(b.size, 512))
            val nonzero = sample.count { it.toInt() != 0 }
            if (nonzero < sample.size / 20) issues += "${img.name}: boş veya siyah render"
        }
        return if (issues.isEmpty()) Result(PASS, emptyList()) else Result(VISUAL_DEFECT, issues)
    }

    fun prompt(): String = "Ekran görüntüsünü denetle. Yalnızca JSON döndür: " +
        "{\"verdict\":\"PASS\"|\"VISUAL_DEFECT\",\"issues\":[\"...\"]}. " +
        "Kusurlar: kesilen veya taşan metin, durum çubuğu çakışması, düşük kontrast, bozuk hizalama, boş veya siyah render. Emoji yok."

    fun blocksMerge(result: Result?): Boolean = result != null && result.verdict == VISUAL_DEFECT
}
