package com.suarez.coder.evolution

import java.util.Calendar

/** The fixed safety rules of self-evolution (unit-tested; none of them can be changed by a model). */
object EvolutionGuard {
    const val DEFAULT_THRESHOLD = 1.5
    const val MIN_THRESHOLD = 1.0
    const val MAX_THRESHOLD = 2.2
    const val NOTES_LIMIT = 4_000
    const val MAX_HINTS = 30
    const val MAX_LESSONS_PER_RUN = 3
    const val MAX_PR_FILES = 4
    const val CI_FIX_ROUNDS = 2
    const val COOLDOWN_MS = 30 * 60_000L
    const val SELF_REPO = "Suarez1907/coder"
    const val BRANCH_PREFIX = "evo/"
    /** A self-PR needs real evidence: repeated trouble in the triggering run (or across recent runs). */
    const val MIN_TROUBLE_FOR_PR = 3

    fun clampThreshold(v: Double) = if (v.isNaN()) DEFAULT_THRESHOLD else v.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    /** Never evaluate an evolution run, never chain evolutions. */
    fun mayRecord(isEvolutionSession: Boolean, hadWork: Boolean) = !isEvolutionSession && hadWork

    enum class Block { DISABLED, EVOLUTION_SESSION, BUDGET, COOLDOWN, NO_KEY, OPEN_PR, REJECTED_TOPIC, NO_EVIDENCE, OK }

    fun mayRun(enabled: Boolean, isEvolutionSession: Boolean, hasKey: Boolean, spentToday: Double, dailyBudget: Double,
               lastRunAt: Long, now: Long, force: Boolean = false): Block = when {
        isEvolutionSession -> Block.EVOLUTION_SESSION
        !enabled && !force -> Block.DISABLED
        !hasKey -> Block.NO_KEY
        spentToday >= dailyBudget -> Block.BUDGET
        !force && now - lastRunAt < COOLDOWN_MS -> Block.COOLDOWN
        else -> Block.OK
    }

    /** At most one open evo/... PR, budget left, a topic not rejected before, and enough evidence. */
    fun mayOpenPr(openEvoPrs: Int, localPending: Boolean, spentToday: Double, dailyBudget: Double,
                  topic: String, rejected: Set<String>, trouble: Int): Block = when {
        openEvoPrs > 0 || localPending -> Block.OPEN_PR
        spentToday >= dailyBudget -> Block.BUDGET
        normalizeTopic(topic) in rejected -> Block.REJECTED_TOPIC
        trouble < MIN_TROUBLE_FOR_PR -> Block.NO_EVIDENCE
        else -> Block.OK
    }

    fun normalizeTopic(t: String) = t.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

    /**
     * Files a self-PR may never touch: credentials and their storage, signing, money/budget locks, the build and CI
     * definitions, and the evolution engine with its own guards.
     */
    private val PROTECTED = listOf(
        Regex("(^|/)evolution/"), Regex("^\\.github/"), Regex("\\.(jks|keystore|p12)$"), Regex("(^|/)build\\.gradle(\\.kts)?$"),
        Regex("(^|/)settings\\.gradle(\\.kts)?$"), Regex("(^|/)gradle\\.properties$"), Regex("(^|/)gradle/wrapper/"),
        Regex("(^|/)AndroidManifest\\.xml$"), Regex("(^|/)data/Settings\\.kt$"), Regex("(^|/)apk/ApkDownloader\\.kt$"),
        Regex("(^|/)apk/ApkInstaller\\.kt$"), Regex("(^|/)cost/"), Regex("(^|/)local\\.properties$"),
        Regex("(?i)(token|secret|credential|password|auth|keystore|signing)"),
    )

    fun isProtected(path: String): Boolean {
        val p = path.trim().removePrefix("./").removePrefix("/")
        return PROTECTED.any { it.containsMatchIn(p) }
    }

    /** Null when the plan is acceptable, otherwise why it is rejected. */
    fun checkPlan(paths: List<String>, text: String): String? {
        if (paths.isEmpty()) return "Plan dosya içermiyor."
        if (paths.size > MAX_PR_FILES) return "Plan çok geniş (${paths.size} dosya; en fazla $MAX_PR_FILES)."
        paths.firstOrNull { isProtected(it) }?.let { return "Korumalı dosya: $it" }
        val risky = Regex("(?i)(auto.?merge|githubToken|apiKey|api_key|budget|bütçe|keystore|signingConfig|EvolutionGuard)")
        risky.find(text)?.let { return "Plan güvenlik sınırına dokunuyor: '${it.value}'" }
        return null
    }

    /**
     * Notes stay under [NOTES_LIMIT] characters. Above it the model is asked to merge them; if that is not enough,
     * the oldest lesson lines are dropped (headings and the first lines are kept).
     */
    fun hardPrune(notes: String, limit: Int = NOTES_LIMIT): String {
        if (notes.length <= limit) return notes
        val lines = notes.lines().toMutableList()
        while (lines.joinToString("\n").length > limit) {
            val idx = lines.indexOfFirst { it.trimStart().startsWith("- ") }
            if (idx < 0) break
            lines.removeAt(idx)
        }
        return lines.joinToString("\n").take(limit)
    }

    fun dayStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
