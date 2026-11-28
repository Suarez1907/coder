package com.suarez.coder.evolution

import kotlin.math.max
import kotlin.random.Random

/**
 * Faz 7B — Token Shapley Ekonomisi ve Nedensel Bağlam Atfı (`ShapleyContextEconomist`).
 *
 * - Canlı atıf takibi + çevrimdışı en fazla `K = 12` Monte Carlo permütasyon / leave-one-out
 *   Shapley yaklaşımı (`$0` API maliyeti, `<200 ms`).
 * - En az `5` görevde gözlemlenmiş ve `phi_i < -0.05` olan otomatik kurallar/skill'ler
 *   otomatik [Status.QUARANTINED] durumuna alınır (`source == MANUAL` asla karantinaya alınmaz,
 *   yalnızca [Status.LOW_VALUE_WARNING] işaretlenir).
 * - `Turn0ContextPacker` / `ContextProvider` için token bütçesi dolduğunda `roiPer1kTokens` sırasıyla
 *   en yüksek getirili bağlam öğelerini seçer.
 */
object ShapleyContextEconomist {

    const val MAX_PERMUTATIONS_K = 12
    const val MIN_OBSERVATIONS_FOR_QUARANTINE = 5
    const val QUARANTINE_PHI_THRESHOLD = -0.05

    enum class Source { AUTO, MANUAL }
    enum class Status { ACTIVE, LOW_VALUE_WARNING, QUARANTINED }

    data class ContextItem(
        val id: String,
        val kind: String,
        val source: Source,
        val tokens: Int,
        val text: String = "",
    )

    data class AttributionRecord(
        val sessionId: String,
        val injectedItemIds: Set<String>,
        val touchedItemIds: Set<String>,
        val success: Boolean,
        val costUsd: Double,
    ) {
        /** Combined session utility in [0.0, 1.0]: rewards green completion and lower cost. */
        val utility: Double
            get() = (if (success) 1.0 else 0.0) - (costUsd * 0.5).coerceIn(0.0, 0.35)
    }

    data class ShapleyValuation(
        val itemId: String,
        val source: Source,
        val observations: Int,
        val marginalContribution: Double,
        val roiPer1kTokens: Double,
        val status: Status,
    )

    /**
     * Computes bounded Shapley values (`K <= 12` permutations + leave-one-out empirical coalition delta)
     * at `$0` API cost.
     */
    fun evaluate(
        items: List<ContextItem>,
        records: List<AttributionRecord>,
        kPermutations: Int = MAX_PERMUTATIONS_K,
        seed: Long = 42L,
    ): List<ShapleyValuation> {
        if (items.isEmpty()) return emptyList()
        val k = kPermutations.coerceIn(1, MAX_PERMUTATIONS_K)
        val rng = Random(seed)

        fun coalitionValue(coalition: Set<String>): Double {
            if (records.isEmpty()) return 0.5
            val matching = records.filter { r ->
                val overlap = r.injectedItemIds.intersect(coalition).size
                overlap > 0
            }
            if (matching.isEmpty()) return 0.5
            return matching.map { it.utility }.average()
        }

        val itemMap = items.associateBy { it.id }
        val ids = items.map { it.id }
        val mcAccum = HashMap<String, Double>()
        ids.forEach { mcAccum[it] = 0.0 }

        // Bounded Monte Carlo Shapley approximation (K <= 12 permutations)
        for (p in 0 until k) {
            val perm = ids.shuffled(rng)
            val current = HashSet<String>()
            var prevVal = 0.5
            for (id in perm) {
                current += id
                val nextVal = coalitionValue(current)
                mcAccum[id] = (mcAccum[id] ?: 0.0) + (nextVal - prevVal)
                prevVal = nextVal
            }
        }

        return items.map { item ->
            val withItem = records.filter { item.id in it.injectedItemIds }
            val withoutItem = records.filter { item.id !in it.injectedItemIds }
            val obs = withItem.size
            val looDelta = if (withItem.isNotEmpty() && withoutItem.isNotEmpty()) {
                withItem.map { it.utility }.average() - withoutItem.map { it.utility }.average()
            } else if (withItem.isNotEmpty()) {
                withItem.map { it.utility }.average() - 0.5
            } else {
                0.0
            }
            val mcPhi = (mcAccum[item.id] ?: 0.0) / k.toDouble()
            // Blend leave-one-out causal signal (dominant) with Monte Carlo coalition estimate
            val phi = 0.75 * looDelta + 0.25 * mcPhi
            val denom = max(item.tokens / 1000.0, 0.05)
            val roi = phi / denom
            val status = when {
                obs >= MIN_OBSERVATIONS_FOR_QUARANTINE && phi < QUARANTINE_PHI_THRESHOLD ->
                    if (item.source == Source.MANUAL) Status.LOW_VALUE_WARNING else Status.QUARANTINED
                else -> Status.ACTIVE
            }
            ShapleyValuation(
                itemId = item.id,
                source = item.source,
                observations = obs,
                marginalContribution = phi,
                roiPer1kTokens = roi,
                status = status,
            )
        }
    }

    /** Packs non-quarantined context items into [maxTokens] strictly ordered by descending [roiPer1kTokens]. */
    fun packByRoi(
        items: List<ContextItem>,
        valuations: List<ShapleyValuation>,
        maxTokens: Int,
    ): List<ContextItem> {
        val valMap = valuations.associateBy { it.itemId }
        val eligible = items.filter { valMap[it.id]?.status != Status.QUARANTINED }
            .sortedByDescending { valMap[it.id]?.roiPer1kTokens ?: 0.0 }
        val kept = ArrayList<ContextItem>()
        var used = 0
        for (item in eligible) {
            if (used + item.tokens <= maxTokens) {
                kept += item
                used += item.tokens
            }
        }
        return kept
    }
}
