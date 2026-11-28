package com.suarez.coder

import com.suarez.coder.cost.ZeroCostWaterfallRouter
import org.junit.Assert.assertEquals
import org.junit.Test

class ZeroCostWaterfallRouterTest {
    @Test
    fun coolsDown429MovesToNextBucketEscalatesOnQualityFailAndKeepsArchitectLocked() {
        var now = 10_000L
        val router = ZeroCostWaterfallRouter { now }
        val buckets = listOf(
            ZeroCostWaterfallRouter.EndpointBucket("free1", "qwen:free", ZeroCostWaterfallRouter.BucketTier.CLOUD_FREE_QUOTA, costToGreenScore = 0.9),
            ZeroCostWaterfallRouter.EndpointBucket("free2", "gemini-flash-free", ZeroCostWaterfallRouter.BucketTier.CLOUD_FREE_QUOTA, costToGreenScore = 0.8),
            ZeroCostWaterfallRouter.EndpointBucket("paid1", "deepseek-chat", ZeroCostWaterfallRouter.BucketTier.PAID_IMPLEMENTER, costToGreenScore = 0.95),
        )
        val first = router.selectNext(ZeroCostWaterfallRouter.TaskKind.SIMPLE_CODE, true, "locked/arch", buckets)
        assertEquals("free1", first.bucket!!.id)
        assertEquals("locked/arch", first.lockedArchitectModelId)

        router.record429("free1", retryAfterSeconds = 60)
        val second = router.selectNext(ZeroCostWaterfallRouter.TaskKind.SIMPLE_CODE, true, "locked/arch", buckets)
        assertEquals("free2", second.bucket!!.id)

        val escalated = router.selectNext(ZeroCostWaterfallRouter.TaskKind.SIMPLE_CODE, true, "locked/arch", buckets, freeQualityFailed = true)
        assertEquals("paid1", escalated.bucket!!.id)
        assertEquals("locked/arch", escalated.lockedArchitectModelId)
    }
}
