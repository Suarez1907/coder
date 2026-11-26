package com.suarez.coder

import com.suarez.coder.context.SymbolIndexer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SymbolIndexerTest {

    @Before
    fun setUp() {
        SymbolIndexer.resetCache()
    }

    @Test
    fun testKotlinIndexing() {
        val ktCode = """
            package com.example

            class UserService {
                fun getUser(id: String): User {
                    return repo.find(id)
                }

                fun deleteUser(id: String) {
                    repo.delete(id)
                }
            }
        """.trimIndent()

        val symbols = SymbolIndexer.index("UserService.kt", ktCode)
        assertEquals(3, symbols.size)

        val classSym = symbols.find { it.name == "UserService" }
        assertTrue(classSym != null)
        assertEquals("class", classSym!!.kind)
        assertEquals(3, classSym.startLine)
        assertEquals(11, classSym.endLine)

        val getMethod = symbols.find { it.name == "getUser" }
        assertTrue(getMethod != null)
        assertEquals("function", getMethod!!.kind)
        assertEquals(4, getMethod.startLine)
        assertEquals(6, getMethod.endLine)

        val delMethod = symbols.find { it.name == "deleteUser" }
        assertTrue(delMethod != null)
        assertEquals(8, delMethod!!.startLine)
        assertEquals(10, delMethod.endLine)

        val compact = SymbolIndexer.formatCompact("UserService.kt", ktCode)
        assertTrue(compact.startsWith("UserService.kt (12 satır)"))
        assertTrue(compact.contains("L3-L11 class UserService"))
        assertTrue(compact.contains("L4-L6 fun getUser(id: String): User"))
    }

    @Test
    fun testPythonIndentationIndexing() {
        val pyCode = """
            class Runner:
                def __init__(self, name):
                    self.name = name

                def run(self):
                    print("running")

            def standalone():
                pass
        """.trimIndent()

        val symbols = SymbolIndexer.index("runner.py", pyCode)
        assertEquals(4, symbols.size)

        val runnerClass = symbols.find { it.name == "Runner" }
        assertTrue(runnerClass != null)
        assertEquals("class", runnerClass!!.kind)
        assertEquals(1, runnerClass.startLine)
        assertEquals(6, runnerClass.endLine)

        val initFunc = symbols.find { it.name == "__init__" }
        assertTrue(initFunc != null)
        assertEquals(2, initFunc!!.startLine)
        assertEquals(3, initFunc.endLine)

        val standalone = symbols.find { it.name == "standalone" }
        assertTrue(standalone != null)
        assertEquals(8, standalone!!.startLine)
        assertEquals(9, standalone.endLine)
    }

    @Test
    fun testGDScriptIndexing() {
        val gdCode = """
            class_name Player
            extends CharacterBody2D

            signal health_changed(new_health)

            func _ready():
                print("ready")

            func take_damage(amount: int):
                health -= amount
                emit_signal("health_changed", health)
        """.trimIndent()

        val symbols = SymbolIndexer.index("player.gd", gdCode)
        assertEquals(4, symbols.size)

        val playerCls = symbols.find { it.name == "Player" }
        assertTrue(playerCls != null)
        assertEquals("class", playerCls!!.kind)

        val signal = symbols.find { it.name == "health_changed" }
        assertTrue(signal != null)
        assertEquals("signal", signal!!.kind)

        val ready = symbols.find { it.name == "_ready" }
        assertTrue(ready != null)
        assertEquals("function", ready!!.kind)
        assertEquals(6, ready!!.startLine)
        assertEquals(7, ready.endLine)

        val takeDamage = symbols.find { it.name == "take_damage" }
        assertTrue(takeDamage != null)
        assertEquals(9, takeDamage!!.startLine)
        assertEquals(11, takeDamage.endLine)
    }

    @Test
    fun testYamlTopLevelKeys() {
        val yamlCode = """
            name: CI Pipeline

            on:
              push:
                branches: [ main ]

            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v3
        """.trimIndent()

        val symbols = SymbolIndexer.index(".github/workflows/ci.yml", yamlCode)
        assertEquals(3, symbols.size)

        val nameKey = symbols.find { it.name == "name" }
        assertTrue(nameKey != null)
        assertEquals(1, nameKey!!.startLine)

        val onKey = symbols.find { it.name == "on" }
        assertTrue(onKey != null)
        assertEquals(3, onKey!!.startLine)
        assertEquals(5, onKey.endLine)

        val jobsKey = symbols.find { it.name == "jobs" }
        assertTrue(jobsKey != null)
        assertEquals(7, jobsKey!!.startLine)
        assertEquals(11, jobsKey.endLine)
    }

    @Test
    fun testLruCacheHits() {
        val content = "fun test() { println(1) }"
        assertEquals(0L, SymbolIndexer.cacheHits)
        assertEquals(0L, SymbolIndexer.cacheMisses)

        SymbolIndexer.index("Test.kt", content)
        assertEquals(0L, SymbolIndexer.cacheHits)
        assertEquals(1L, SymbolIndexer.cacheMisses)
        assertTrue(SymbolIndexer.isCached("Test.kt", content))

        SymbolIndexer.index("Test.kt", content)
        assertEquals(1L, SymbolIndexer.cacheHits)
        assertEquals(1L, SymbolIndexer.cacheMisses)
    }

    @Test
    fun testReadRangeLimitsAndLineNumbers() {
        val lines = (1..300).map { "val x$it = $it" }.joinToString("\n")

        val slice = SymbolIndexer.readRange(lines, 10, 20)
        val sliceLines = slice.lines()
        assertEquals(11, sliceLines.size)
        assertEquals("10: val x10 = 10", sliceLines.first())
        assertEquals("20: val x20 = 20", sliceLines.last())

        // Max lines cap (MAX_RANGE_LINES = 250)
        val capped = SymbolIndexer.readRange(lines, 1, 300)
        assertTrue(capped.contains("... (güvenli tavan: en fazla 250 satır gösterildi"))
        assertTrue(capped.startsWith("1: val x1 = 1"))
        assertTrue(capped.contains("250: val x250 = 250"))
    }
}
