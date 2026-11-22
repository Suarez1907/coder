package com.suarez.coder.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Error lines from the toolchains Coder builds with (and Blender/Python tracebacks from cloud tools) must be kept. */
class LogFilterCoverageTest {
    private fun kept(line: String) = !LogFilter.extract("ilk satır\n$line\nson satır").startsWith("(Belirgin")

    @Test fun commonErrorLinesAreKept() {
        for (l in listOf(
            "src/Main.java:12: error: cannot find symbol",
            "error[E0308]: mismatched types",
            "com.x.FooTest > adds FAILED",
            "    java.lang.AssertionError: expected:<1> but was:<2>",
            "Error: Cannot find module 'react'",
            "ValueError: invalid literal for int()",
            "##[error]Process completed with exit code 1.",
            "npm ERR! code ELIFECYCLE",
            "FAILED tests/test_x.py::test_a - assert 1 == 2",
            "Execution failed for task ':app:compileDebugKotlin'.",
            "  File \"script.py\", line 3, in <module>",
            "RuntimeError: Operator bpy.ops.view3d.snap failed, context is incorrect",
            "ERROR: Could not find a version that satisfies the requirement foo",
            "Test failed: expected true",
            "> Task :app:testDebugUnitTest FAILED",
            "AttributeError: 'NoneType' object has no attribute 'data'",
            "fatal: not a git repository",
            "e: /path/File.kt: Unresolved reference: foo",
        )) assertTrue(l, kept(l))
    }

    @Test fun blenderTracebackKeepsTheFinalErrorLine() {
        val log = (listOf("Blender 4.2.3", "Read prefs", "Traceback (most recent call last):") +
            (1..8).map { "  File \"/x/lib$it.py\", line $it, in f$it" } +
            listOf("    bpy.ops.view3d.snap_cursor_to_center()", "RuntimeError: Operator bpy.ops.view3d.snap_cursor_to_center.poll() failed, context is incorrect",
                "Blender quit")).joinToString("\n")
        assertTrue(LogFilter.extract(log).contains("RuntimeError: Operator bpy.ops.view3d.snap_cursor_to_center.poll() failed"))
    }

    @Test fun ordinaryLinesAndLicenseTextAreNotErrors() {
        assertFalse(kept("BUILD SUCCESSFUL in 32s"))
        assertFalse(kept("Downloading https://services.gradle.org/distributions/gradle-8.14.3-bin.zip"))
        assertFalse(kept(" * ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF"))
        assertFalse(kept(" * IS NOT WARRANTED. EXCEPTION TO LIABILITY LIMITATIONS APPLY."))
    }
}
