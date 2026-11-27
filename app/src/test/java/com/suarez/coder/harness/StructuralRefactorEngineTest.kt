package com.suarez.coder.harness

import com.suarez.coder.context.RepoDependencyGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuralRefactorEngineTest {
    private fun graph(files: Map<String, String>): RepoDependencyGraph {
        val g = RepoDependencyGraph()
        files.forEach { (p, t) -> g.update(p, p, t) }
        return g
    }

    private fun engine(files: Map<String, String>) = StructuralRefactorEngine(graph(files))

    @Test fun renamesAcrossKotlinPythonAndGdWithoutTouchingStringsOrComments() {
        val files = mapOf(
            "A.kt" to """
                fun oldName() = 1
                // oldName yorumda kalır
                val s = "oldName"
                val x = oldName()
            """.trimIndent() + "\n",
            "b.py" to """
                def old_py():
                    return 1
                # old_py
                s = "old_py"
                old_py()
            """.trimIndent() + "\n",
            "c.gd" to """
                func old_gd():
                    pass
                # old_gd
                var s = "old_gd"
                old_gd()
            """.trimIndent() + "\n",
        )
        val kt = engine(files).rename("oldName", "neoName", null, false)
        assertTrue(kt.summary, kt.ok)
        val next = kt.edits.single { it.path == "A.kt" }.after!!
        assertTrue(next.contains("fun neoName()"))
        assertTrue(next.contains("neoName()"))
        assertTrue(next.contains("// oldName yorumda kalır"))
        assertTrue(next.contains("\"oldName\""))
        assertFalse(next.contains("fun oldName"))

        val py = engine(files).rename("old_py", "new_py", listOf("b.py"), false)
        assertTrue(py.summary, py.ok)
        val pyText = py.edits.single().after!!
        assertTrue(pyText.contains("def new_py()"))
        assertTrue(pyText.contains("new_py()"))
        assertTrue(pyText.contains("# old_py"))
        assertTrue(pyText.contains("\"old_py\""))

        val gd = engine(files).rename("old_gd", "new_gd", listOf("c.gd"), false)
        assertTrue(gd.summary, gd.ok)
        val gdText = gd.edits.single().after!!
        assertTrue(gdText.contains("func new_gd()"))
        assertTrue(gdText.contains("# old_gd"))
        assertTrue(gdText.contains("\"old_gd\""))
        assertTrue(gdText.contains("new_gd()"))
    }

    @Test fun updatesImportsAtomically() {
        val files = mapOf(
            "A.kt" to "import com.old.Foo\nimport com.other.Bar\n",
            "b.py" to "from old.mod import Foo\nimport old.mod\n",
            "c.gd" to "const X = preload(\"res://old/player.gd\")\n",
        )
        val eng = engine(files)
        val kt = eng.updateImports("com.old", "com.new", listOf("A.kt"))
        assertTrue(kt.ok)
        assertEquals("import com.new.Foo\nimport com.other.Bar\n", kt.edits.single().after)
        val py = eng.updateImports("old.mod", "new.mod", listOf("b.py"))
        assertTrue(py.summary, py.ok)
        assertTrue(py.edits.single().after!!.contains("from new.mod import Foo"))
        assertTrue(py.edits.single().after!!.contains("import new.mod"))
        val gd = eng.updateImports("res://old", "res://new", listOf("c.gd"))
        assertTrue(gd.summary, gd.ok)
        assertTrue(gd.edits.single().after!!.contains("res://new/player.gd"))
        assertFalse(gd.edits.single().after!!.contains("res://old/"))
    }

    @Test fun movesAKotlinFileAndUpdatesPackageAndImportsInOneCommit() {
        val from = "src/main/kotlin/com/app/Foo.kt"
        val to = "src/main/kotlin/com/app/feature/Foo.kt"
        val files = mapOf(
            from to "package com.app\n\nclass Foo {\n    fun ping() = 1\n}\n",
            "src/main/kotlin/com/app/Use.kt" to "package com.app\n\nimport com.app.Foo\n\nfun use() = Foo()\n",
        )
        var pushed: List<StagedTransaction.Edit>? = null
        val outcome = engine(files).let { eng ->
            eng.commit(eng.move(from, to, true)) { pushed = it }
        }
        assertTrue(outcome.summary, outcome.ok)
        assertEquals(1, if (pushed == null) 0 else 1)
        assertTrue(pushed!!.any { it.path == from && it.after == null })
        val moved = pushed!!.first { it.path == to }.after!!
        assertTrue(moved.contains("package com.app.feature"))
        assertTrue(moved.contains("class Foo"))
        val use = pushed!!.first { it.path.endsWith("Use.kt") }.after!!
        assertTrue(use.contains("import com.app.feature.Foo"))
        assertTrue(use.contains("Foo()"))
        assertTrue(outcome.filesTouched >= 2)
        assertTrue(outcome.savedTokens > 0)
    }

    @Test fun aNameClashRollsBackAndListsTheLine() {
        val files = mapOf(
            "A.kt" to "fun oldName() = 1\nfun newName() = 2\n",
            "B.kt" to "fun call() = oldName()\n",
        )
        var pushed = false
        val outcome = engine(files).let { eng ->
            eng.commit(eng.rename("oldName", "newName", null, false)) { pushed = true }
        }
        assertFalse(outcome.ok)
        assertTrue(pushed.not())
        assertTrue(outcome.edits.isEmpty())
        assertTrue(outcome.lines.any { it.contains("A.kt:") && it.contains("newName") })
    }
}
