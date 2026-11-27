package com.suarez.coder

import androidx.test.core.app.ApplicationProvider
import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.agent.ArchitectEditor
import com.suarez.coder.agent.PlanFile
import com.suarez.coder.data.Provider
import com.suarez.coder.data.Settings
import com.suarez.coder.decision.PrePushGate
import com.suarez.coder.evolution.Experiment
import com.suarez.coder.evolution.WeeklyCycle
import com.suarez.coder.harness.OscillationBreaker
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.orchestrator.OrchestratorTools
import com.suarez.coder.orchestrator.PlanState
import com.suarez.coder.orchestrator.TaskStatus
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The architect/executor rules, checked on the real Agent against a mock OpenAI-compatible API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchitectLockTest {
    private lateinit var server: MockWebServer
    private lateinit var settings: Settings
    private val ui = object : AgentUi {
        override fun update() {}
        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String) = false
    }

    private fun reply(content: String, toolCalls: List<Pair<String, JSONObject>> = emptyList()): MockResponse {
        val msg = JSONObject().put("content", content)
        if (toolCalls.isNotEmpty()) msg.put("tool_calls", JSONArray().also { a ->
            toolCalls.forEachIndexed { i, (name, args) ->
                a.put(JSONObject().put("id", "call_$i").put("type", "function")
                    .put("function", JSONObject().put("name", name).put("arguments", args.toString())))
            }
        })
        return MockResponse().setBody(JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", msg)))
            .put("usage", JSONObject().put("prompt_tokens", 100).put("completion_tokens", 10)).toString())
    }

    private data class Req(val model: String, val system: String, val tools: List<String>, val messages: JSONArray, val raw: String)

    private fun take(): Req {
        val raw = server.takeRequest().body.readUtf8()
        val b = JSONObject(raw)
        val msgs = b.getJSONArray("messages")
        val tools = b.optJSONArray("tools")?.let { t -> (0 until t.length()).map { t.getJSONObject(it).getJSONObject("function").getString("name") } }.orEmpty()
        return Req(b.getString("model"), msgs.getJSONObject(0).get("content").toString(), tools, msgs, raw)
    }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        settings = Settings(ApplicationProvider.getApplicationContext())
        settings.deepseekKey = "k"
        settings.geminiKey = ""
        settings.openrouterKey = ""
        settings.deepseekBaseUrl = server.url("/").toString().trimEnd('/')
        settings.provider = Provider.DEEPSEEK
        settings.architectMode = true
        settings.smartRouting = false
        settings.architectModel = "deepseek/arch"
        settings.editorModel = "deepseek/edit"
        settings.escalationModel = "none"
        settings.defaultRepo = ""
        settings.orchestratorMode = "OFF"
        settings.budgetUsd = 0.0
        settings.maxExecutorRetries = 5
        settings.autoApprove = true
    }

    @After fun tearDown() { server.shutdown() }

    // 1. The architect cannot reach write or run tools.

    @Test fun architectHasNoWriteToolsAndTheDispatcherRejectsThem() = runBlocking {
        val agent = Agent(settings)
        val offered = agent.tools.singleShotSpecs.map { it.name }.toSet()
        assertTrue(offered.none { it in ToolRegistry.WRITE_TOOLS })
        assertEquals(setOf("submit_plan", "need_more_info", "delegate_to_executor"), offered)
        for (w in ToolRegistry.WRITE_TOOLS) assertNotNull(w, ToolRegistry.architectDenied(w, null))
        assertNotNull(ToolRegistry.architectDenied("github_api", "POST"))
        assertNull(ToolRegistry.architectDenied("github_read_file", null))
        assertNull(ToolRegistry.architectDenied("github_api", "GET"))
        // Dispatcher: even if a caller passes full access, the architect role is refused.
        for (role in listOf("architect", "planner")) {
            val e = runCatching {
                agent.tools.execute(ToolCall("x", "github_write_files", JSONObject().put("repo", "me/app")), readOnly = false, allowOps = true, role = role)
            }.exceptionOrNull()
            assertTrue(e?.message.orEmpty().contains("salt okunur"))
        }
    }

    @Test fun aWriteCallFromTheArchitectIsNeverExecuted() = runBlocking {
        val agent = Agent(settings)
        server.enqueue(reply("", listOf("github_apply_diff" to JSONObject().put("repo", "me/app").put("diff", "x"))))
        server.enqueue(reply("Tamam, değişiklik gerekmiyor."))
        val history = mutableListOf<ChatMessage>(ChatMessage.User("Bir şey yap"))
        agent.run(history, ui)
        val result = history.filterIsInstance<ChatMessage.ToolResult>().single { it.name == "github_apply_diff" }
        assertTrue(result.isError)
        assertEquals(2, server.requestCount) // nothing but the two architect calls
    }

    // 2 & 3. Executor self-repair, never the architect.

    private val bad = "A.kt\n<<<<<<< SEARCH\nfun b() = 2\n=======\nfun b() = 3\n>>>>>>> REPLACE"
    private val good = "A.kt\n<<<<<<< SEARCH\nfun a() = 1\n=======\nfun a() = 2\n>>>>>>> REPLACE"

    private suspend fun edit(agent: Agent): Pair<ArchitectEditor.Outcome, Map<String, String?>> {
        val originals = hashMapOf<String, String?>("A.kt" to "fun a() = 1\n")
        val state = hashMapOf<String, String?>("A.kt" to "fun a() = 1\n")
        val o = ArchitectEditor(agent).editFiles(mutableListOf(), ui, "Commit: x\n## A.kt\nchange a to 2",
            listOf(PlanFile("A.kt", "modify", "a() 2 döndürsün")), "me/app", "feat/x", null, originals, state, StringBuilder())
        return o to state
    }

    @Test fun executorFixesItselfOnTheThirdTryWithoutTheArchitect() = runBlocking {
        val agent = Agent(settings)
        server.enqueue(reply(bad)); server.enqueue(reply(bad)); server.enqueue(reply(good))
        val (o, state) = edit(agent)
        assertTrue(o.ok)
        assertEquals("fun a() = 2\n", state["A.kt"])
        val reqs = (1..3).map { take() }
        assertTrue(reqs.all { it.model == "edit" })
        // Repair turns are appended to the executor's own conversation, with the file re-read.
        assertTrue(reqs[1].raw.contains("CURRENT A.kt"))
        assertEquals(reqs[0].messages.getJSONObject(1).toString(), reqs[2].messages.getJSONObject(1).toString())
        assertEquals(3, server.requestCount)
    }

    @Test fun afterMaxRetriesTheRunPausesForTheUserAndTheArchitectIsNotCalled() = runBlocking {
        settings.maxExecutorRetries = 3
        val agent = Agent(settings)
        repeat(3) { server.enqueue(reply(bad)) }
        val (o, _) = edit(agent)
        assertFalse(o.ok)
        assertTrue(o.repairable)
        assertEquals(3, server.requestCount)
        assertTrue((1..3).map { take() }.all { it.model == "edit" })
        val card = OrchestratorTools.repairPlan("me/app", "feat/x", null, "m", "plan", listOf(PlanFile("A.kt", "modify", "x")), o.text)
        assertEquals(PlanState.PAUSED, card.state)
        assertTrue(card.repair)
        assertEquals(TaskStatus.FAILED, card.tasks.single().status)
    }

    @Test fun theLadderNeverContainsTheArchitect() {
        val editor = ModelRef.parse("deepseek/edit")!!
        val esc = ModelRef.parse("deepseek/mid")!!
        val ladder = ArchitectEditor.executorLadder(5, editor, esc)
        assertEquals(5, ladder.size)
        assertEquals(listOf("editor", "editor", "editor", "escalation", "escalation"), ladder.map { it.role })
        assertTrue(ArchitectEditor.executorLadder(5, editor, null).all { it.ref == editor })
    }

    // 4. The router sends a simple task straight to the executor.

    @Test fun simpleTaskSkipsTheArchitect() = runBlocking {
        settings.smartRouting = true
        settings.useDecision1 = false
        val agent = Agent(settings)
        server.enqueue(reply("Düzelttim."))
        agent.run(mutableListOf(ChatMessage.User("Giriş ekranındaki null hatayı düzelt")), ui)
        val r = take()
        assertEquals("edit", r.model)
        assertTrue("github_apply_diff" in r.tools)
        assertFalse("submit_plan" in r.tools)
        assertEquals(1, server.requestCount)
    }

    // 5. The prefix stays identical across calls; the architect is called at most twice.

    @Test fun architectPrefixIsStaticAndCallsAreCappedAtTwo() = runBlocking {
        val agent = Agent(settings)
        server.enqueue(reply("", listOf("need_more_info" to JSONObject().put("reason", "dosya lazım"))))
        server.enqueue(reply("", listOf("need_more_info" to JSONObject().put("reason", "yine"))))
        val history = mutableListOf<ChatMessage>(ChatMessage.User("Yeni özellik ekle: skor tablosu"))
        agent.run(history, ui)
        assertEquals(2, server.requestCount)
        val a = take(); val b = take()
        assertEquals(a.system, b.system)
        assertEquals(a.tools, b.tools)
        assertTrue(a.system.contains("read-only planner"))
        // Append-only: every message of the first request is unchanged at the start of the second.
        for (i in 0 until a.messages.length()) assertEquals(a.messages.getJSONObject(i).toString(), b.messages.getJSONObject(i).toString())
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}|\\d{2}:\\d{2}:\\d{2}").find(a.system) == null) // no date/time in the prefix

        // A new turn reuses exactly the same system prompt and tools.
        server.enqueue(reply("Bu bir soru, cevap 42."))
        history += ChatMessage.User("Peki skor nerede tutuluyor?")
        agent.run(history, ui)
        val c = take()
        assertEquals(a.system, c.system)
        assertEquals(a.tools, c.tools)
        assertEquals(a.messages.getJSONObject(1).toString(), c.messages.getJSONObject(1).toString())
    }

    @Test fun delegationRunsTheExecutorAndNotTheArchitectAgain() = runBlocking {
        val agent = Agent(settings)
        server.enqueue(reply("", listOf("delegate_to_executor" to JSONObject().put("instructions", "PR #3'ü merge et"))))
        server.enqueue(reply("Merge edildi."))
        agent.run(mutableListOf(ChatMessage.User("PR 3'ü birleştirir misin")), ui)
        val first = take(); val second = take()
        assertEquals("arch", first.model)
        assertEquals("edit", second.model)
        assertTrue("github_merge_pull_request" in second.tools)
        assertEquals(2, server.requestCount)
    }

    @Test fun modelChoicesStickAndTakeEffectImmediately() {
        val vm = com.suarez.coder.ui.ChatViewModel(ApplicationProvider.getApplicationContext())
        vm.settings.architectModel = "gemini/gemini-4-neon"
        vm.settings.editorModel = "gemini/my-custom-flash"
        vm.selectArchitect(ModelRef.parse("gemini/gemini-4-hydrogen")!!)
        assertEquals("gemini/gemini-4-hydrogen", vm.agent.architectRef().key)
        // A list refresh without the chosen models must not replace them.
        vm.mergeCatalog(Provider.GEMINI, listOf("gemini-4-neon", "gemini-3.8-flash"))
        assertEquals("gemini/gemini-4-hydrogen", vm.settings.architectModel)
        assertEquals("gemini/my-custom-flash", vm.settings.editorModel)
        assertTrue(vm.settings.modelCatalog.contains("gemini/gemini-3.8-flash"))
        // No silent "medium architect" by default.
        assertEquals("", Settings(ApplicationProvider.getApplicationContext()).architectModelMedium)
    }

    // 6. The architect model is locked: experiments never target or replace it.

    @Test fun architectExperimentsAreRejectedAndTheLockHolds() {
        val before = settings.architectModel
        // Generation never proposes an architect candidate.
        assertTrue(runCatching { Experiment.model("architect", "deepseek/arch-2", "test") }.exceptionOrNull() is IllegalArgumentException)
        // A hand-built architect experiment is recognised and is never adopted automatically.
        val arch = Experiment(kind = Experiment.Kind.MODEL, title = "Mimar → arch-2",
            payload = org.json.JSONObject().put("role", "architect").put("model", "deepseek/arch-2"))
        assertTrue(arch.isArchitectExperiment())
        assertTrue(!WeeklyCycle.autoAdoptable(arch))
        // Positive control: an editor experiment is adoptable.
        assertTrue(WeeklyCycle.autoAdoptable(Experiment.model("editor", "deepseek/edit-2", "test")))
        // The lock holds: nothing touched the architect model.
        assertEquals(before, settings.architectModel)
    }

    @Test fun oscillationBreakerAndPrePushGateEscalationsKeepLockedArchitectModel() {
        val agent = Agent(settings)
        val lockedArchitect = settings.architectModel
        assertEquals(lockedArchitect, agent.architectRef().key)

        val breaker = OscillationBreaker()
        repeat(OscillationBreaker.DEFAULT_THRESHOLD) {
            breaker.recordFailure("A.kt", "SEARCH bloğu bulunamadı")
        }
        assertTrue(breaker.isTripped("A.kt"))
        assertEquals(lockedArchitect, settings.architectModel)
        assertEquals(lockedArchitect, agent.architectRef().key)

        val gateVerdict = PrePushGate.evaluate(
            PrePushGate.Input(
                changedFiles = listOf("A.kt", "B.kt", "C.kt", "D.kt", "E.kt", "F.kt"),
                totalDiffLines = 600,
                hasArchitectReview = false,
                oscillationTripped = true
            )
        )
        assertEquals(PrePushGate.Action.REQUIRE_ARCHITECT_REVIEW, gateVerdict.action)
        assertEquals(lockedArchitect, settings.architectModel)
        assertEquals(lockedArchitect, agent.architectRef().key)
    }

    @Test fun escalationNeverSwapsTheLockedArchitectForARepairModel() {
        val locked = settings.architectModel
        val breaker = OscillationBreaker()
        repeat(OscillationBreaker.DEFAULT_THRESHOLD) { breaker.recordFailure("A.kt", "SEARCH bloğu bulunamadı") }
        assertTrue(breaker.isTripped("A.kt"))
        val agent = Agent(settings)
        assertEquals("deepseek/arch", locked)
        assertEquals(locked, agent.architectRef().key)
        assertEquals("deepseek/arch", settings.architectModel)
    }
}
