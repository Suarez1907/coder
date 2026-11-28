package com.suarez.coder.evolution

import kotlin.math.abs

/**
 * Faz 7B — MAP-Elites Çok Boyutlu Evrimsel Niş Arşivi (`MapElitesArchive`).
 *
 * 3 Eksenli Davranışsal Izgara (3 x 2 x 5 = 30 niş hücresi):
 * - [Complexity]: `SIMPLE`, `MODERATE`, `COMPLEX`
 * - [Risk]: `LOW_RISK`, `HIGH_RISK`
 * - [TaskDomain]: `UI_COMPOSE`, `KOTLIN_CORE`, `CI_GRADLE_YAML`, `GAME_3D_SCRIPT`, `GENERAL`
 *
 * Kurallar:
 * - Yalnızca Uygulayıcı (Implementer) stratejisini optimize eder; kilitli Mimar modeline asla dokunmaz.
 * - Bir hücrenin şampiyonu yalnızca `>= %5` daha yüksek `fitnessScore` ve `zeroInvariantRegressions == true`
 *   ile güncellenir; komşu hücreler etkilenmez.
 * - Boş hücrede en yakın dolu komşudan güvenle miras alınır.
 */
class MapElitesArchive {

    companion object {
        const val MIN_PROMOTION_GAIN_RATIO = 0.05
        const val TOTAL_CELLS = 3 * 2 * 5
    }

    enum class Complexity { SIMPLE, MODERATE, COMPLEX }
    enum class Risk { LOW_RISK, HIGH_RISK }
    enum class TaskDomain { UI_COMPOSE, KOTLIN_CORE, CI_GRADLE_YAML, GAME_3D_SCRIPT, GENERAL }

    data class NicheKey(
        val complexity: Complexity,
        val risk: Risk,
        val domain: TaskDomain,
    )

    data class EliteGenome(
        val implementerModelId: String,
        val turn0CharBudget: Int = 10_000,
        val preferTypedHoles: Boolean = true,
        val swarmCandidateCount: Int = 1,
        val promptTacticId: String = "compact_diff",
        val fitnessScore: Double = 0.5,
    )

    private val cells = LinkedHashMap<NicheKey, EliteGenome>()

    @Synchronized
    fun filledCount(): Int = cells.size

    @Synchronized
    fun all(): Map<NicheKey, EliteGenome> = LinkedHashMap(cells)

    @Synchronized
    fun reset() = cells.clear()

    /**
     * Non-regressive cell promotion:
     * - Empty cell: accepts any non-regressive genome (`zeroRegressions == true`).
     * - Occupied cell: requires `>= 5%` higher `fitnessScore` AND `zeroRegressions == true`.
     * Neighbor cells are never modified.
     */
    @Synchronized
    fun tryPromote(key: NicheKey, candidate: EliteGenome, zeroRegressions: Boolean): Boolean {
        if (!zeroRegressions) return false
        val current = cells[key]
        if (current == null) {
            cells[key] = candidate
            return true
        }
        val required = current.fitnessScore * (1.0 + MIN_PROMOTION_GAIN_RATIO)
        if (candidate.fitnessScore >= required) {
            cells[key] = candidate
            return true
        }
        return false
    }

    /** Returns exact cell champion, or safely inherits from the nearest populated neighbor. */
    @Synchronized
    fun lookupOrNearest(key: NicheKey): EliteGenome? {
        cells[key]?.let { return it }
        if (cells.isEmpty()) return null
        return cells.entries.minByOrNull { (k, _) -> distance(k, key) }?.value
    }

    /** Offline crossover + light mutation between two parent genomes (Implementer-only). */
    fun crossoverAndMutate(a: EliteGenome, b: EliteGenome, mutateBudget: Boolean = false): EliteGenome {
        val budgets = listOf(6_000, 10_000, 14_000)
        val pickedBudget = if (mutateBudget) budgets[(budgets.indexOf(a.turn0CharBudget) + 1) % budgets.size] else b.turn0CharBudget
        return EliteGenome(
            implementerModelId = if (a.fitnessScore >= b.fitnessScore) a.implementerModelId else b.implementerModelId,
            turn0CharBudget = pickedBudget,
            preferTypedHoles = a.preferTypedHoles || b.preferTypedHoles,
            swarmCandidateCount = ((a.swarmCandidateCount + b.swarmCandidateCount) / 2).coerceIn(1, 3),
            promptTacticId = a.promptTacticId,
            fitnessScore = 0.0,
        )
    }

    fun classify(fileCount: Int, touchesCoreOrCi: Boolean, paths: List<String>): NicheKey {
        val complexity = when {
            fileCount <= 1 -> Complexity.SIMPLE
            fileCount <= 3 -> Complexity.MODERATE
            else -> Complexity.COMPLEX
        }
        val risk = if (touchesCoreOrCi) Risk.HIGH_RISK else Risk.LOW_RISK
        val joined = paths.joinToString(" ").lowercase()
        val domain = when {
            "ui/" in joined || "screen.kt" in joined -> TaskDomain.UI_COMPOSE
            ".github/" in joined || "build.gradle" in joined || ".yml" in joined -> TaskDomain.CI_GRADLE_YAML
            ".gd" in joined || "godot" in joined || "three" in joined -> TaskDomain.GAME_3D_SCRIPT
            ".kt" in joined -> TaskDomain.KOTLIN_CORE
            else -> TaskDomain.GENERAL
        }
        return NicheKey(complexity, risk, domain)
    }

    private fun distance(a: NicheKey, b: NicheKey): Int =
        abs(a.complexity.ordinal - b.complexity.ordinal) +
            abs(a.risk.ordinal - b.risk.ordinal) * 2 +
            (if (a.domain == b.domain) 0 else 3)
}
