package com.suarez.coder.evolution

/**
 * Picks an implementer model from cost-to-green scores. The architect id is filtered out of every candidate list and
 * is never written back into a role map.
 */
class ImplementerExperimentEngine(private val scorer: CostToGreenScorer) {
    fun select(
        domain: TaskDomain,
        candidates: List<String>,
        architectModelId: String,
        baselineModelId: String,
        experiments: Boolean,
        pinned: String? = null,
        roll: Double = Math.random(),
        otherIndex: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val architect = architectModelId.trim()
        fun allowed(id: String) = id.isNotBlank() && !sameModel(id, architect)
        val pin = pinned?.trim()?.takeIf { allowed(it) }
        if (pin != null) return pin
        if (!experiments) return null
        val pool = (candidates + baselineModelId).map { it.trim() }.filter { allowed(it) }.distinct()
        if (pool.isEmpty()) return null
        val ranked = pool.map { scorer.score(it, domain, now) }
            .filter { it.cooldownUntil <= now }
            .sortedByDescending { scorer.selectionScore(it) }
        if (ranked.isEmpty()) return baselineModelId.takeIf { allowed(it) } ?: pool.firstOrNull()
        if (roll < 0.80 || ranked.size == 1) return ranked.first().modelId
        val others = ranked.drop(1)
        return others[otherIndex.coerceIn(0, others.lastIndex)].modelId
    }

    /**
     * Replaces only the editor entry. The architect entry is put back to the value it had, and a chosen id that is
     * the architect is ignored.
     */
    fun applyEditorOnly(models: MutableMap<String, String>, chosenEditor: String?, architectModelId: String): Map<String, String> {
        val architect = models["architect"]
        if (chosenEditor != null && !sameModel(chosenEditor, architectModelId) && !sameModel(chosenEditor, architect ?: "")) {
            models["editor"] = chosenEditor
        }
        if (architect != null) models["architect"] = architect
        return models
    }

    private fun sameModel(a: String, b: String) = a.trim().equals(b.trim(), true)
}
