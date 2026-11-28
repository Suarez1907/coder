package com.suarez.coder

import com.suarez.coder.reflex.ReflexCompiler
import com.suarez.coder.reflex.ReflexExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexCompilerAndExecutorTest {
    @Test
    fun compilesDraftAfterThreeGreenExamplesExecutesAtZeroCostAndRollsBackOnMismatch() {
        val prog = ReflexCompiler.ReflexProgram(
            id = "bump_ver",
            name = "Sürüm Güncelle",
            intentMatchers = listOf("version\s+(\d+\.\d+\.\d+)"),
            requiredParams = listOf("ver"),
            operations = listOf(
                ReflexCompiler.ReflexOp(
                    kind = ReflexCompiler.OpKind.UPDATE_VERSION_FIELD,
                    targetPath = "app/build.gradle.kts",
                    anchorOrPattern = "versionName",
                    payloadTemplate = "{{ver}}",
                ),
            ),
        )
        val baseFile = mapOf("app/build.gradle.kts" to "versionName = \"1.0.1\"\n")
        val examples = listOf("version 1.0.2", "version 1.0.3", "version 1.0.4").map {
            ReflexCompiler.ReplayExample(it, baseFile)
        }
        val compiled = ReflexCompiler.compileAndVerify(prog, examples)
        assertTrue(compiled.accepted)
        assertEquals(ReflexCompiler.Status.DRAFT, compiled.program!!.status)

        val active = compiled.program!!.copy(status = ReflexCompiler.Status.ACTIVE)
        val run = ReflexExecutor.tryExecute("version 2.0.0", listOf(active), baseFile)
        assertTrue(run.succeeded)
        assertEquals(0.0, run.costUsd, 1e-9)
        assertTrue(run.staged.edit("app/build.gradle.kts")!!.after!!.contains("2.0.0"))
        assertTrue(run.statusLine().contains("\$0.0000"))

        val rollback = ReflexExecutor.tryExecute("version 2.0.0", listOf(active), mapOf("app/build.gradle.kts" to "no_field_here"))
        assertTrue(rollback.matched)
        assertFalse(rollback.succeeded)
        assertTrue(rollback.staged.edits().isEmpty())
    }
}
