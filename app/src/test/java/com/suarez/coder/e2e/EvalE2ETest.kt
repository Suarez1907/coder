package com.suarez.coder.e2e

import androidx.test.core.app.ApplicationProvider
import com.suarez.coder.data.Provider
import com.suarez.coder.data.Settings
import com.suarez.coder.e2e.FakeWorld.Companion.diff
import com.suarez.coder.e2e.FakeWorld.Companion.reply
import com.suarez.coder.evolution.EvalRun
import com.suarez.coder.evolution.EvalSuite
import com.suarez.coder.evolution.EvolutionEngine
import com.suarez.coder.evolution.ExamCase
import com.suarez.coder.evolution.Stats
import com.suarez.coder.llm.OpenAiCompatClient
import com.suarez.coder.tools.GitHub
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EvalE2ETest {
    private val saved = GitHub.API
    private lateinit var world: FakeWorld

    @After fun tearDown() {
        if (!::world.isInitialized) return
        world.shutdown(); GitHub.API = saved; GitHub.ReadCache.clear()
        OpenAiCompatClient.BACKOFF_MS = listOf(2_000L, 5_000L, 10_000L); ToolRegistry.CI_POLL_MS = 15_000L
        world.errors.firstOrNull()?.let { throw AssertionError(it.message, it) }
    }

    @Test fun theFixedSetIsSolvedOnThrowawayBranchesAndMeasuredWithCi() = runBlocking {
        world = FakeWorld(files = mapOf("a.txt" to "bir\n", "b.txt" to "iki\n")).start()
        GitHub.API = world.url(); GitHub.ReadCache.clear()
        OpenAiCompatClient.BACKOFF_MS = emptyList(); ToolRegistry.CI_POLL_MS = 10
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val settings = Settings(ctx).apply {
            githubToken = "t"; deepseekKey = "k"; geminiKey = ""; openrouterKey = ""; deepseekBaseUrl = world.url()
            provider = Provider.DEEPSEEK; setModelFor(Provider.DEEPSEEK, "m1"); editorModel = "deepseek/m1"; architectModel = "deepseek/m1"
            architectMode = false; smartRouting = false; escalationChain = ""; escalationModel = "none"; defaultRepo = "me/app"
            orchestratorMode = "OFF"; budgetUsd = 0.0; autoApprove = true; selfReview = false; quickCheck = false; docsLookup = false
            semanticSearch = false; untilGreen = true; checkpoints = true; projectNotes = false; activeMode = ""; examWeeklyBudget = 1.0
        }
        world.ci = { files -> if (files.values.any { it.contains("YENİ") }) "success" to "" else "failure" to "e: test failed\n" }
        val base = world.branches.getValue("main")
        // Task 1 is solved (commit + green CI); task 2 commits nothing (fails).
        world.script += { r ->
            val branch = Regex("coder-exam/[\\w./-]+").find(FakeWorld.userTexts(r).joinToString())!!.value.trimEnd('.', '`')
            reply("", "github_apply_diff" to JSONObject().put("repo", "me/app").put("branch", branch).put("message", "m").put("diff", diff("a.txt", "bir", "YENİ")))
        }
        world.script += { reply("", "ci_wait" to JSONObject().put("repo", "me/app").put("ref", Regex("coder-exam/[\\w./-]+").find(FakeWorld.userTexts(it).joinToString())!!.value.trimEnd('.', '`'))) }
        world.script += { reply("Bitti.") }
        world.script += { reply("Yapamadım.") }
        val store = EvolutionEngine.storeFor(ctx)
        val suite = EvalSuite(ctx, settings, store)
        suite.evals.saveSuite(listOf(ExamCase("c1", "me/app", base, "a.txt'yi YENİ yap", "SIMPLE_EDIT"), ExamCase("c2", "me/app", base, "b.txt'yi değiştir", "SIMPLE_EDIT")))
        val msg = suite.run { 1.0 }
        val run = suite.evals.runs().last()
        assertEquals(listOf(true, false), run.results.map { it.pass })
        assertTrue(msg, msg.contains("1/2 geçti"))
        assertTrue(world.branches.keys.none { it.startsWith("coder-exam/") }) // scratch branches are cleaned up
        assertEquals("bir\n", world.file("main", "a.txt")) // the real branch is never touched
    }

    @Test fun statistics() {
        val (lo, hi) = Stats.wilson(8, 10)
        assertEquals(0.49, lo, 0.02); assertEquals(0.94, hi, 0.02)
        assertEquals(1.0, Stats.mcnemarP(0, 0), 1e-9)
        assertEquals(0.0625, Stats.mcnemarP(0, 5), 1e-6) // 5 of 5 discordant improved: p = 2 * 0.5^5
        assertTrue(Stats.mcnemarP(1, 9) < 0.05)
        val (m, mlo, mhi) = Stats.bootstrapMean(listOf(1.0, 2.0, 3.0, 4.0))
        assertEquals(2.5, m, 1e-9); assertTrue(mlo < m && m < mhi)
        val r = { v: String, ps: List<Boolean> -> EvalRun(0, v, "", ps.mapIndexed { i, p -> EvalRun.Result("c$i", p, 0.01, 60_000) }) }
        val old = r("1.0", List(10) { it < 3 }); val new = r("1.1", List(10) { true })
        assertTrue(EvalRun.compare(old, new).contains("ANLAMLI biçimde arttı"))
        assertTrue(EvalRun.compare(old, r("1.1", List(10) { it < 4 })).contains("anlamlı değil"))
    }
}
