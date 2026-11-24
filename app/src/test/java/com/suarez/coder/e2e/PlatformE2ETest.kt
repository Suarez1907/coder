package com.suarez.coder.e2e

import androidx.test.core.app.ApplicationProvider
import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.agent.Span
import com.suarez.coder.agent.TimelineReport
import com.suarez.coder.data.Provider
import com.suarez.coder.data.Settings
import com.suarez.coder.e2e.FakeWorld.Companion.diff
import com.suarez.coder.e2e.FakeWorld.Companion.reply
import com.suarez.coder.e2e.FakeWorld.Companion.toolResults
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.OpenAiCompatClient
import com.suarez.coder.security.RegistryChecker
import com.suarez.coder.tools.GitHub
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

/** GitHub layer (GraphQL, ETag), flaky CI, supply-chain checks and the timeline, end to end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformE2ETest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var world: FakeWorld
    private lateinit var settings: Settings
    private val asked = ArrayList<String>()
    private val ui = object : AgentUi {
        override fun update() {}
        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String): Boolean { asked += title; return false }
    }
    private val saved = GitHub.API

    @Before fun setUp() {
        world = FakeWorld(files = mapOf("a.kt" to "val a = 1\n", "b.kt" to "val b = 2\n", "c.kt" to "val c = 3\n",
            "requirements.txt" to "flask==3.0.0\n")).start()
        GitHub.API = world.url(); GitHub.ReadCache.clear(); GitHub.ETags.clear()
        OpenAiCompatClient.BACKOFF_MS = emptyList(); ToolRegistry.CI_POLL_MS = 10
        settings = Settings(ApplicationProvider.getApplicationContext()).apply {
            githubToken = "t"; deepseekKey = "k"; geminiKey = ""; openrouterKey = ""; deepseekBaseUrl = world.url()
            provider = Provider.DEEPSEEK; setModelFor(Provider.DEEPSEEK, "m1"); editorModel = "deepseek/m1"; architectModel = "deepseek/m1"
            architectMode = false; smartRouting = false; escalationModel = "none"; escalationChain = ""; defaultRepo = "me/app"
            contextRepo = ""; contextRef = ""; contextFiles = emptyList(); orchestratorMode = "OFF"; budgetUsd = 0.0; autoApprove = true
            projectNotes = false; docsLookup = false; semanticSearch = false; selfReview = false; quickCheck = false; activeMode = ""
            flakyRetry = true; supplyChainCheck = true; untilGreen = false; pipelining = true
        }
    }

    @After fun tearDown() {
        world.shutdown(); GitHub.API = saved; GitHub.ReadCache.clear(); GitHub.ETags.clear()
        OpenAiCompatClient.BACKOFF_MS = listOf(2_000L, 5_000L, 10_000L); ToolRegistry.CI_POLL_MS = 15_000L
        RegistryChecker.NPM = "https://registry.npmjs.org"; RegistryChecker.PYPI = "https://pypi.org"; RegistryChecker.OSV = "https://api.osv.dev"
        world.errors.firstOrNull()?.let { throw AssertionError(it.message, it) }
    }

    private fun agent() = Agent(settings, tmp.root)
    private fun args(vararg kv: Pair<String, Any>) = JSONObject().also { o -> kv.forEach { (k, v) -> o.put(k, v) } }

    @Test fun manyFilesAreReadInOneGraphqlRequestAndThenServedFromCache() = runBlocking {
        val gh = agent().tools.github
        val got = gh.readFilesBatch("me/app", null, listOf("a.kt", "b.kt", "c.kt", "missing.kt"))
        assertEquals("val b = 2\n", got["b.kt"]); assertEquals(null, got["missing.kt"])
        assertEquals(1, world.counts["graphql"]); assertEquals(null, world.counts["contents"])
        assertEquals("val c = 3\n", gh.readFileRaw("me/app", "c.kt", null)) // cache hit, no request
        assertEquals(null, world.counts["contents"])
    }

    @Test fun unchangedContentComesBackAs304() = runBlocking {
        val gh = agent().tools.github
        gh.readFileRaw("me/app", "a.kt", null)
        GitHub.ReadCache.clear() // the TTL cache is gone; the ETag still lets GitHub answer "not modified"
        assertEquals("val a = 1\n", gh.readFileRaw("me/app", "a.kt", null))
        assertEquals(1, world.counts["notModified"])
    }

    @Test fun aTransientCiFailureIsRerunWithoutAModelCallAndTheTestIsMarkedFlaky() {
        world.ci = { "failure" to "ScoreTest > savesScore FAILED\njava.net.SocketException: Connection reset\n" }
        world.ciRerun = { "success" to "" }
        world.script += { reply("", "github_apply_diff" to args("repo" to "me/app", "branch" to "f", "message" to "m", "diff" to diff("a.kt", "val a = 1", "val a = 10"))) }
        world.script += { reply("", "ci_wait" to args("repo" to "me/app", "ref" to "f")) }
        world.script += { req -> assertTrue(toolResults(req).last().contains("yeniden çalıştırılınca geçti")); reply("Yeşil.") }
        val a = agent()
        runBlocking { a.run(mutableListOf(ChatMessage.User("a'yı 10 yap")), ui) }
        assertEquals(1, world.reruns)
        assertTrue(a.tools.flaky!!.known("me/app").contains("ScoreTest.savesScore"))
        assertEquals(3, world.llmRequests.size) // no model call was spent on the flake
    }

    @Test fun aRealFailureIsNotRerun() {
        world.ci = { "failure" to "e: a.kt:1:9 Type mismatch\n" }
        world.script += { reply("", "github_apply_diff" to args("repo" to "me/app", "branch" to "g", "message" to "m", "diff" to diff("a.kt", "val a = 1", "val a = \"x\""))) }
        world.script += { reply("", "ci_wait" to args("repo" to "me/app", "ref" to "g")) }
        world.script += { reply("Kırmızı.") }
        runBlocking { agent().run(mutableListOf(ChatMessage.User("x")), ui) }
        assertEquals(0, world.reruns)
    }

    @Test fun aHallucinatedPackageAndASecretNeverReachTheRepo() {
        val registry = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when {
                    request.path!!.startsWith("/pypi/reqeusts") -> MockResponse().setResponseCode(404)
                    request.path!!.startsWith("/v1/query") -> MockResponse().setBody("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        RegistryChecker.PYPI = registry.url("/").toString().trimEnd('/'); RegistryChecker.OSV = registry.url("/").toString().trimEnd('/')
        try {
            world.script += { reply("", "github_apply_diff" to args("repo" to "me/app", "branch" to "dep", "message" to "m",
                "diff" to diff("requirements.txt", "flask==3.0.0", "flask==3.0.0\nreqeusts==2.31.0"))) }
            world.script += { req ->
                assertTrue(toolResults(req).last().contains("kayıt defterinde YOK")); assertTrue(toolResults(req).last().contains("requests"))
                reply("", "github_apply_diff" to args("repo" to "me/app", "branch" to "dep", "message" to "m",
                    "diff" to diff("a.kt", "val a = 1", "val a = 1\nval key = \"ghp_" + "a".repeat(36) + "\"")))
            }
            world.script += { req -> assertTrue(toolResults(req).last().contains("gizli anahtar")); reply("Durdum.") }
            runBlocking { agent().run(mutableListOf(ChatMessage.User("bağımlılık ekle")), ui) }
            assertEquals(listOf("Şüpheli bağımlılık", "Sır gibi görünen değer"), asked)
            assertFalse(world.branches.containsKey("dep")) // nothing was committed
        } finally { registry.shutdown() }
    }

    @Test fun theTimelineRecordsModelCallsToolsAndCiWaits() {
        world.ci = { "success" to "" }
        world.script += { reply("", "github_read_file" to args("repo" to "me/app", "path" to "a.kt")) }
        world.script += { reply("", "github_apply_diff" to args("repo" to "me/app", "branch" to "t", "message" to "m", "diff" to diff("a.kt", "val a = 1", "val a = 2"))) }
        world.script += { reply("", "ci_wait" to args("repo" to "me/app", "ref" to "t")) }
        world.script += { reply("Bitti.") }
        val spans = ArrayList<Span>()
        val a = agent().apply { onSpan = { spans += it } }
        runBlocking { a.run(mutableListOf(ChatMessage.User("a=2")), ui) }
        assertEquals(4, spans.count { it.kind == Span.MODEL })
        assertTrue(spans.any { it.kind == Span.CI && it.name == "ci_wait" })
        assertTrue(spans.any { it.kind == Span.TOOL && it.name == "github_apply_diff" })
        val totals = TimelineReport.totals(spans).associateBy { it.kind }
        assertEquals(4, totals.getValue(Span.MODEL).count)
    }
}
