package com.suarez.coder.cost

/**
 * Faz 9 — Sıfır-Maliyet Kota & Şelale Yönlendirici (`ZeroCostWaterfallRouter`).
 *
 * Kırmızı Çizgi: Şelale yönlendirme **yalnızca Uygulayıcı (Implementer) ve yardımcı/triyaj işleri**
 * içindir; kullanıcının kilitli **Mimar (Architect)** modeli asla değiştirilemez.
 *
 * - Her uç nokta için yerel `RPM / RPD` sayaçları ve `429 Retry-After` soğutma zamanlayıcıları tutar.
 * - Ücretsiz kota `429` aldığında soğutmaya alınıp anında sıradaki kovaya geçer.
 * - Ücretsiz çıktı `PreFlight` / `ProofGate` geçemezse anında en verimli ücretli Uygulayıcıya yükselir.
 */
class ZeroCostWaterfallRouter(
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {

    enum class TaskKind { AUXILIARY, SIMPLE_CODE, MODERATE_CODE, HIGH_RISK_COMPLEX }

    enum class BucketTier { LOCAL_EDGE_FREE, CLOUD_FREE_QUOTA, PAID_IMPLEMENTER }

    data class EndpointBucket(
        val id: String,
        val modelId: String,
        val tier: BucketTier,
        val rpmLimit: Int = 15,
        val rpdLimit: Int = 500,
        val costToGreenScore: Double = 0.80,
    )

    data class RouteChoice(
        val bucket: EndpointBucket?,
        val lockedArchitectModelId: String,
        val reason: String,
    )

    private class QuotaState(
        var minuteWindowStartMs: Long = 0L,
        var reqsInMinute: Int = 0,
        var dayWindowStartMs: Long = 0L,
        var reqsInDay: Int = 0,
        var cooldownUntilMs: Long = 0L,
    )

    private val states = HashMap<String, QuotaState>()

    @Synchronized
    fun record429(bucketId: String, retryAfterSeconds: Long = 60L) {
        val now = nowProvider()
        val st = states.getOrPut(bucketId) { QuotaState(now, 0, now, 0, 0L) }
        st.cooldownUntilMs = now + (retryAfterSeconds.coerceIn(5L, 600L) * 1000L)
    }

    @Synchronized
    fun recordRequest(bucketId: String) {
        val now = nowProvider()
        val st = states.getOrPut(bucketId) { QuotaState(now, 0, now, 0, 0L) }
        if (now - st.minuteWindowStartMs >= 60_000L) {
            st.minuteWindowStartMs = now
            st.reqsInMinute = 0
        }
        if (now - st.dayWindowStartMs >= 24 * 3_600_000L) {
            st.dayWindowStartMs = now
            st.reqsInDay = 0
        }
        st.reqsInMinute++
        st.reqsInDay++
    }

    @Synchronized
    fun isAvailable(bucket: EndpointBucket): Boolean {
        val now = nowProvider()
        val st = states[bucket.id] ?: return true
        if (now < st.cooldownUntilMs) return false
        val rpmUsed = if (now - st.minuteWindowStartMs >= 60_000L) 0 else st.reqsInMinute
        val rpdUsed = if (now - st.dayWindowStartMs >= 24 * 3_600_000L) 0 else st.reqsInDay
        return rpmUsed < bucket.rpmLimit && rpdUsed < bucket.rpdLimit
    }

    /**
     * Picks the next available Implementer/auxiliary bucket in waterfall order.
     * [lockedArchitectModelId] is echoed untouched.
     */
    @Synchronized
    fun selectNext(
        taskKind: TaskKind,
        waterfallEnabled: Boolean,
        lockedArchitectModelId: String,
        buckets: List<EndpointBucket>,
        freeQualityFailed: Boolean = false,
    ): RouteChoice {
        val available = buckets.filter { isAvailable(it) }
        if (available.isEmpty()) {
            return RouteChoice(null, lockedArchitectModelId, "Kullanılabilir kova kalmadı.")
        }

        // High-risk/complex or failed free quality gate -> go straight to best paid Implementer
        if (taskKind == TaskKind.HIGH_RISK_COMPLEX || freeQualityFailed || (!waterfallEnabled && taskKind != TaskKind.AUXILIARY)) {
            val paid = available.filter { it.tier == BucketTier.PAID_IMPLEMENTER }
                .maxByOrNull { it.costToGreenScore }
                ?: available.maxByOrNull { it.costToGreenScore }
            return RouteChoice(paid, lockedArchitectModelId, if (freeQualityFailed) "Ücretsiz çıktı kalite kapısını geçemedi → ücretli Uygulayıcı." else "Doğrudan verimli Uygulayıcı seçildi.")
        }

        // Waterfall order: LOCAL_EDGE_FREE -> CLOUD_FREE_QUOTA (by CTG) -> PAID_IMPLEMENTER (by CTG)
        val tierOrder = listOf(BucketTier.LOCAL_EDGE_FREE, BucketTier.CLOUD_FREE_QUOTA, BucketTier.PAID_IMPLEMENTER)
        for (tier in tierOrder) {
            val best = available.filter { it.tier == tier }.maxByOrNull { it.costToGreenScore }
            if (best != null) {
                return RouteChoice(best, lockedArchitectModelId, "Şelale seçimi: ${tier.name} (${best.modelId})")
            }
        }
        return RouteChoice(available.first(), lockedArchitectModelId, "Varsayılan kova seçildi.")
    }
}
