package com.suarez.coder

import com.suarez.coder.harness.ForensicDiffEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForensicDiffEngineTest {

    @Test
    fun diagnoseExtractsKotlincFileSymbolAndLineRange() {
        val log = """
            > Task :app:compileDebugKotlin FAILED
            e: file:///workspace/app/src/main/java/com/suarez/coder/llm/OpenAiCompatClient.kt:158:21 Unresolved reference 'close'.
            e: file:///workspace/app/src/main/java/com/suarez/coder/orchestrator/SequentialOrchestrator.kt:70:12 Unresolved reference 'AutoHealer'.
        """.trimIndent()

        val report = ForensicDiffEngine.diagnose(log, contextRadius = 2)
        assertEquals(2, report.targets.size)

        val t0 = report.targets[0]
        assertEquals("app/src/main/java/com/suarez/coder/llm/OpenAiCompatClient.kt", t0.file)
        assertEquals("close", t0.symbol)
        assertEquals(156, t0.startLine)
        assertEquals(160, t0.endLine)
        assertEquals(ForensicDiffEngine.ErrorKind.UNRESOLVED_REFERENCE, t0.kind)

        val t1 = report.targets[1]
        assertEquals("AutoHealer", t1.symbol)
        assertTrue(report.healthScore < 1.0)
    }

    @Test
    fun diagnoseExtractsProjectFrameFromStackTrace() {
        val trace = """
            java.lang.IllegalStateException: Stream closed
                at java.io.BufferedReader.ensureOpen(BufferedReader.java:122)
                at com.suarez.coder.llm.OpenAiCompatClient.observeOnce(OpenAiCompatClient.kt:190)
                at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:104)
        """.trimIndent()

        val report = ForensicDiffEngine.diagnose(trace)
        assertTrue(report.hasTargets)
        assertTrue(report.targets.any { it.file == "OpenAiCompatClient.kt" && it.symbol == "observeOnce" })
    }

    @Test
    fun narrowDiffSubsetIsolatesMinimalCulpritHunk() {
        val hunks = (1..12).map { i ->
            ForensicDiffEngine.DiffHunk(
                path = "app/src/main/java/com/suarez/coder/File$i.kt",
                startLine = i * 10,
                endLine = i * 10 + 5,
                before = "old_$i",
                after = if (i == 7) "broken_call()" else "ok_$i",
            )
        }

        val narrowed = ForensicDiffEngine.narrowDiffSubset(hunks) { subset ->
            subset.any { it.after == "broken_call()" }
        }
        assertEquals(1, narrowed.culpritHunks.size)
        assertEquals("app/src/main/java/com/suarez/coder/File7.kt", narrowed.culpritHunks.single().path)

        val feedback = ForensicDiffEngine.buildFocusedFeedback(
            ForensicDiffEngine.diagnose("e: File7.kt:72: Unresolved reference 'broken_call'."),
            narrowed.culpritHunks,
        )
        assertTrue(feedback.contains("File7.kt"))
        assertTrue(feedback.contains("broken_call"))
    }
}
