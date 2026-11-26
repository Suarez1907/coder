package com.suarez.coder

import com.suarez.coder.agent.CodeIndex
import com.suarez.coder.agent.CodeIndexStore
import com.suarez.coder.agent.Mutator
import com.suarez.coder.agent.SymbolExtractor
import com.suarez.coder.agent.SymbolExtractor.Range
import com.suarez.coder.agent.TestSelector
import com.suarez.coder.cloudtools.CloudWorkflowTemplates
import com.suarez.coder.orchestrator.MaestroFlows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodeIntelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val kotlin = """
        package a

        /**
         * Saves the score.
         */
        @Synchronized
        fun saveScore(s: Int): Boolean {
            if (s < 0) {
                return false
            }
            val msg = "brace } in string"
            return true
        }

        fun square(x: Int) =
            x * x

        fun other() = 1
    """.trimIndent() + "\n"

    private val python = """
        import os

        class Board:
            ""${'"'}A board.""${'"'}

            @property
            def size(self):
                return 3

            def place(self, x, y):
                if x < 0:
                    raise ValueError("x")

                return True


        def helper():
            pass
    """.trimIndent() + "\n"

    @Test fun symbolsAreCutWithTheirDocsAndBodies() {
        assertEquals(Range(3, 13), SymbolExtractor.find(kotlin, "saveScore")) // KDoc + annotation + braces (a brace inside a string ignored)
        assertEquals(Range(15, 16), SymbolExtractor.find(kotlin, "square"))   // expression body on the next line
        assertEquals(Range(18, 18), SymbolExtractor.find(kotlin, "other"))
        assertNull(SymbolExtractor.find(kotlin, "missing"))
        assertEquals(Range(10, 14), SymbolExtractor.find(python, "place"))   // indented block, blank line inside
        assertEquals(Range(6, 8), SymbolExtractor.find(python, "size"))      // decorator included
        assertEquals(Range(3, 14), SymbolExtractor.find(python, "Board"))
        // Two definitions of the same name: the hint (index line) picks the right one.
        val twice = "class A {\n    fun render() {\n        a()\n    }\n}\nclass B {\n    fun render() {\n        b()\n    }\n}\n"
        assertEquals(Range(2, 4), SymbolExtractor.find(twice, "render"))
        assertEquals(Range(7, 9), SymbolExtractor.find(twice, "render", hint = 7))
    }

    private val index = CodeIndex.parse("me/app", """
        {"sha":"abc","defs":{"saveScore":[["app/src/main/java/a/Score.kt",7,"method","Score"]],"Score":[["app/src/main/java/a/Score.kt",3,"class",""]]},
         "refs":{"saveScore":["app/src/main/java/a/Score.kt:7","app/src/main/java/a/Game.kt:5","app/src/test/java/a/GameTest.kt:12"]}}
    """.trimIndent(), builtAt = 42)

    @Test fun codeIndexAnswersWhereNamesLive() {
        assertEquals("abc", index.sha)
        assertEquals(setOf("app/src/main/java/a/Score.kt", "app/src/main/java/a/Game.kt", "app/src/test/java/a/GameTest.kt"), index.filesFor("saveScore"))
        assertEquals(setOf("saveScore", "Score"), index.definedIn("app/src/main/java/a/Score.kt").toSet())
        val store = CodeIndexStore(tmp.newFolder("idx"))
        assertNull(store.get("me/app"))
        store.put("me/app", """{"sha":"s1","defs":{"f":[["x.py",1,"function",""]]},"refs":{}}""")
        val back = store.get("me/app")!!
        assertEquals("s1", back.sha); assertEquals(1, back.defs.getValue("f").single().line)
        assertTrue(System.currentTimeMillis() - back.builtAt < 60_000)
    }

    @Test fun testsAreSelectedByNameAndByUse() {
        assertTrue(TestSelector.isTest("app/src/test/java/a/FooTest.kt"))
        assertTrue(TestSelector.isTest("web/src/a.test.ts") && TestSelector.isTest("tests/test_x.py") && TestSelector.isTest("pkg/x_test.py"))
        assertFalse(TestSelector.isTest("app/src/main/java/a/Foo.kt"))
        assertFalse(TestSelector.isTest("app/src/androidTest/java/a/UiTest.kt")) // needs a device
        val tree = listOf("app/src/main/java/a/Score.kt", "app/src/main/java/a/Game.kt", "app/src/test/java/a/ScoreTest.kt",
            "app/src/test/java/a/GameTest.kt", "app/src/test/java/a/OtherTest.kt")
        assertEquals(listOf("app/src/test/java/a/ScoreTest.kt"), TestSelector.select(listOf("app/src/main/java/a/Score.kt"), tree))
        assertEquals(listOf("app/src/test/java/a/ScoreTest.kt", "app/src/test/java/a/GameTest.kt"),
            TestSelector.select(listOf("app/src/main/java/a/Score.kt"), tree, index)) // GameTest uses saveScore
        assertEquals(listOf("app/src/test/java/a/OtherTest.kt"), TestSelector.select(listOf("app/src/test/java/a/OtherTest.kt"), tree))
        assertEquals("app" to listOf("a.ScoreTest", "a.GameTest"), TestSelector.gradle(listOf("app/src/test/java/a/ScoreTest.kt", "app/src/test/java/a/GameTest.kt")))
        assertEquals("feature:login" to listOf("x.LoginTest"), TestSelector.gradle(listOf("feature/login/src/test/kotlin/x/LoginTest.kt")))
        assertEquals("" to listOf("x.YTest"), TestSelector.gradle(listOf("src/test/kotlin/x/YTest.kt")))
        assertNull(TestSelector.gradle(listOf("web/a.test.ts")))
        assertEquals(listOf("web/a.test.ts", "tests/test_x.py"), TestSelector.files(listOf("web/a.test.ts", "tests/test_x.py", "bad;rm.py", "app/src/test/java/a/FooTest.kt")))
    }

    @Test fun mutantsAreSmallRealBugsOutsideCommentsAndStrings() {
        val text = "fun ok(a: Int, b: Int): Boolean {\n    // a == b is a comment\n    if (a == b && a > 0) return true\n" +
            "    val s = \"x == y\"\n    return a + 1 >= b\n}\n"
        val ms = Mutator.generate("A.kt", text, 1, 6)
        assertEquals(listOf(3, 5), ms.map { it.line })
        assertEquals("    if (a != b && a > 0) return true", ms[0].replace)
        assertEquals("    return a + 1 < b", ms[1].replace)
        ms.forEach { m -> assertEquals(1, text.split(m.search).size - 1) } // each search is unique in the file
        assertEquals(listOf(5), Mutator.generate("A.kt", text, 4, 5).map { it.line }) // range respected
        val dup = "x == 1\nx == 1\n"
        assertTrue(Mutator.generate("B.kt", dup, 1, 2).isEmpty()) // ambiguous lines are skipped
        assertTrue(Mutator.toJson(ms).contains("\"search\""))
        val (killed, survived, error) = Mutator.results("""{"results":[{"id":"m1","status":"killed"},{"id":"m2","status":"survived"}]}""", ms)
        assertEquals(1, killed); assertEquals(listOf("satır 5: `>=` → `<` (A.kt)"), survived); assertNull(error)
        assertEquals("kırık", Mutator.results("""{"error":"kırık"}""", ms).error)
        assertEquals(1, Mutator.results("""{"results":[{"id":"m1","status":"invalid"},{"id":"m2","status":"killed"}]}""", ms).invalid)
    }

    @Test fun maestroFlowsAndReports() {
        val reply = "Akışlar:\n```yaml\n# Skor kaydedilir\nappId: com.wrong\n---\n- launchApp:\n    clearState: true\n- tapOn: \"Kaydet\"\n- assertVisible: \"Kaydedildi\"\n```\n" +
            "```yaml\n---\n- launchApp\n- assertVisible: \"Ayarlar\"\n```\n```yaml\nnot: a flow\n```"
        val flows = MaestroFlows.extract(reply, "com.suarez.coder")
        assertEquals(2, flows.size)
        assertEquals("Skor kaydedilir", flows[0].name)
        assertTrue(flows[0].yaml.contains("appId: com.suarez.coder") && !flows[0].yaml.contains("com.wrong"))
        assertTrue(flows[0].yaml.contains("appId: com.suarez.coder\nname: \"Skor kaydedilir\"\n---"))
        assertTrue(flows[1].yaml.startsWith("appId: com.suarez.coder\nname: \"Ölçüt 2\"\n---"))
        assertEquals("Ölçüt 2", flows[1].name)
        assertEquals("\"a \\\"b\\\" c\\\\d\"", MaestroFlows.quote("a \"b\" c\\d\u0007"))
        val y = org.yaml.snakeyaml.Yaml().loadAll(flows[0].yaml).toList()
        assertEquals("Skor kaydedilir", (y[0] as Map<*, *>)["name"])
        assertEquals("com.suarez.coder", MaestroFlows.appId(mapOf("app/build.gradle.kts" to "defaultConfig {\n    applicationId = \"com.suarez.coder\"\n}")))
        assertEquals("com.x.y", MaestroFlows.appId(mapOf("app/build.gradle" to "defaultConfig {\n    applicationId 'com.x.y'\n}")))
        val xml = "<?xml version='1.0' encoding='UTF-8'?><testsuites><testsuite name=\"Test Suite\" tests=\"4\" failures=\"2\">" +
            "<testcase id=\"01\" name=\"01\" classname=\"01\" time=\"3.1\" status=\"SUCCESS\"/>" +
            "<testcase id=\"02\" name=\"02\" classname=\"02\" status=\"ERROR\"><failure>Element not found: Text matching regex: Kaydet</failure></testcase>" +
            "<testcase id=\"03\" name=\"03\"><failure message=\"Assertion is false\" /></testcase>" +
            "<testcase id=\"04\" name=\"04\"></testcase></testsuite></testsuites>"
        val cases = MaestroFlows.junit(xml)
        assertEquals(listOf("01", "02", "03", "04"), cases.map { it.name })
        assertEquals(listOf(true, false, false, true), cases.map { it.pass })
        assertTrue(cases[1].message.contains("Element not found"))
        assertTrue(cases[2].message.contains("Assertion is false"))
    }

    @Test fun cloudCodeToolTemplates() {
        val dir = java.io.File("build/wf").apply { mkdirs() }
        CloudWorkflowTemplates.ALL.forEach { java.io.File(dir, it.file).writeText(it.yaml) } // checked with bash -n / yaml outside
        assertEquals(CloudWorkflowTemplates.INDEX, CloudWorkflowTemplates.forTool("index"))
        assertEquals(CloudWorkflowTemplates.CODEMOD, CloudWorkflowTemplates.forTool("ast-grep"))
        assertEquals(CloudWorkflowTemplates.MUTATE, CloudWorkflowTemplates.forTool("mutation"))
        val idx = CloudWorkflowTemplates.INDEX.yaml
        assertTrue(idx.contains("universal-ctags") && idx.contains("--output-format=json") && idx.contains("index.json"))
        assertFalse(idx.contains("lstrip(\"./\")")) // would turn .github/x into github/x
        val cm = CloudWorkflowTemplates.CODEMOD.yaml
        assertTrue(cm.contains("@ast-grep/cli@0.51.1") && cm.contains("--update-all") && cm.contains("__too_large__"))
        val mu = CloudWorkflowTemplates.MUTATE.yaml
        assertTrue(mu.contains("results.json") && mu.contains("tr -cd") && mu.contains("\"invalid\""))
        assertTrue(CloudWorkflowTemplates.ANDROID_SHOTS.yaml.contains("---- Maestro ----"))
        assertTrue(CloudWorkflowTemplates.QUICK.yaml.contains("Etkilenen testler"))
        assertTrue(CloudWorkflowTemplates.ANDROID_SHOTS.yaml.contains("maestro test") && CloudWorkflowTemplates.ANDROID_SHOTS.yaml.contains("--format junit"))
        assertFalse(CloudWorkflowTemplates.MUTATE.scriptOptional) // the mutant list is required
    }
}
