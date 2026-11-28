package com.suarez.coder

import com.suarez.coder.agent.ReadOnlyMismatchRecovery
import com.suarez.coder.ci.LogFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Temporary plumbing smoke test: a config line that mentions "continue-on-failure" is not a CI error. */
class PlumbingSmokeTest {

    @Test
    fun logFilterKeepsOnlyTheRealErrorLine() {
        val log = listOf(
            "dependencies:",
            "  dependency-graph-continue-on-failure: true",
            "e: file:///App.kt:10:5 Unresolved reference 'foo'",
            "BUILD SUCCESSFUL in 12s",
        ).joinToString("\n")

        val result = LogFilter.extract(log, context = 0)

        assertFalse("Ayar satiri hata sanilmamali", result.contains("dependency-graph-continue-on-failure"))
        assertTrue("Kotlin derleyici hatasi korunmali", result.contains("e: file:///App.kt:10:5 Unresolved reference 'foo'"))
    }

    @Test
    fun writeIntentDetection() {
        assertTrue(ReadOnlyMismatchRecovery.hasWriteIntent("Uygula ve push et"))
        assertFalse(ReadOnlyMismatchRecovery.hasWriteIntent("sadece incele, repoyu değiştirme"))
    }
}
