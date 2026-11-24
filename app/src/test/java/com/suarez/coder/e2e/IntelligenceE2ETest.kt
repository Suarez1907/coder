package com.suarez.coder.e2e

import androidx.test.core.app.ApplicationProvider
import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.data.Provider
import com.suarez.coder.data.Settings
import com.suarez.coder.e2e.FakeWorld.Companion.diff
import com.suarez.coder.e2e.FakeWorld.Companion.reply
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.OpenAiCompatClient
import com.suarez.coder.tools.GitHub
import com.suarez.coder.tools.ToolRegistry
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
import java.util.concurrent.atomic.AtomicInteger

/** Candidates, plan uncertainty, the architect path's commit checks and risk labels, end to end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntelligenceE2ETest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var world: FakeWorld
    private lateinit var settings: Settings
    private val asked = ArrayList<String>()
    private var answer = false
    private val ui = object : AgentUi {
        override fun update() {}
        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String): Boolean { asked += title; return answer }
    }
    private val saved = GitHub.API

    @Before fun setUp() {
        world = FakeWorld(files = mapOf("a.kt" to "val a = 1\n", "b.kt" to "val b = 2\n")).start()
        GitHub.API = world.url(); GitHub.ReadCache.clear(); GitHub.ETags.clear()
        OpenAiCompatClient.BACKOFF_MS = emptyList(); ToolRegistry.CI_POLL_MS = 10
        settings = Settings(ApplicationProvider.getApplicationContext()).apply {
            githubToken = "t"; deepseekKey = "k"; geminiKey = ""; openrouterKey = ""; deepseekBaseUrl = world.url()
            provider = Provider.DEEPSEEK; setModelFor(Provider.DEEPSEEK, "m1"); editorModel = "deepseek/m1"; architectModel = "deepseek/m1"
            architectMode = true; smartRouting = false; escalationModel = "none"; escalationChain = ""; defaultRepo = "me/app"
            contextRepo = ""; contextRef = ""; contextFiles = emptyList(); orchestratorMode = "OFF"; budgetUsd = 0.0; autoApprove = true
            projectNotes = false; docsLookup = false; semanticSearch = false; selfReview = false; quickCheck = false; activeMode = ""
            planCritic = false; planUncertainty = false; candidates = 3; candidateTrigger = "off"; supplyChainCheck = true; maxExecutorRetries = 2
        }
        world.bySystem["You are the ARCHITECT"] = {
            reply("", "submit_plan" to JSONObject().put("repo", "me/app").put("branch", "plan").put("commit_message", "a=5")
                .put("files", JSONArray().put(JSONObject().put("path", "a.kt").put("action", "modify").put("instructions", "a'yı 5 yap"))))
        }
    }

    @After fun tearDown() {
        world.shutdown(); GitHub.API = saved; GitHub.ReadCache.clear(); GitHub.ETags.clear()
        OpenAiCompatClient.BACKOFF_MS = listOf(2_000L, 5_000L, 10_000L); ToolRegistry.CI_POLL_MS = 15_000L
        world.errors.firstOrNull()?.let { throw AssertionError(it.message, it) }
    }

    private fun run(text: String) = mutableListOf<ChatMessage>(ChatMessage.User(text)).also { runBlocking { Agent(settings, tmp.root).run(it, ui) } }

    @Test fun threeCandidatesTheAgreedOneWins() {
        settings.candidateTrigger = "always"
        val n = AtomicInteger()
        world.bySystem["You are a code EDITOR"] = {
            when (n.getAndIncrement()) {
                2 -> reply(diff("a.kt", "val a = 9", "val a = 7")) // SEARCH does not match: fails locally
                else -> reply(diff("a.kt", "val a = 1", "val a = 5"))
            }
        }
        val h = run("a'yı 5 yap")
        assertEquals(3, n.get())
        assertEquals("val a = 5\n", world.file("plan", "a.kt"))
        assertTrue(h.filterIsInstance<ChatMessage.Notice>().any { it.text.startsWith("Çok aday: 3 aday, 2 yerel kontrolden geçti") && it.text.contains("2 aday aynı sonuca") })
        // Temperatures differ between candidates.
        val temps = world.llmRequests.filter { it.getJSONArray("messages").getJSONObject(0).optString("content").contains("You are a code EDITOR") }
            .map { it.optDouble("temperature") }.toSet()
        assertEquals(3, temps.size)
    }

    @Test fun anAmbiguousPlanStopsAndAsks() {
        settings.planUncertainty = true
        world.bySystem["List the repository files that must be created"] = { reply("{\"files\":[\"z.kt\",\"y.kt\"]}") }
        world.bySystem["You are a code EDITOR"] = { reply(diff("a.kt", "val a = 1", "val a = 5")) }
        val h = run("bunu düzelt")
        assertEquals(listOf("Plan belirsiz"), asked)
        assertTrue((h.last { it is ChatMessage.Assistant } as ChatMessage.Assistant).text.startsWith(Agent.CLARIFY_MARK))
        assertFalse(world.branches.containsKey("plan"))
    }

    @Test fun theArchitectPathAlsoGoesThroughTheCommitChecks() {
        settings.selfReview = true
        world.bySystem["You check a code change"] = { reply("""{"ok":false,"problems":["a.kt:1 - wrong value"]}""") }
        world.bySystem["You are a code EDITOR"] = { reply(diff("a.kt", "val a = 1", "val a = 5")) }
        val h = run("a'yı 5 yap")
        assertFalse(world.branches.containsKey("plan")) // stopped before the commit
        assertTrue(h.filterIsInstance<ChatMessage.ToolResult>().any { it.content.contains("Commit denetimi değişikliği durdurdu") })
    }

    @Test fun commitsAreLabelledWithTheirCiOutcomeForTheRiskModel() = runBlocking {
        settings.architectMode = false
        world.ci = { "failure" to "e: x\n" }
        world.script += { reply("", "github_apply_diff" to JSONObject().put("repo", "me/app").put("branch", "r").put("message", "m").put("diff", diff("a.kt", "val a = 1", "val a = 2"))) }
        world.script += { reply("Tamam.") }
        val a = Agent(settings, tmp.root)
        a.run(mutableListOf(ChatMessage.User("a=2")), ui)
        val rec = a.foresight.log!!.all().single()
        assertEquals(null, rec.failed)
        a.foresight.labelPending(minAgeMs = 0)
        assertEquals(true, a.foresight.log!!.all().single().failed)
    }
}
