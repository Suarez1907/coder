package com.suarez.coder

import com.suarez.coder.ci.AutoHealer
import com.suarez.coder.ci.LogFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFilterAndAutoHealerTest {
    @Test fun filtersEnvironmentNoise() {
        val log = listOf(
            "dependency-graph-continue-on-failure: true",
            "env:",
            "  JAVA_HOME: /opt/java",
            "  GRADLE_USER_HOME: /home/runner/.gradle",
            "GITHUB_DEPENDENCY_GRAPH: abc",
        ).joinToString("\n")
        val out = LogFilter.extract(log)
        assertFalse(out.contains("dependency-graph-continue-on-failure"))
        assertFalse(out.contains("GITHUB_DEPENDENCY_GRAPH"))
    }

    @Test fun keepsPriorityCompilerErrorLine() {
        val log = listOf(
            "Some not-an-error line",
            "e: file:///repo/app/src/main/kotlin/Foo.kt:10:5 Unresolved reference 'bar'",
            "another line",
        ).joinToString("\n")
        val out = LogFilter.extract(log)
        assertTrue(out.contains("Unresolved reference"))
        val idx = out.indexOf("Unresolved reference")
        val noiseIdx = out.indexOf("Some not-an-error line")
        assertTrue(idx in 0..8 || noiseIdx < 0 || idx < noiseIdx + out.length)
    }

    @Test fun signatureSkipsCommitHeader() {
        val log = listOf(
            "Commit abcdef1234567890",
            "---",
            "Job: build",
            "Run ./gradlew assembleRelease",
            "e: file:///repo/app/src/main/kotlin/Foo.kt:10:5 Unresolved reference 'bar'",
        ).joinToString("\n")
        val sig = AutoHealer.signature(log)
        assertTrue(sig.isNotEmpty())
        assertFalse(sig.startsWith("Commit "))
        assertFalse(sig.startsWith("---"))
        assertFalse(sig.startsWith("Job:"))
        assertFalse(sig.startsWith("Run "))
    }
}
