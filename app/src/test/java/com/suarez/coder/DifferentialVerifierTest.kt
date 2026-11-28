package com.suarez.coder

import com.suarez.coder.validate.DifferentialVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DifferentialVerifierTest {

    @Test
    fun detectsBrokenImportPackageAndSignatureChanges() {
        val before = """
            package com.suarez.coder.demo
            import com.suarez.coder.ci.AutoHealer
            class Demo {
                fun analyze(log: String): String = AutoHealer.signature(log)
            }
        """.trimIndent()

        val after = """
            package com.suarez.coder.wrong
            class Demo {
                fun analyze(log: String, strict: Boolean): String = AutoHealer.signature(log)!!
            }
        """.trimIndent()

        val report = DifferentialVerifier.verify("Demo.kt", before, after)
        assertTrue(report.hasBlocking)
        assertTrue(report.issues.any { it.kind == DifferentialVerifier.IssueKind.PACKAGE_MISMATCH })
        assertTrue(report.issues.any { it.kind == DifferentialVerifier.IssueKind.BROKEN_IMPORT_CONTRACT })
        assertTrue(report.issues.any { it.kind == DifferentialVerifier.IssueKind.CHANGED_SIGNATURE_ARITY })
        assertTrue(report.issues.any { it.kind == DifferentialVerifier.IssueKind.MISSING_ERROR_OR_NULL_GUARD })
        assertTrue(report.score < 0.5)
    }

    @Test
    fun detectsUnbalancedBracesAsBlockingConstraintViolation() {
        val before = "package a\nclass X { fun f() = 1 }"
        val after = "package a\nclass X { fun f() { return 1 }"
        val report = DifferentialVerifier.verify("X.kt", before, after)
        assertTrue(report.hasBlocking)
        assertTrue(report.issues.any { it.kind == DifferentialVerifier.IssueKind.CONSTRAINT_VIOLATION })
    }

    @Test
    fun skeletonizeShrinksLargeFileToSignaturesOnly() {
        val bigBody = (1..40).joinToString("\n") { "        val v$it = input * $it" }
        val src = """
            package com.suarez.coder.demo
            import org.json.JSONObject
            class Calculator {
                fun compute(input: Int): Int {
            $bigBody
                    return input
                }
            }
        """.trimIndent()

        val view = DifferentialVerifier.skeletonize("Calculator.kt", src)
        assertTrue(view.skeleton.contains("class Calculator"))
        assertTrue(view.skeleton.contains("fun compute(input: Int): Int"))
        assertFalse(view.skeleton.contains("val v20 = input * 20"))
        assertTrue(view.reductionPercent > 50)
    }
}
