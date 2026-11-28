package com.suarez.coder

import com.suarez.coder.llm.OutputDietController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputDietControllerTest {
    @Test
    fun scalesThinkingAndMaxTokensByDifficultyAndExpandsOnceOnLengthTruncation() {
        val simple = OutputDietController.plan(OutputDietController.Difficulty.SIMPLE, targetLines = 10)
        val complex = OutputDietController.plan(OutputDietController.Difficulty.COMPLEX_OR_ARCHITECT, targetLines = 120)
        assertTrue(simple.thinkingBudgetTokens < complex.thinkingBudgetTokens)
        assertEquals(624, simple.maxOutputTokens)
        assertEquals(3264, complex.maxOutputTokens)

        assertEquals(1248, OutputDietController.expandOnLengthTruncation(simple.maxOutputTokens, truncated = true, alreadyExpanded = false))
        assertNull(OutputDietController.expandOnLengthTruncation(simple.maxOutputTokens, truncated = true, alreadyExpanded = true))
        assertNull(OutputDietController.expandOnLengthTruncation(simple.maxOutputTokens, truncated = false, alreadyExpanded = false))
    }
}
