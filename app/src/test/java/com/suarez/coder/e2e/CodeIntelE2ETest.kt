package com.suarez.coder.e2e

import androidx.test.core.app.ApplicationProvider
import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.CodeIndex
import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.CloudOps
import com.suarez.coder.tools.GitHub
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** find_references and read_symbol with a code index: only the files that matter are read, newer commits included. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodeIntelE2ETest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var world: FakeWorld
    private lateinit var settings: Settings
    private val saved = GitHub.API

    private val score = "package a\n\nclass Score {\n    /** Saves. */\n    fun saveScore(v: Int): Boolean {\n        if (v < 0) return false\n" +
        "        return true\n    }\n\n    fun reset() {}\n}\n"

    @Before fun setUp() {
        val files = HashMap<String, String>()
        files["app/src/main/java/a/Score.kt"] = score
        files["app/src/main/java/a/Game.kt"] = "package a\n\nfun play(s: Score) {\n    s.saveScore(3)\n}\n"
        for (i in 0 until 40) files["app/src/main/java/a/F$i.kt"] = "package a\n\nval v$i = $i\n"
        world = FakeWorld(files = files).start()
        GitHub.API = world.url(); GitHub.ReadCache.clear(); GitHub.ETags.clear()
        settings = Settings(ApplicationProvider.getApplicationContext()).apply { githubToken = "t"; defaultRepo = "me/app"; codeIndex = true }
    }

    @After fun tearDown() {
        world.shutdown(); GitHub.API = saved; GitHub.ReadCache.clear(); GitHub.ETags.clear()
        world.errors.firstOrNull()?.let { throw AssertionError(it.message, it) }
    }

    @Test fun referencesAndSymbolsComeFromTheIndexPlusFilesChangedSince() = runBlocking {
        val agent = Agent(settings, tmp.root)
        val built = world.branches.getValue("main")
        val idx = CodeIndex.parse("me/app", JSONObject().put("sha", built)
            .put("defs", JSONObject().put("saveScore", JSONArray().put(JSONArray(listOf("app/src/main/java/a/Score.kt", 5, "method", "Score")))))
            .put("refs", JSONObject().put("saveScore", JSONArray(listOf("app/src/main/java/a/Score.kt:5", "app/src/main/java/a/Game.kt:4")))).toString())
        var asked = 0
        agent.tools.cloudOps = object : CloudOps {
            override suspend fun codeIndex(repo: String, refresh: Boolean): CodeIndex { asked++; return idx }
            override suspend fun codemod(repo: String, branch: String, base: String?, args: JSONObject): String = error("unused")
            override suspend fun mutation(repo: String, branch: String, path: String, from: Int?, to: Int?, tests: List<String>?): String = error("unused")
        }
        // Committed after the index was built: found through the compare API.
        agent.tools.github.commitFiles("me/app", "main", "later",
            listOf(GitHub.FileChange("app/src/main/java/a/Late.kt", "package a\n\nfun late(s: Score) = s.saveScore(1)\n", false)), null)
        GitHub.ReadCache.clear()

        val refs = agent.tools.execute(ToolCall("1", "find_references", JSONObject().put("repo", "me/app").put("symbol", "saveScore")))
        assertTrue(refs, refs.contains("1 tanım, 2 kullanım, 3 dosya (kod dizini)"))
        assertTrue(refs, refs.contains("app/src/main/java/a/Late.kt:3"))
        assertEquals(1, world.counts["graphql"]) // the three candidate files in one request
        assertTrue(world.contentReads.toString(), world.contentReads.none { it.endsWith(".kt") }) // none of the 40 unrelated files

        val sym = agent.tools.execute(ToolCall("2", "read_symbol", JSONObject().put("repo", "me/app").put("symbol", "Score.saveScore")))
        assertTrue(sym, sym.startsWith("### app/src/main/java/a/Score.kt:4-8"))
        assertTrue(sym, sym.contains("4:     /** Saves. */") && sym.contains("5:     fun saveScore(v: Int): Boolean {") && sym.contains("8:     }"))
        assertFalse(sym, sym.contains("reset"))
        assertTrue(asked >= 2)
    }
}
