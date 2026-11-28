package com.suarez.coder.evolution

/**
 * Faz 7B — Brier Skorlu Kalibrasyon ve Çeyrek-Kelly Bütçe Piyasası (`KellyBudgetMarket`).
 *
 * Kırmızı Çizgi: Yalnızca **Uygulayıcı (Implementer)** modellerinin trafik ve deney bütçesi paylarını
 * dağıtır; kullanıcının kilitli **Mimar (Architect)** modeline asla dokunmaz.
 *
 * - Son 25 görevde Brier Skoru: `mean((p_t - y_t)^2)` (düşük daha dürüst/kalibre).
 * - Güvenli Çeyrek-Kelly (`0.25 * f*`) dağılımı, her model için sert `[0.05, 0.70]` ağırlık sınırlarıyla.
 */
object KellyBudgetMarket {

    const val WINDOW_SIZE = 25
    const val QUARTER_KELLY_FACTOR = 0.25
    const val MIN_WEIGHT = 0.05
    const val MAX_WEIGHT = 0.70

    data class PredictionRecord(
        val implementerModelId: String,
        val predictedSuccessProb: Double,
        val actualSuccess: Boolean,
        val costUsd: Double,
    )

    data class ModelAllocation(
        val implementerModelId: String,
        val brierScore: Double,
        val empiricalWinRate: Double,
        val avgCostUsd: Double,
        val kellyWeight: Double,
        val sampleCount: Int,
    )

    data class MarketSnapshot(
        /** Echo of locked architect id — guaranteed untouched by the Kelly market. */
        val lockedArchitectModelId: String,
        val allocations: List<ModelAllocation>,
    )

    fun brierScore(records: List<PredictionRecord>): Double {
        if (records.isEmpty()) return 0.25
        val window = records.takeLast(WINDOW_SIZE)
        return window.map { r ->
            val p = r.predictedSuccessProb.coerceIn(0.0, 1.0)
            val y = if (r.actualSuccess) 1.0 else 0.0
            (p - y) * (p - y)
        }.average()
    }

    /**
     * Computes Quarter-Kelly allocations clamped strictly to [MIN_WEIGHT, MAX_WEIGHT].
     * Never modifies [lockedArchitectModelId].
     */
    fun allocate(
        lockedArchitectModelId: String,
        implementerPool: List<String>,
        history: List<PredictionRecord>,
    ): MarketSnapshot {
        val uniquePool = implementerPool.filter { it.isNotBlank() }.distinct()
        if (uniquePool.isEmpty()) return MarketSnapshot(lockedArchitectModelId, emptyList())

        val byModel = history.groupBy { it.implementerModelId }
        val rawScores = LinkedHashMap<String, Double>()
        val stats = LinkedHashMap<String, Triple<Double, Double, Double>>() // brier, winRate, avgCost

        for (modelId in uniquePool) {
            val recs = byModel[modelId].orEmpty().takeLast(WINDOW_SIZE)
            val brier = brierScore(recs)
            val winRate = if (recs.isEmpty()) 0.55 else recs.count { it.actualSuccess }.toDouble() / recs.size
            val avgCost = if (recs.isEmpty()) 0.01 else recs.map { it.costUsd.coerceAtLeast(0.0005) }.average()

            // Effective edge penalized by Brier miscalibration and normalized by relative cost
            val calibrationFactor = (1.0 - brier).coerceIn(0.1, 1.0)
            val odds = (0.02 / avgCost).coerceIn(0.5, 4.0)
            val fullKelly = ((winRate * odds - (1.0 - winRate)) / odds).coerceAtLeast(0.0)
            val quarterKelly = QUARTER_KELLY_FACTOR * fullKelly * calibrationFactor
            rawScores[modelId] = quarterKelly.coerceAtLeast(0.01)
            stats[modelId] = Triple(brier, winRate, avgCost)
        }

        // Normalize and enforce [MIN_WEIGHT, MAX_WEIGHT] strictly
        val weights = clampAndNormalize(rawScores)
        val allocations = uniquePool.map { id ->
            val (brier, win, cost) = stats[id]!!
            ModelAllocation(
                implementerModelId = id,
                brierScore = brier,
                empiricalWinRate = win,
                avgCostUsd = cost,
                kellyWeight = weights[id] ?: MIN_WEIGHT,
                sampleCount = byModel[id].orEmpty().takeLast(WINDOW_SIZE).size,
            )
        }.sortedByDescending { it.kellyWeight }

        return MarketSnapshot(lockedArchitectModelId, allocations)
    }

    private fun clampAndNormalize(raw: Map<String, Double>): Map<String, Double> {
        val total = raw.values.sum().coerceAtLeast(1e-9)
        val clamped = LinkedHashMap<String, Double>()
        for ((k, v) in raw) {
            clamped[k] = (v / total).coerceIn(MIN_WEIGHT, MAX_WEIGHT)
        }
        // Renormalize gently while preserving [MIN_WEIGHT, MAX_WEIGHT]
        val sum = clamped.values.sum().coerceAtLeast(1e-9)
        if (raw.size >= 2) {
            for ((k, v) in clamped.toMap()) {
                clamped[k] = (v / sum).coerceIn(MIN_WEIGHT, MAX_WEIGHT)
            }
        }
        return clamped
    }
}
