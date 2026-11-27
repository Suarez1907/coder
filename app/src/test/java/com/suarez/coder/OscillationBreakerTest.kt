package com.suarez.coder

import com.suarez.coder.harness.OscillationBreaker
import com.suarez.coder.llm.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OscillationBreakerTest {

    private val architect = ModelRef("deepseek", "deepseek-reasoner")

    @Test
    fun testConsecutiveRepeatEscalatesToArchitectOnly() {
        val breaker = OscillationBreaker(architect, capacity = 6)

        val v1 = breaker.recordFailure("hashA", "SyntaxError: line 10", "First attempt")
        assertEquals(OscillationBreaker.Action.CONTINUE, v1.action)

        val v2 = breaker.recordFailure("hashA", "SyntaxError: line 10", "Same attempt repeated")
        assertEquals(OscillationBreaker.Action.ESCALATE_TO_ARCHITECT, v2.action)
        assertEquals(OscillationBreaker.Kind.CONSECUTIVE_REPEAT, v2.kind)
        assertEquals(architect, v2.targetModel)
        assertTrue(v2.internalMessage?.contains("Talimat:") == true)
    }

    @Test
    fun testABAAOscillationEscalatesToArchitect() {
        val breaker = OscillationBreaker(architect, capacity = 6)

        breaker.recordFailure("hashA", "Error 1", "Att 1")
        breaker.recordFailure("hashB", "Error 2", "Att 2")
        val v3 = breaker.recordFailure("hashA", "Error 1", "Att 3")

        assertEquals(OscillationBreaker.Action.ESCALATE_TO_ARCHITECT, v3.action)
        assertEquals(OscillationBreaker.Kind.A_B_A_OSCILLATION, v3.kind)
        assertEquals(architect, v3.targetModel)
    }

    @Test
    fun testSecondEscalationAbortsCleanlyWithoutLooping() {
        val breaker = OscillationBreaker(architect, capacity = 6)

        // 1st cycle -> triggers escalate
        breaker.recordFailure("hashA", "Error 1", "Att 1")
        val vEsc = breaker.recordFailure("hashA", "Error 1", "Att 2")
        assertEquals(OscillationBreaker.Action.ESCALATE_TO_ARCHITECT, vEsc.action)

        // Model changes slightly then repeats
        breaker.recordFailure("hashC", "Error 3", "Att 3")
        val vAbort = breaker.recordFailure("hashC", "Error 3", "Att 4")

        assertEquals(OscillationBreaker.Action.ABORT_CLEANLY, vAbort.action)
        assertNull(vAbort.targetModel)
        assertTrue(vAbort.userNotice?.contains("temiz bir şekilde sonlandırıldı") == true)
    }

    @Test
    fun testRingCapacityMaintained() {
        val breaker = OscillationBreaker(architect, capacity = 6)
        for (i in 1..20) {
            breaker.recordFailure("hash$i", "Error $i", "Att $i")
        }
        assertEquals(0, breaker.escalationsCount)
    }
}
