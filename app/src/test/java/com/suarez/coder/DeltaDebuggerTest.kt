package com.suarez.coder

import com.suarez.coder.debug.DeltaDebugger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaDebuggerTest {
    @Test
    fun isolatesSingleCulpritHunkWithinSixteenProbesSkippingUnresolved() = runBlocking {
        val hunks = (1..12).map { "hunk_$it" }
        val res = DeltaDebugger.minimize(hunks, maxProbes = 16) { subset ->
            when {
                "hunk_9" in subset -> DeltaDebugger.OracleResult.FAIL_SAME_SIGNATURE
                subset == listOf("hunk_1", "hunk_2", "hunk_3") -> DeltaDebugger.OracleResult.UNRESOLVED_COMPILE_ERROR
                else -> DeltaDebugger.OracleResult.PASS
            }
        }
        assertEquals(listOf("hunk_9"), res.minimalItems)
        assertTrue("Probes (${res.probesUsed}) must be <= 16", res.probesUsed <= 16)
    }
}
