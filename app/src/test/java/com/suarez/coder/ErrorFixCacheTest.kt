package com.suarez.coder

import com.suarez.coder.repair.ErrorFixCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorFixCacheTest {
    @Test
    fun recallsPatchFastAtZeroCostAndDisablesAfterTwoFailures() {
        val cache = ErrorFixCache()
        val err = "e: Sample.kt:12: Unresolved reference 'oldApi'"
        cache.recordGreenFix(err, ErrorFixCache.TemplateKind.API_DEPRECATION, "oldApi()", "newApi()")

        val ok = cache.tryRecallAndApply(err, mapOf("Sample.kt" to "package a\nfun f() = oldApi()"))
        assertTrue(ok.applied)
        assertEquals(0.0, ok.costUsd, 1e-9)
        assertTrue(ok.staged.edit("Sample.kt")!!.after!!.contains("newApi()"))

        // Two failed applications must auto-disable rule
        assertFalse(cache.tryRecallAndApply(err, mapOf("Sample.kt" to "no_match")).applied)
        assertFalse(cache.tryRecallAndApply(err, mapOf("Sample.kt" to "no_match")).applied)
        assertFalse(cache.get(err)!!.enabled)
    }
}
