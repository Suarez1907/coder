package com.suarez.coder.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreFlightValidatorTest {
    private fun rules(path: String, before: String, after: String) =
        PreFlightValidator.check(path, before, after).map { it.rule }.toSet()

    @Test fun bracesInsideStringsCommentsAndRawStringsAreFine() {
        val before = "fun a() = 1\n"
        val after = """
            fun a(s: String): String {
                // comment { } ( )
                /* block { } */
                val raw = ${"\"\"\""} still { fine } ${"\"\"\""}
                val t = "literal { } ( )"
                return t + raw
            }
        """.trimIndent()
        assertTrue(rules("Ui.kt", before, after).isEmpty())
        assertFalse(PreFlightValidator.unbalanced(after, "kt"))
    }

    @Test fun realUnbalancedParensAndAStrayCloseAreFindings() {
        assertEquals(setOf("unbalanced_brackets"), rules("A.kt", "fun a() = 1\n", "fun a() {\n    if (true {\n        return\n    }\n}\n"))
        assertTrue(PreFlightValidator.unbalanced("fun a() { return }\n}", "kt"))
    }

    @Test fun jsonAndYamlRules() {
        assertEquals(setOf("broken_json"), rules("a.json", "{ \"a\": 1 }\n", "{ \"a\": }\n"))
        assertTrue(rules("a.json", "{ \"a\": 1 }\n", "{ \"a\": \"{ }\" }\n").isEmpty())
        assertTrue(PreFlightValidator.validJson("[1, 2, {\"k\": \"{ }\"}]\n".trim()))
        assertEquals(setOf("yaml_tab"), rules("ci.yml", "name: x\n", "name: x\njobs:\n\tbuild: true\n"))
        assertTrue(rules("ci.yml", "name: x\n", "name: x\njobs:\n  build: true\n").isEmpty())
        assertTrue(PreFlightValidator.hasYamlTab("\tkey: 1\n"))
        assertFalse(PreFlightValidator.hasYamlTab("  key: 1\n"))
    }

    @Test fun conflictMarkersAndAccidentalDelete() {
        val long = "fun kept(): String {\n    return \"${"x".repeat(80)}\"\n}\n"
        assertEquals(setOf("merge_conflict"), rules("A.kt", "fun a() = 1\n", "fun a() = 1\n<<<<<<< HEAD\nx\n=======\ny\n>>>>>>> other\n"))
        assertEquals(setOf("accidental_delete"), rules("Big.kt", long, "\n"))
        assertTrue(rules("Tiny.kt", "fun a() = 1\n", "").isEmpty())
        assertFalse(PreFlightValidator.isAccidentalDelete("short", ""))
        assertTrue(PreFlightValidator.isAccidentalDelete(long, "  "))
    }

    @Test fun pythonHashCommentDoesNotHideARealParen() {
        val src = "def a():\n    # { ignore }\n    return (1\n"
        assertTrue(PreFlightValidator.unbalanced(src, "py"))
        val ok = "def a():\n    # { ignore }\n    return (1)\n"
        assertFalse(PreFlightValidator.unbalanced(ok, "py"))
    }
}
