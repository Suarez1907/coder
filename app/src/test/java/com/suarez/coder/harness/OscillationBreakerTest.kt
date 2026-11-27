package com.suarez.coder.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OscillationBreakerTest {
    @Test fun abaOnTheSameFileEscalates() {
        val b = OscillationBreaker()
        assertFalse(b.observe("ui/A.kt", "fun a() = 1\n").oscillated)
        assertFalse(b.observe("ui/A.kt", "fun a() = 2\n").oscillated)
        val back = b.observe("ui/A.kt", "fun a() = 1\n")
        assertTrue(back.oscillated)
        assertTrue(back.escalate)
        assertEquals(1, b.escalations)
    }

    @Test fun aDifferentFileDoesNotCountAndAStraightEditDoesNotEscalate() {
        val b = OscillationBreaker()
        b.observe("A.kt", "one")
        b.observe("B.kt", "one")
        b.observe("A.kt", "two")
        val third = b.observe("A.kt", "three")
        assertFalse(third.oscillated)
        assertEquals(0, b.escalations)
        assertFalse(b.observe("B.kt", "two").escalate)
    }

    @Test fun thresholdTwoNeedsASecondLoop() {
        val b = OscillationBreaker(threshold = 2)
        b.observe("A.kt", "a")
        b.observe("A.kt", "b")
        val first = b.observe("A.kt", "a")
        assertTrue(first.oscillated)
        assertFalse(first.escalate)
        val second = b.observe("A.kt", "b")
        assertTrue(second.oscillated)
        assertTrue(second.escalate)
        assertTrue(b.escalations >= 2)
    }

    @Test fun repeatingTheSameBodyIsNotALoop() {
        val b = OscillationBreaker()
        b.observe("A.kt", "same")
        b.observe("A.kt", "same")
        assertEquals(0, b.observe("A.kt", "same").escalations)
    }
}
