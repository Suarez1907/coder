package com.suarez.coder

import com.suarez.coder.repair.CompilerQuickFixer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilerQuickFixerTest {
    @Test
    fun fixesUniqueImportOptInValReassignAndYamlTabsAtZeroCost() {
        val kt = "package demo\nfun a() {\n    val count = 1\n    count = 2\n    AutoHealer.signature(\"x\")\n}"
        val yml = "jobs:\n\tbuild:\n\t\truns-on: ubuntu-latest"
        val log = """
            e: Demo.kt:4:5 Val cannot be reassigned
            e: Demo.kt:5:5 Unresolved reference 'AutoHealer'
            e: Demo.kt:2:1 This declaration needs opt-in: ExperimentalMaterial3Api
        """.trimIndent()
        val res = CompilerQuickFixer.tryFix(
            log = log,
            files = mapOf("Demo.kt" to kt, "ci.yml" to yml),
            symbolPackages = mapOf("AutoHealer" to listOf("com.suarez.coder.ci.AutoHealer")),
        )
        assertTrue(res.fixed)
        assertEquals(0.0, res.costUsd, 1e-9)
        val fixedKt = res.staged.edit("Demo.kt")!!.after!!
        assertTrue(fixedKt.contains("import com.suarez.coder.ci.AutoHealer"))
        assertTrue(fixedKt.contains("var count = 1"))
        assertTrue(fixedKt.contains("@OptIn(ExperimentalMaterial3Api::class)"))
        assertFalse(res.staged.edit("ci.yml")!!.after!!.contains("\t"))

        // Ambiguous import (2 candidates) must NOT guess
        val ambig = CompilerQuickFixer.tryFix(
            log = "e: X.kt:2:1 Unresolved reference 'Node'",
            files = mapOf("X.kt" to "package x\nval n = Node()"),
            symbolPackages = mapOf("Node" to listOf("a.Node", "b.Node")),
        )
        assertFalse(ambig.fixed)
    }
}
