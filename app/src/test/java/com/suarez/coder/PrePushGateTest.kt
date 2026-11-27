package com.suarez.coder

import com.suarez.coder.decision.DecisionAnswer
import com.suarez.coder.decision.DecisionResult
import com.suarez.coder.decision.PrePushGate
import com.suarez.coder.harness.PreFlightValidator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrePushGateTest {
    private fun staged(path: String, content: String = "class X") =
        PreFlightValidator.StagedFile(path = path, before = null, after = content)

    private fun choiceResult(choice: String, inputTokens: Long = 100L, model: String? = "gemini-decision-1") =
        DecisionResult(
            answers = mapOf("pre_push_gate" to DecisionAnswer(type = "choice", choice = choice, confidence = 1.0)),
            inputTokens = inputTokens,
            latencyMs = 10L,
            model = model,
        )

    @Test
    fun belowThresholdDoesNotTriggerDecide() = runBlocking {
        val gate = PrePushGate()
        var called = false
        val files = listOf(staged("app/src/main/java/A.kt"), staged("app/src/main/java/B.kt"))
        val res = gate.check("Small fix.", files, addedLines = 20, removedLines = 20) { _, _ ->
            called = true
            DecisionResult(emptyMap(), 0L, 0L)
        }
        assertFalse(called)
        assertFalse(res.triggered)
        assertEquals(PrePushGate.Verdict.APPROVE, res.verdict)
    }

    @Test
    fun triggersOnThreeOrMoreFilesAndCapsInputLength() = runBlocking {
        val gate = PrePushGate()
        var capturedInput = ""
        val files = (1..100).map { staged("app/src/main/java/com/suarez/coder/VeryLongFileNameNumber$it.kt") }
        val longTask = "First sentence. Second sentence. " + "x".repeat(3000)
        val res = gate.check(longTask, files, addedLines = 10, removedLines = 5) { input, _ ->
            capturedInput = input
            choiceResult("APPROVE")
        }
        assertTrue(res.triggered)
        assertTrue(capturedInput.length <= PrePushGate.MAX_INPUT_CHARS)
        assertEquals(PrePushGate.Verdict.APPROVE, res.verdict)
    }

    @Test
    fun triggersOnLineCountThreshold() = runBlocking {
        val gate = PrePushGate()
        var called = false
        val files = listOf(staged("app/src/main/java/A.kt"))
        val res = gate.check("Refactor.", files, addedLines = 80, removedLines = 40) { _, _ ->
            called = true
            choiceResult("APPROVE")
        }
        assertTrue(called)
        assertTrue(res.triggered)
    }

    @Test
    fun triggersOnCriticalPaths() = runBlocking {
        val criticalPaths = listOf(
            "build.gradle.kts",
            ".github/workflows/build.yml",
            "app/src/main/AndroidManifest.xml",
            "app/src/main/java/com/suarez/coder/harness/X.kt",
        )
        for (path in criticalPaths) {
            val gate = PrePushGate()
            var called = false
            val res = gate.check("Update critical file.", listOf(staged(path)), 1, 0) { _, _ ->
                called = true
                choiceResult("APPROVE")
            }
            assertTrue("Expected trigger for $path", called)
            assertTrue("Expected triggered=true for $path", res.triggered)
        }
    }

    @Test
    fun warnsAndRequiresArchitectAtMostOncePerTask() = runBlocking {
        val gate = PrePushGate()
        val files = listOf(staged("a.kt"), staged("b.kt"), staged("c.kt"))

        val w1 = gate.check("Task.", files, 10, 0) { _, _ ->
            choiceResult("WARN_SCOPE_DRIFT")
        }
        val w2 = gate.check("Task.", files, 10, 0) { _, _ ->
            choiceResult("WARN_SCOPE_DRIFT")
        }
        assertEquals(PrePushGate.Verdict.WARN_SCOPE_DRIFT, w1.verdict)
        assertEquals(PrePushGate.Verdict.APPROVE, w2.verdict)

        val r1 = gate.check("Task.", files, 10, 0) { _, _ ->
            choiceResult("REQUIRE_ARCHITECT_REVIEW")
        }
        val r2 = gate.check("Task.", files, 10, 0) { _, _ ->
            choiceResult("REQUIRE_ARCHITECT_REVIEW")
        }
        assertEquals(PrePushGate.Verdict.REQUIRE_ARCHITECT_REVIEW, r1.verdict)
        assertEquals(PrePushGate.Verdict.APPROVE, r2.verdict)
    }

    @Test
    fun failsOpenOnNetworkErrorAndTimeout() = runBlocking {
        val gate = PrePushGate()
        val files = listOf(staged("a.kt"), staged("b.kt"), staged("c.kt"))

        val errRes = gate.check("Task.", files, 10, 0) { _, _ ->
            throw RuntimeException("network down")
        }
        assertTrue(errRes.triggered)
        assertTrue(errRes.timedOutOrFailed)
        assertEquals(PrePushGate.Verdict.APPROVE, errRes.verdict)

        val timeoutRes = gate.check("Task.", files, 10, 0) { _, _ ->
            delay(5_000L)
            choiceResult("WARN_SCOPE_DRIFT")
        }
        assertTrue(timeoutRes.triggered)
        assertTrue(timeoutRes.timedOutOrFailed)
        assertEquals(PrePushGate.Verdict.APPROVE, timeoutRes.verdict)
    }
}
