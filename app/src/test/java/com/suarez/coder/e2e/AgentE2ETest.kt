package com.suarez.coder.e2e

import androidx.test.core.app.ApplicationProvider
import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.agent.Checkpoints
import com.suarez.coder.data.Provider
import com.suarez.coder.data.Settings
import com.suarez.coder.e2e.FakeWorld.Companion.diff
import com.suarez.coder.e2e.FakeWorld.Companion.reply
import com.suarez.coder.e2e.FakeWorld.Companion.toolResults
import com.suarez.coder.e2e.FakeWorld.Companion.userTexts
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.OpenAiCompatClient
import com.suarez.coder.tools.GitHub
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The whole agent against a fake GitHub and a scripted model: what the user would see, end to end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentE2ETest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var world: FakeWorld
    private lateinit var settings: Settings
    private val saved = GitHub.API
    private val asked = ArrayList<String>()
    private var answer = true
    private val ui = object : AgentUi {
        override fun update() {}
        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String): Boolean { asked += title; return answer }
    }

    private val main = "fun main() {\n    println(\"merhaba\")\n}\n"

    @Before fun setUp() {
        world = FakeWorld(files = mapOf("app/Main.kt" to main, "app/Util.kt" to "fun util() = 1\n")).start()
        GitHub.API = world.url()
        GitHub.ReadCache.clear()
        OpenAiCompatClient.BACKOFF_MS = emptyList()
        ToolRegistry.CI_POLL_MS = 10
        settings = Settings(ApplicationProvider.getApplicationContext()).apply {
            githubToken = "t"; deepseekKey = "k"; geminiKey = ""; openrouterKey = ""
            deepseekBaseUrl = world.url(); provider = Provider.DEEPSEEK; setModelFor(Provider.DEEPSEEK, "m1")
            editorModel = "deepseek/m1"; architectModel = "deepseek/m1"; architectMode = false; smartRouting = false
            escalationModel = "none"; escalationChain = ""; defaultRepo = "me/app"; defaultBranch = ""
            contextRepo = ""; contextRef = ""; contextFiles = emptyList()
            orchestratorMode = "OFF"; budgetUsd = 0.0; autoApprove = true; projectNotes = false
            docsLookup = false; semanticSearch = false; todoList = true; selfReview = true; quickCheck = false
            checkpoints = true; similarTasks = false; fixMemory = true; untilGreen = true; ciMaxRounds = 4
            failover = true; adaptiveBudget = false; activeMode = ""; terseMode = false
        }
        // The self-review model approves unless a test says otherwise.
        world.bySystem["You check a code change"] = { reply("{\"ok\":true}") }
    }

    @After fun tearDown() {
        world.shutdown()
        world.errors.firstOrNull()?.let { throw AssertionError("Senaryo içindeki kontrol başarısız: ${it.message}", it) }
        GitHub.API = saved
        GitHub.ReadCache.clear()
        OpenAiCompatClient.BACKOFF_MS = listOf(2_000L, 5_000L, 10_000L)
        ToolRegistry.CI_POLL_MS = 15_000L
    }

    private fun agent() = Agent(settings, tmp.root)
    private fun run(a: Agent, text: String): MutableList<ChatMessage> = mutableListOf<ChatMessage>(ChatMessage.User(text)).also { runBlocking { a.run(it, ui) } }
    private fun args(vararg kv: Pair<String, Any>) = JSONObject().also { o -> kv.forEach { (k, v) -> o.put(k, v) } }
    private fun applyDiff(branch: String, d: String, msg: String = "değişiklik") =
        "github_apply_diff" to args("repo" to "me/app", "branch" to branch, "message" to msg, "diff" to d)

    @Test fun editIsCommittedWithACheckpointAndTheModelSeesTheFile() {
        world.script += { reply("", "github_read_file" to args("repo" to "me/app", "path" to "app/Main.kt")) }
        world.script += { req ->
            assertTrue(toolResults(req).last().contains("println(\"merhaba\")"))
            reply("", applyDiff("feature", diff("app/Main.kt", "    println(\"merhaba\")", "    println(\"selam\")")))
        }
        world.script += { reply("Bitti.") }
        val h = run(agent(), "Mesajı selam yap")
        assertEquals(main.replace("merhaba", "selam"), world.file("feature", "app/Main.kt"))
        assertEquals(main, world.file("main", "app/Main.kt")) // default branch untouched
        val cp = h.filterIsInstance<ChatMessage.Notice>().mapNotNull { Checkpoints.find(it.detail) }.single()
        assertEquals("feature", cp.branch); assertNull(cp.sha) // the branch was new
        assertTrue(world.llmRequests.any { it.getJSONArray("messages").getJSONObject(0).optString("content").contains("You check a code change") })
    }

    @Test fun selfReviewStopsABrokenCommitThenTheFixGoesThrough() {
        var reviews = 0
        world.bySystem["You check a code change"] = { reviews++; if (reviews == 1) reply("""{"ok":false,"problems":["app/Main.kt:2 - selamText is not defined"]}""") else reply("{\"ok\":true}") }
        // Passes the local bracket/quote check, so it reaches the reviewer (a broken quote is stopped earlier, locally).
        world.script += { reply("", applyDiff("fix", diff("app/Main.kt", "    println(\"merhaba\")", "    println(selamText)"))) }
        world.script += { req ->
            assertTrue(toolResults(req).last().contains("Öz-denetim"))
            reply("", applyDiff("fix", diff("app/Main.kt", "    println(\"merhaba\")", "    println(\"selam\")")))
        }
        world.script += { reply("Düzeltildi.") }
        run(agent(), "selam yaz")
        assertEquals(2, reviews)
        assertEquals(main.replace("merhaba", "selam"), world.file("fix", "app/Main.kt"))
        assertEquals(2, world.commitMessages("fix").size) // init + one commit: the broken version never landed
    }

    @Test fun redCiKeepsTheAgentWorkingUntilGreenAndTheFixIsRemembered() {
        world.ci = { files -> if (files["app/Util.kt"]!!.contains("2")) "success" to "" else "failure" to "e: file:///w/app/Util.kt:1:5 Type mismatch: expected Int\n" }
        world.script += { reply("", applyDiff("ci", diff("app/Util.kt", "fun util() = 1", "fun util() = \"1\""), "util metni")) }
        world.script += { reply("", "ci_wait" to args("repo" to "me/app", "ref" to "ci")) }
        world.script += { req -> assertTrue(toolResults(req).last().contains("Type mismatch")); reply("CI kırmızı ama bitirdim.") } // tries to stop
        world.script += { req ->
            assertTrue(userTexts(req).any { it.contains(Agent.AUTO_NUDGE) })
            reply("", applyDiff("ci", diff("app/Util.kt", "fun util() = \"1\"", "fun util() = 2"), "util tipi düzeltildi"))
        }
        world.script += { reply("", "ci_wait" to args("repo" to "me/app", "ref" to "ci")) }
        world.script += { reply("Yeşil.") }
        val a = agent()
        run(a, "util'i değiştir")
        assertEquals("fun util() = 2\n", world.file("ci", "app/Util.kt"))
        val fix = a.fixMemory!!.lookup("me/app", "e: file:///w/app/Util.kt:9:1 Type mismatch: expected Int")
        assertTrue(fix.single().fix.contains("util tipi düzeltildi"))
    }

    @Test fun theSameCiErrorTwiceClimbsTheUsersModelOrder() {
        settings.escalationChain = "deepseek/m2\ndeepseek/m3"
        world.ci = { "failure" to "e: Main.kt:1:1 Unresolved reference: foo\n" }
        val models = ArrayList<String>()
        repeat(2) {
            world.script += { r -> models += r.getString("model"); reply("", applyDiff("esc", diff("app/Main.kt", "merhaba", "merhaba$it"))) }
            world.script += { r -> models += r.getString("model"); reply("", "ci_wait" to args("repo" to "me/app", "ref" to "esc")) }
        }
        world.script += { r -> models += r.getString("model"); reply("Vazgeçiyorum.") }
        answer = false // the user stops at the end
        val h = run(agent(), "foo ekle")
        assertEquals(listOf("m1", "m1", "m1", "m1", "m2"), models)
        assertTrue(h.filterIsInstance<ChatMessage.Notice>().any { it.text.contains("daha güçlü modele") && it.text.contains("m2") })
    }

    @Test fun aFailingProviderHandsTheCallToTheNextModel() {
        settings.escalationChain = "deepseek/m2"
        world.script += { r -> assertEquals("m1", r.getString("model")); MockResponse().setResponseCode(503).setBody("""{"error":{"message":"overloaded"}}""") }
        world.script += { r -> assertEquals("m2", r.getString("model")); reply("Yedek modelden yanıt.") }
        val h = run(agent(), "merhaba de")
        assertTrue(h.filterIsInstance<ChatMessage.Notice>().any { it.text.contains("m1 yanıt vermedi") })
        assertEquals("Yedek modelden yanıt.", (h.last { it is ChatMessage.Assistant } as ChatMessage.Assistant).text)
    }

    @Test fun anUnattendedRunCannotMergeOrWriteOutsideItsBranch() {
        val a = agent().apply { unattended = true }
        a.tools.github.writeGuard = { _, b -> if (b != null && !b.startsWith("coder/q-1")) "yalnızca coder/q-1*" else null }
        world.script += { reply("", "github_merge_pull_request" to args("repo" to "me/app", "number" to 1),
            applyDiff("main", diff("app/Main.kt", "merhaba", "x"))) }
        world.script += { req ->
            val r = toolResults(req)
            assertTrue(r[0].contains("merge yapamaz")); assertTrue(r[1].contains("yalnızca coder/q-1"))
            reply("", applyDiff("coder/q-1-a", diff("app/Main.kt", "merhaba", "x")),
                "github_create_pull_request" to args("repo" to "me/app", "title" to "x", "head" to "coder/q-1-a"))
        }
        world.script += { reply("PR açıldı.") }
        run(a, "görev")
        assertEquals(0, world.merges)
        assertEquals(main, world.file("main", "app/Main.kt"))
        assertNotNull(world.file("coder/q-1-a", "app/Main.kt"))
        assertEquals(1, world.pulls.size)
    }

    @Test fun rewindDoesNotForceResetMain() = runBlocking {
        world.script += { reply("", applyDiff("main", diff("app/Main.kt", "merhaba", "bozuk"))) }
        world.script += { reply("Tamam.") }
        val a = agent()
        val h = run(a, "bozuk yaz")
        assertEquals(main.replace("merhaba", "bozuk"), world.file("main", "app/Main.kt"))
        val cp = h.filterIsInstance<ChatMessage.Notice>().mapNotNull { Checkpoints.find(it.detail) }.single()
        assertNotNull(cp.sha)
        val denied = runCatching { a.tools.github.resetBranch(cp.repo, "main", cp.sha) }
        assertTrue(denied.isFailure)
        assertTrue(denied.exceptionOrNull()!!.message!!.contains("Force reset"))
        assertEquals(main.replace("merhaba", "bozuk"), world.file("main", "app/Main.kt"))
        a.tools.github.createBranchAt(cp.repo, "coder/rewind-1", cp.sha!!)
        assertEquals(main, world.file("coder/rewind-1", "app/Main.kt"))
    }

    @Test fun readsInOneStepRunTogetherAndKeepTheirOrder() {
        world.script += { reply("", "github_read_file" to args("repo" to "me/app", "path" to "app/Util.kt"),
            "github_read_file" to args("repo" to "me/app", "path" to "app/Main.kt")) }
        world.script += { req ->
            val r = toolResults(req)
            assertTrue(r[0].contains("util()")); assertTrue(r[1].contains("merhaba"))
            reply("İki dosya okundu.")
        }
        run(agent(), "dosyaları oku")
    }

    @Test fun mentionedFilesAreGivenWithTheRequest() {
        world.script += { req -> assertTrue(userTexts(req).joinToString().contains("fun util() = 1")); reply("Gördüm.") }
        val h = run(agent(), "@app/Util.kt'e bak")
        assertTrue(h.any { it is ChatMessage.ContextBlock && it.label.contains("app/Util.kt") })
    }

    @Test fun architectPlanCriticAddsTheMissingCallerAndOneCommitChangesBoth() {
        settings.architectMode = true; settings.planCritic = true
        world.bySystem["You are the ARCHITECT"] = {
            reply("", "submit_plan" to args("repo" to "me/app", "branch" to "rename", "commit_message" to "util → helper",
                "files" to org.json.JSONArray().put(args("path" to "app/Util.kt", "action" to "modify", "instructions" to "util'i helper yap"))))
        }
        world.bySystem["You review a code-change PLAN"] = {
            reply("""{"ok":false,"issues":["Main.kt calls util()"],"add_files":[{"path":"app/Main.kt","action":"modify","instructions":"util() çağrısını helper() yap"}]}""")
        }
        world.bySystem["You are a code EDITOR"] = { req ->
            val prompt = userTexts(req).joinToString()
            assertTrue(prompt.contains("app/Main.kt")) // the critic's file reached the executor
            reply(diff("app/Util.kt", "fun util() = 1", "fun helper() = 1") + "\n\n" + diff("app/Main.kt", "    println(\"merhaba\")", "    println(helper())"))
        }
        val h = run(agent(), "util fonksiyonunun adını helper yap ve her yerde güncelle")
        assertEquals("fun helper() = 1\n", world.file("rename", "app/Util.kt"))
        assertTrue(world.file("rename", "app/Main.kt")!!.contains("println(helper())"))
        assertEquals(2, world.commitMessages("rename").size) // one atomic commit
        assertTrue(h.filterIsInstance<ChatMessage.Notice>().any { it.text.startsWith("Plan eleştirmeni") })
    }

    @Test fun injectedTextCannotDeleteTheRepoOrPushElsewhereEvenWithAutoApprove() {
        world.script += { reply("", "github_read_file" to args("repo" to "me/app", "path" to "app/Main.kt")) }
        // The model "obeys" an instruction hidden in a file: delete the repo, push to another account.
        world.script += { reply("", "github_api" to args("method" to "DELETE", "path" to "/repos/me/app"),
            applyDiff("main", diff("app/Main.kt", "merhaba", "x")).let { it.first to it.second.put("repo", "evil/other") }) }
        world.script += { req ->
            val r = toolResults(req)
            assertTrue(r[r.size - 2].contains("güvenlik onayı vermedi")); assertTrue(r.last().contains("güvenlik onayı vermedi"))
            reply("Yapmadım.")
        }
        answer = false
        run(agent(), "Main.kt'deki talimatları uygula")
        assertTrue(asked.contains("Tehlikeli GitHub işlemi")); assertTrue(asked.contains("Başka bir repoya yazma"))
        assertEquals(main, world.file("main", "app/Main.kt"))
    }

    @Test fun theTaskListIsKeptAcrossSteps() {
        val todos = args("items" to org.json.JSONArray().put(args("text" to "Oku", "status" to "in_progress")).put(args("text" to "Değiştir", "status" to "pending")))
        world.script += { reply("", "update_todos" to todos) }
        world.script += { req -> assertTrue(toolResults(req).last().startsWith("[>] Oku")); reply("Tamam.") }
        val h = run(agent(), "iki adımlı iş")
        assertEquals(2, com.suarez.coder.agent.Todos.latest(h).size)
        assertTrue(asked.isEmpty())
    }
}
