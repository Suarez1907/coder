package com.suarez.coder

import com.suarez.coder.formal.FsmModelChecker
import com.suarez.coder.repair.SelfHealingLadder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfHealingLadderTest {
    @Test
    fun resolvesAtTier0WithZeroLlmCallsAndEscalatesToLockedArchitectWhenNeeded() = runBlocking {
        val ladder0 = SelfHealingLadder()
        val r0 = ladder0.heal(
            errorLog = "e: Demo.kt:2:5 Unresolved reference 'AutoHealer'",
            files = mapOf("Demo.kt" to "package d\nfun f() = AutoHealer.signature(\"a\")"),
            lockedArchitectModelId = "locked/arch",
            symbolPackages = mapOf("AutoHealer" to listOf("com.suarez.coder.ci.AutoHealer")),
        )
        assertTrue(r0.resolved)
        assertEquals(SelfHealingLadder.Tier.TIER_0_DETERMINISTIC, r0.tier)
        assertEquals(0, r0.llmCallsMade)
        assertEquals(0.0, r0.costUsd, 1e-9)

        val ladder3 = SelfHealingLadder(
            tier1FreeRepair = { _, _ -> null },
            tier2PaidImplementer = { _, _ -> null to 0.002 },
            tier3LockedArchitect = { archId, _, files ->
                assertEquals("locked/arch", archId)
                mapOf("Demo.kt" to "package d\nfun f() = \"fixed\"") to 0.01
            },
        )
        val r3 = ladder3.heal(
            errorLog = "Semantic mismatch",
            files = mapOf("Demo.kt" to "package d\nfun f() = \"broken\""),
            lockedArchitectModelId = "locked/arch",
        )
        assertTrue(r3.resolved)
        assertEquals(SelfHealingLadder.Tier.TIER_3_LOCKED_ARCHITECT, r3.tier)
        assertEquals("locked/arch", r3.lockedArchitectModelId)
        assertTrue(FsmModelChecker.verifyAll().verified)
    }
}
