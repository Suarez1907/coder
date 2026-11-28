package com.suarez.coder.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubJobLogTest {

    @Test
    fun logErrorMessageContainsHttpJobAndStep() {
        val msg = GitHub.logErrorMessage(403, "build (3m12s)", "Unit tests")

        assertTrue(msg.contains("HTTP 403"))
        assertTrue(msg.contains("build (3m12s)"))
        assertTrue(msg.contains("Unit tests"))
    }

    @Test
    fun missingJobAndStepUsePlaceholders() {
        assertEquals("Log alınamadı (HTTP 500) · ? · ?", GitHub.logErrorMessage(500, "", ""))
    }
}