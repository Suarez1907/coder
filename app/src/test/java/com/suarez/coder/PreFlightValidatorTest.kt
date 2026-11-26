package com.suarez.coder

import com.suarez.coder.harness.PreFlightValidator
import com.suarez.coder.harness.StagedTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreFlightValidatorTest {

    @Test
    fun testNoFalsePositivesInStringsAndComments() {
        val validKotlinCode = """
            package com.example

            // Comment with unmatched brackets: { [ (
            /* Nested comment /* with { brackets */ here */
            fun sample() {
                val s1 = "if (a) { "
                val s2 = "{ unmatched in string }"
                val raw = ${"\"\"\""}
                    fun inner() {
                        [ ( unclosed
                    }
                ${"\"\"\""}
                val ch = '{'
                println(s1 + s2 + raw + ch)
            }
        """.trimIndent()

        val res = PreFlightValidator.validate(
            listOf(PreFlightValidator.StagedFile("Foo.kt", null, validKotlinCode))
        )
        assertTrue(res.report(), res.ok)
        assertEquals(0, res.issues.size)
    }

    @Test
    fun testDetectsUnbalancedBrackets() {
        val broken = "fun broken() { val a = 1"
        val res = PreFlightValidator.validate(
            listOf(PreFlightValidator.StagedFile("Broken.kt", null, broken))
        )
        assertFalse(res.ok)
        assertTrue(res.issues.any { it.reason.contains("Kapatılmamış parantez") })
    }

    @Test
    fun testDetectsBrokenJson() {
        val brokenJson = "{\n  \"key\": \"value\",\n}" // trailing comma
        val res = PreFlightValidator.validate(
            listOf(PreFlightValidator.StagedFile("data.json", null, brokenJson))
        )
        assertFalse(res.ok)
        assertTrue(res.issues.any { it.path == "data.json" })
    }

    @Test
    fun testDetectsYamlTabIndentation() {
        val brokenYaml = "foo:\n\tbar: 1"
        val res = PreFlightValidator.validate(
            listOf(PreFlightValidator.StagedFile("config.yml", null, brokenYaml))
        )
        assertFalse(res.ok)
        assertTrue(res.issues.any { it.reason.contains("tab ('\\t')") })
    }

    @Test
    fun testDetectsMissingWorkflowJobs() {
        val workflowMissingJobs = "name: CI\non:\n  push:\n    branches: [main]\n"
        val res = PreFlightValidator.validate(
            listOf(PreFlightValidator.StagedFile(".github/workflows/ci.yml", null, workflowMissingJobs))
        )
        assertFalse(res.ok)
        assertTrue(res.issues.any { it.reason.contains("kök 'jobs:' anahtarı eksik") })
    }

    @Test
    fun testDetectsBrokenSkillFrontmatter() {
        val brokenSkill = "---\ndescription: sample without name\n---\nBody"
        val res = PreFlightValidator.validate(
            listOf(PreFlightValidator.StagedFile("skills/my/SKILL.md", null, brokenSkill))
        )
        assertFalse(res.ok)
        assertTrue(res.issues.any { it.reason.contains("name:") })
    }

    @Test
    fun testDetectsUnbalancedXml() {
        val brokenXml = "<root><item>Hello</other></root>"
        val res = PreFlightValidator.validate(
            listOf(PreFlightValidator.StagedFile("values.xml", null, brokenXml))
        )
        assertFalse(res.ok)
        assertTrue(res.issues.any { it.reason.contains("eşleşmeyen kapanış etiketi") })
    }

    @Test
    fun testDetectsDuplicateKotlinImport() {
        val code = "package foo\nimport bar.Baz\nimport bar.Baz\nfun run() {}"
        val res = PreFlightValidator.validate(
            listOf(PreFlightValidator.StagedFile("Foo.kt", null, code))
        )
        assertFalse(res.ok)
        assertTrue(res.issues.any { it.reason.contains("Çifte import") })
    }

    @Test
    fun testAccidentalWipeProtectionAndStagedTransactionRollback() = runBlocking {
        val beforeContent = (1..60).joinToString("\n") { "line $it" }
        val afterContent = "only two lines\nhere"

        val transaction = StagedTransaction()
        val outcome = transaction.stageWrites(
            listOf(StagedTransaction.WriteItem("BigFile.kt", afterContent, delete = false))
        ) { path -> if (path == "BigFile.kt") beforeContent else null }

        assertTrue(outcome is StagedTransaction.Outcome.PreFlightBlocked)
        val blocked = outcome as StagedTransaction.Outcome.PreFlightBlocked
        assertTrue(blocked.formattedReport.contains("Güvenlik engeli"))
    }
}
