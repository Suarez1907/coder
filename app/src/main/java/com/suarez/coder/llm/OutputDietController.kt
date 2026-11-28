package com.suarez.coder.llm

/**
 * Faz 9 — Çıktı Token Diyeti & Dinamik Düşünme Tavanı (`OutputDietController`).
 *
 * Görev karmaşıklığına göre `thinkingBudget` / `reasoningEffort` ve hedef blok boyutuna göre
 * sıkı `max_tokens` tavanı hesaplar (`min(defaultMax, max(512, targetLines * 24 + 384))`).
 * Yalnızca `finish_reason == "length"` döndüğünde tavanı 1 kez 2x genişletir.
 */
object OutputDietController {

    enum class Difficulty { SIMPLE, MODERATE, COMPLEX_OR_ARCHITECT }
    enum class ThinkingTier(val label: String, val budgetTokens: Int) {
        NONE("minimal", 0),
        MINIMAL("minimal", 512),
        LOW("low", 1_536),
        MEDIUM("medium", 4_096),
        HIGH("high", 8_192),
    }

    data class DietPlan(
        val thinkingTier: ThinkingTier,
        val thinkingBudgetTokens: Int,
        val maxOutputTokens: Int,
        val compactSystemRule: String,
    )

    const val COMPACT_TOOL_PROTOCOL =
        "Araç çağrısı öncesi açıklama, özet veya kod tekrarı yazma; doğrudan araç çağrısını yap."

    /**
     * Computes dynamic thinking budget and tight `max_tokens` cap for a coding turn.
     * Respects [manualThinkingOverride] if the user explicitly pinned a thinking level.
     */
    fun plan(
        difficulty: Difficulty,
        targetLines: Int,
        defaultMaxTokens: Int = 4096,
        manualThinkingOverride: String? = null,
        isReadWriteToolTurn: Boolean = true,
    ): DietPlan {
        val tier = when (manualThinkingOverride?.lowercase()?.trim()) {
            "none" -> ThinkingTier.NONE
            "minimal" -> ThinkingTier.MINIMAL
            "low" -> ThinkingTier.LOW
            "medium" -> ThinkingTier.MEDIUM
            "high", "xhigh" -> ThinkingTier.HIGH
            else -> when (difficulty) {
                Difficulty.SIMPLE -> ThinkingTier.MINIMAL
                Difficulty.MODERATE -> ThinkingTier.LOW
                Difficulty.COMPLEX_OR_ARCHITECT -> ThinkingTier.MEDIUM
            }
        }
        val safeTarget = targetLines.coerceAtLeast(1)
        val dynamicCap = minOf(defaultMaxTokens.coerceAtLeast(512), maxOf(512, safeTarget * 24 + 384))
        return DietPlan(
            thinkingTier = tier,
            thinkingBudgetTokens = tier.budgetTokens,
            maxOutputTokens = dynamicCap,
            compactSystemRule = if (isReadWriteToolTurn) COMPACT_TOOL_PROTOCOL else "",
        )
    }

    /**
     * Expands `max_tokens` once by 2x (capped at [hardCeiling]) only when `truncated` (`finish_reason == "length"`).
     */
    fun expandOnLengthTruncation(
        currentMaxTokens: Int,
        truncated: Boolean,
        alreadyExpanded: Boolean,
        hardCeiling: Int = 16_384,
    ): Int? {
        if (!truncated || alreadyExpanded) return null
        return (currentMaxTokens * 2).coerceAtMost(hardCeiling)
    }
}
