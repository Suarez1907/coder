package com.suarez.coder

import com.suarez.coder.context.RepoDependencyGraph
import com.suarez.coder.harness.PreFlightValidator
import com.suarez.coder.harness.StagedTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoDependencyGraphTest {
    private val lib = """
        package demo
        fun add(a: Int, b: Int): Int = a + b
    """.trimIndent()

    private val app = """
        package app
        import demo.add
        fun main() {
            add(1, 2)
        }
    """.trimIndent()

    @Test fun importAndCallMapAndShaCache() {
        val g = RepoDependencyGraph()
        assertTrue(g.update("lib.kt", "sha-1", lib))
        assertTrue(g.update("app.kt", "sha-a", app))
        assertFalse(g.update("lib.kt", "sha-1", "fun gone() = 0\n"))
        assertEquals(2, g.symbol("lib.kt", "add")!!.requiredParams)
        val hits = g.impact("lib.kt", "add")
        assertTrue(hits.any { it.path == "app.kt" && it.line == 2 && it.kind == "import" })
        assertTrue(hits.any { it.path == "app.kt" && it.line == 4 && it.kind == "call" })
        assertTrue(g.update("m.py", "p", "class Box:\n    def ping(self, a, b=1):\n        return a\n"))
        assertEquals(1, g.symbol("m.py", "ping")!!.requiredParams)
        assertTrue(g.update("p.gd", "g", "class_name Player\nfunc hit(a, b=1):\n    pass\n"))
        assertEquals(1, g.symbol("p.gd", "hit")!!.requiredParams)
        assertTrue(g.symbol("p.gd", "Player") != null)
    }

    @Test fun staleCallerBlocksPushAtZeroCostUntilItIsUpdated() {
        val g = RepoDependencyGraph()
        g.update("lib.kt", "sha-1", lib)
        g.update("app.kt", "sha-a", app)
        val newLib = """
            package demo
            fun add(a: Int, b: Int, c: Int): Int = a + b + c
        """.trimIndent()
        val tx = StagedTransaction()
        tx.stage("lib.kt", lib, newLib)
        val blocked = PreFlightValidator.signatureGate(g, tx)
        assertTrue(blocked.blocked)
        assertEquals(0.0, blocked.costUsd, 0.0)
        assertTrue(blocked.sites.any { it.path == "app.kt" && it.line == 4 })
        assertTrue(blocked.repairPrompt().contains("app.kt:4"))
        tx.stage("app.kt", app, app.replace("add(1, 2)", "add(1, 2, 3)"))
        val open = PreFlightValidator.signatureGate(g, tx)
        assertFalse(open.blocked)
        assertEquals(0.0, open.costUsd, 0.0)
    }
}
