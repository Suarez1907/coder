package com.suarez.coder

import com.suarez.coder.qa.DiffMutationTester
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffMutationTesterTest {

    @Test
    fun generatesAtMostFiveMutantsFromChangedExecutableLinesOnly() {
        val tester = DiffMutationTester()
        val changes = (1..8).map { idx ->
            DiffMutationTester.DiffLineChange(
                path = "app/Logic.kt",
                lineNumber = idx * 10,
                content = "if (count$idx >= 10) return true",
            )
        }
        val mutants = tester.generateDiffMutants(changes, maxMutants = 5)
        assertEquals(5, mutants.size)
        assertTrue(mutants.all { it.path == "app/Logic.kt" })
        assertTrue(mutants.all { it.mutatedLine != it.originalLine })
    }

    @Test
    fun skipsCommentsAndUiColorChanges() = runBlocking {
        val tester = DiffMutationTester()
        val changes = listOf(
            DiffMutationTester.DiffLineChange("app/Theme.kt", 12, "// updated primary color"),
            DiffMutationTester.DiffLineChange("app/Theme.kt", 13, "val Primary = Color(0xFF123456)"),
            DiffMutationTester.DiffLineChange("README.md", 5, "if (a > b) docs"),
        )
        val eval = tester.evaluate(changes) { false }
        assertTrue(eval.skipped)
        assertTrue(eval.allowMerge)
        assertEquals(0, eval.totalMutants)
    }

    @Test
    fun blocksMergeAndLimitsRecoveryToOneAttemptWhenMutantSurvives() = runBlocking {
        val tester = DiffMutationTester()
        val changes = listOf(
            DiffMutationTester.DiffLineChange("app/Validator.kt", 42, "if (score >= 50) return true"),
        )

        val firstAttempt = tester.evaluate(changes, recoveryAttempt = 0) { false }
        assertFalse(firstAttempt.skipped)
        assertFalse(firstAttempt.allowMerge)
        assertTrue(firstAttempt.canRetry)
        assertEquals(1, firstAttempt.survivingMutants.size)
        assertTrue(firstAttempt.recoveryPrompt.contains("app/Validator.kt:42"))
        assertTrue(firstAttempt.recoveryPrompt.contains("->"))

        val secondAttempt = tester.evaluate(changes, recoveryAttempt = 1) { false }
        assertFalse(secondAttempt.allowMerge)
        assertFalse(secondAttempt.canRetry)
    }
}
