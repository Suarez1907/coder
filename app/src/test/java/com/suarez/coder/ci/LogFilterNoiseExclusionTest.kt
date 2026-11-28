package com.suarez.coder.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Installation and framework noise must not be reported as the real CI failure. */
class LogFilterNoiseExclusionTest {

    @Test
    fun toolchainNoiseDoesNotBecomeTheOnlyError() {
        val log = """
            Picked up JAVA_TOOL_OPTIONS: -Xmx2g
            warning: [options] bootstrap class path not set in conjunction with -source 17
            2024-01-01T00:00:00Z > Task :app:compileDebugKotlin FAILED
            2024-01-01T00:00:01Z e: /path/File.kt:12:5 Unresolved reference: foo
        """.trimIndent()

        val result = LogFilter.extract(log)

        assertTrue(result.contains("Unresolved reference: foo"))
        assertTrue(result.contains("compileDebugKotlin FAILED"))
        assertFalse(result.contains("Picked up JAVA_TOOL_OPTIONS"))
        assertFalse(result.contains("bootstrap class path not set"))
    }

    @Test
    fun stackTraceFramesAreExcludedButCauseIsKept() {
        val log = """
            java.lang.RuntimeException: boom
                at java.base/java.lang.Thread.run(Thread.java:829)
                at org.gradle.internal.Foo.bar(Foo.kt:12)
            Caused by: IllegalStateException: bad state
        """.trimIndent()

        val result = LogFilter.extract(log)

        assertTrue(result.contains("RuntimeException: boom"))
        assertTrue(result.contains("IllegalStateException: bad state"))
        assertFalse(result.contains("Thread.java:829"))
    }

    @Test
    fun noiseOnlyLogFallsBackToTailWithoutClaimingRealError() {
        val log = """
            Picked up JAVA_TOOL_OPTIONS: -Xmx2g
            DeprecationWarning: legacy API
        """.trimIndent()

        val result = LogFilter.extract(log)

        assertTrue(result.contains("(Belirgin hata satırı bulunamadı"))
    }
}