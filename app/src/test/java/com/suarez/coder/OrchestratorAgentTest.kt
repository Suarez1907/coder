package com.suarez.coder

import androidx.test.core.app.ApplicationProvider
import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.data.Provider
import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.Usage
import com.suarez.coder.orchestrator.OrchestratorHooks
import com.suarez.coder.orchestrator.OrchestratorMode
import com.suarez.coder.orchestrator.OrchestratorPlan
import com.suarez.coder.orchestrator.PlanState
import com.suarez.coder.orchestrator.SequentialOrchestrator
import com.suarez.coder.orchestrator.SubTask
import com.suarez.coder.orchestrator.TaskFile
import com.suarez.coder.orchestrator.TaskStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the real Agent against a mock OpenAI-compatible server (DeepSeek base URL). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrchestratorAgentTest {
    private lateinit var server: MockWebServer
    private lateinit var settings: Settings
    private val saved = ArrayList<OrchestratorPlan>()
    private var pause = false

    private val hooks = object : OrchestratorHooks {
        override fun save(plan: OrchestratorPlan) { saved += plan }
        override fun pauseRequested() = pause
        override fun clearPause() { pause = false }
    }
    private val ui = object : AgentUi {
        override fun update() {}
        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String) = false
    }

    private fun textReply(t: String) = MockResponse().setBody(JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", t))))
        .put("usage", JSONObject().put("prompt_tokens", 10).put("completion_tokens", 2)).toString())

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        settings = Settings(ApplicationProvider.getApplicationContext())
        settings.deepseekKey = "k"
        settings.deepseekBaseUrl = server.url("/").toString().trimEnd('/')
        settings.provider = Provider.DEEPSEEK
        settings.architectMode = true
        settings.smartRouting = false
        settings.architectModel = "deepseek/arch"
        settings.editorModel = "deepseek/edit"
        settings.defaultRepo = ""
        settings.budgetUsd = 1.0
    }

    @After fun tearDown() { server.shutdown() }

    private fun requestTools(): Set<String> {
        val body = JSONObject(server.takeRequest().body.readUtf8())
        val tools = body.optJSONArray("tools") ?: return emptySet()
        return (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
    }

    @Test fun offModeIsDefaultAndKeepsTheNormalArchitectFlow() = runBlocking {
        assertEquals("OFF", settings.orchestratorMode)
        val agent = Agent(settings).also { it.orchestratorHooks = hooks }
        server.enqueue(textReply("Bu bir soru; cevap: 42."))
        val history = mutableListOf<ChatMessage>(ChatMessage.User("Projede ne var?"))
        agent.run(history, ui)
        val tools = requestTools()
        assertTrue("submit_plan" in tools)
        assertFalse("submit_task_plan" in tools)
        assertTrue(saved.isEmpty()) // no plan, no extra calls
        assertEquals(1, server.requestCount)
    }

    @Test fun orchestratorModeAsksThePlannerForATaskList() = runBlocking {
        settings.orchestratorMode = OrchestratorMode.LIGHT_SEQUENTIAL.name
        val agent = Agent(settings).also { it.orchestratorHooks = hooks }
        server.enqueue(textReply("Plan yapamadım."))
        agent.run(mutableListOf(ChatMessage.User("Skor tablosu ekle")), ui)
        val tools = requestTools()
        assertTrue("submit_task_plan" in tools)
        assertFalse("submit_plan" in tools)
        settings.orchestratorMode = "OFF"
    }

    private fun lightPlan() = OrchestratorPlan(
        mode = OrchestratorMode.LIGHT_SEQUENTIAL, request = "r", repo = "me/app", branch = "feat/x", base = null, commitMessage = "m",
        tasks = listOf(
            SubTask("t1", "Bir", "d", listOf(TaskFile("A.kt", "modify", "x")), status = TaskStatus.DONE, handoff = "Bir: A.kt +1 −0"),
            SubTask("t2", "İki", "d", listOf(TaskFile("B.kt", "modify", "x"))),
        ),
        state = PlanState.PAUSED,
    )

    @Test fun pauseStopsAtTheNextSubtaskBoundaryWithoutCalls() = runBlocking {
        val agent = Agent(settings)
        pause = true
        val result = SequentialOrchestrator(agent, hooks).run(mutableListOf(), ui, lightPlan())
        assertEquals(PlanState.PAUSED, result.state)
        assertEquals(listOf(TaskStatus.DONE, TaskStatus.PENDING), result.tasks.map { it.status })
        assertEquals(0, server.requestCount)
        assertEquals(PlanState.PAUSED, saved.last().state)
    }

    @Test fun budgetPausesBetweenSubtasksInsteadOfAskingMidTask() = runBlocking {
        val agent = Agent(settings)
        val history = mutableListOf<ChatMessage>(ChatMessage.UsageRecord("deepseek/arch", "architect", Usage(input = 10), actualUsd = 1.5))
        val result = SequentialOrchestrator(agent, hooks).run(history, ui, lightPlan())
        assertEquals(PlanState.PAUSED, result.state)
        assertTrue(result.note!!.contains("Bütçe"))
        assertEquals(TaskStatus.PENDING, result.task("t2")!!.status)
        assertEquals(0, server.requestCount)
        // Resuming approves one more budget step.
        assertTrue(agent.budgetExceeded(history))
        agent.approveBudget(history)
        assertFalse(agent.budgetExceeded(history))
    }
}
