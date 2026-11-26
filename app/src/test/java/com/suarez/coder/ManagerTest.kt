package com.suarez.coder

import androidx.test.core.app.ApplicationProvider
import com.suarez.coder.data.Settings
import com.suarez.coder.evolution.EvolutionStore
import com.suarez.coder.evolution.ExamCase
import com.suarez.coder.evolution.ExamCases
import com.suarez.coder.evolution.ExamRunner
import com.suarez.coder.evolution.ExamScore
import com.suarez.coder.evolution.Experiment
import com.suarez.coder.evolution.ExperimentApplier
import com.suarez.coder.evolution.GoalPlan
import com.suarez.coder.evolution.ModelBandit
import com.suarez.coder.evolution.Outcome
import com.suarez.coder.evolution.PendingSelfPr
import com.suarez.coder.evolution.SelfManager
import com.suarez.coder.evolution.SelfPrActions
import com.suarez.coder.evolution.SessionTrace
import com.suarez.coder.evolution.SkillScout
import com.suarez.coder.evolution.WeeklyCycle
import com.suarez.coder.evolution.WeeklyReport
import com.suarez.coder.skills.SkillRepository
import com.suarez.coder.tools.GitHub
import com.suarez.coder.tools.ToolException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

private fun trace(id: String, at: Long = 1_000, rating: Int = 0, ciFail: Int = 0, diffErr: Int = 0, stopped: Boolean = false,
                  repo: String = "me/app", base: String? = "abc123", ciRuns: Int = 1, route: String? = "SIMPLE_EDIT",
                  request: String = "Giriş ekranındaki hatayı düzelt $id", cost: Double = 0.01, arm: String? = null) =
    SessionTrace(id, "s", repo, at, request, listOf("m"), 2, 50, cost, diffErr, emptyList(), ciFail, emptyList(), emptyList(),
        "SIMPLE", false, stopped, rating = rating, ciRuns = ciRuns, baseSha = base, route = route, arm = arm)

class ManagerLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun outcomeUsesThumbsFirstThenSignals() {
        assertEquals(1.0, Outcome.of(trace("a", rating = 1, ciFail = 2)), 1e-9)
        assertEquals(0.0, Outcome.of(trace("b", rating = -1)), 1e-9)
        assertEquals(1.0, Outcome.of(trace("c")), 1e-9)
        assertEquals(0.0, Outcome.of(trace("d", stopped = true)), 1e-9)
        assertEquals(0.45, Outcome.of(trace("e", ciFail = 1, diffErr = 1)), 1e-9)
    }

    @Test fun banditLearnsTheBetterModelForTheMoney() {
        val b = ModelBandit(tmp.newFile("b.json"), Random(7))
        // "strong" succeeds 90% at $0.05, "cheap" 85% at $0.005, "bad" 30% at $0.001.
        val truth = mapOf("x/strong" to (0.9 to 0.05), "x/cheap" to (0.85 to 0.005), "x/bad" to (0.3 to 0.001))
        val rnd = Random(1)
        val picks = HashMap<String, Int>()
        repeat(300) { i ->
            val m = b.choose("SIMPLE_EDIT", "editor", truth.keys.toList(), costWeight = 0.5)!!
            if (i >= 200) picks.merge(m, 1, Int::plus)
            val (p, c) = truth.getValue(m)
            b.record("SIMPLE_EDIT", "editor", m, if (rnd.nextDouble() < p) 1.0 else 0.0, c)
        }
        assertTrue(picks.toString(), (picks["x/cheap"] ?: 0) > 60) // mostly the cheap-but-good one
        assertTrue(picks.toString(), (picks["x/bad"] ?: 0) < 15)
        assertEquals("x/cheap", b.best("SIMPLE_EDIT", "editor", 0.5))
        // Quality first: the strong model wins when cost does not matter.
        assertEquals("x/strong", b.best("SIMPLE_EDIT", "editor", 0.0))
    }

    @Test fun thumbsChangeAnAlreadyCountedRun() {
        val b = ModelBandit(tmp.newFile("b.json"))
        b.record("SIMPLE_EDIT", "editor", "x/a", 1.0, 0.01)
        b.adjust("SIMPLE_EDIT", "editor", "x/a", 1.0, 0.0)
        val arm = b.arms("SIMPLE_EDIT", "editor").getValue("x/a")
        assertEquals(1, arm.n)
        assertTrue(arm.mean < 0.5)
        assertEquals(listOf(Triple("SIMPLE_EDIT", "editor", "deepseek/x")), SelfManager.parseArms("SIMPLE_EDIT|editor|deepseek/x"))
        assertEquals(2, SelfManager.parseArms("COMPLEX_ARCHITECT|architect|a/b;COMPLEX_ARCHITECT|editor|c/d").size)
        assertTrue(SelfManager.parseArms(null).isEmpty())
    }

    @Test fun examQuestionsComeOnlyFromGoodTasksWithCiAndABase() {
        val traces = listOf(
            trace("ok1", at = 5), trace("ok2", at = 6, repo = "me/web"), trace("ok3", at = 7),
            trace("bad", rating = -1), trace("nobase", base = null), trace("noci", ciRuns = 0),
            trace("question", route = "QUESTION_ONLY"), trace("failed", ciFail = 2),
            trace("dup", at = 4, request = "Giriş ekranındaki hatayı düzelt ok1"),
        )
        val cases = ExamCases.from(traces)
        assertEquals(listOf("ok3", "ok2", "ok1"), cases.map { it.id })
        val picked = ExamCases.pick(cases, 2)
        assertEquals(setOf("me/app", "me/web"), picked.map { it.repo }.toSet()) // spread over repos
        // Questions that could not be scored are excluded; the duplicate of ok1 then becomes usable.
        assertEquals(listOf("dup"), ExamCases.from(traces, excluded = setOf("ok1", "ok2", "ok3")).map { it.id })
    }

    @Test fun experimentsWinOnlyWhenNotWorse() {
        assertTrue(Experiment.wins(ExamScore(2, 3, 0.30), ExamScore(3, 3, 0.50)))
        assertTrue(Experiment.wins(ExamScore(2, 3, 0.30), ExamScore(2, 3, 0.20)))
        assertFalse(Experiment.wins(ExamScore(2, 3, 0.30), ExamScore(2, 3, 0.29))) // not cheaper enough
        assertFalse(Experiment.wins(ExamScore(3, 3, 0.30), ExamScore(2, 3, 0.05))) // cheaper but worse
        assertFalse(Experiment.wins(ExamScore(0, 3, 0.30), ExamScore(0, 3, 0.01))) // passing nothing never wins
    }

    @Test fun storeQueuesEachChangeOnceAndKeepsRules() {
        val store = EvolutionStore(tmp.newFolder())
        assertTrue(store.queue(Experiment.rule("Read the failing file before patching it", "x")))
        assertFalse(store.queue(Experiment.rule("read the failing file   before patching it", "y")))
        assertTrue(store.queue(Experiment.model("editor", "deepseek/deepseek-chat", "x")))
        val e = store.experiments().first()
        store.saveExperiment(e.copy(state = Experiment.State.ADOPTED, baseline = ExamScore(1, 2, 0.1), candidate = ExamScore(2, 2, 0.1)))
        assertEquals(Experiment.State.ADOPTED, store.experiments().first { it.id == e.id }.state)
        assertEquals(ExamScore(2, 2, 0.1), store.experiments().first { it.id == e.id }.candidate)
        store.setPolicyRules(listOf("a rule", "a rule", " "))
        assertEquals(listOf("a rule"), store.policyRules())
        store.saveBaseline("cfg", "case1", ExamScore(1, 1, 0.02))
        assertEquals(ExamScore(1, 1, 0.02), store.baseline("cfg", "case1"))
        assertNull(store.baseline("other", "case1"))
    }

    @Test fun examWritesAreLockedToTheScratchBranch() {
        val guard = ExamRunner.guard("me/app", "coder-exam/x-1")
        assertNull(guard("me/app", "coder-exam/x-1"))
        assertNotNull(guard("me/app", "main"))
        assertNotNull(guard("me/other", "coder-exam/x-1"))
        assertNotNull(guard("me/app", null)) // PRs, merges, dispatches, API writes
        val gh = GitHub { "token" }.apply { writeGuard = guard }
        val e = runCatching { runBlocking { gh.commitFiles("me/app", "main", "m", listOf(GitHub.FileChange("a", "b", false)), null) } }.exceptionOrNull()
        assertTrue(e is ToolException)
        assertTrue(runCatching { runBlocking { gh.tryMerge("me/app", 1, "squash") } }.exceptionOrNull() is ToolException)
        assertTrue(runCatching { runBlocking { gh.raw("POST", "/repos/me/app/issues", "{}") } }.exceptionOrNull() is ToolException)
        assertFalse(runBlocking { gh.deleteBranch("me/app", "main") })
        val case = ExamCase("abcdefghij", "me/app", "sha", "Butonu düzelt", "SIMPLE_EDIT")
        val b = ExamRunner.branchFor(case, "model")
        assertTrue(b.startsWith("coder-exam/abcdefgh-model-"))
        assertTrue(ExamRunner.prompt(case, b).contains(b))
        assertTrue(ExamRunner.ciPassed("Commit x:\n- Build: success (url)\n"))
        assertFalse(ExamRunner.ciPassed("Commit x:\n- Build: failure\n\nHatalar (Build, run_id 1):\n..."))
        assertTrue(ExamRunner.ciMissing("Commit abc için başlayan bir workflow yok (2 dk beklendi)."))
    }

    @Test fun autoMergeNeedsEveryGateAndCrashSpikesAreDetected() {
        val pr = PendingSelfPr(5, "t", "u", "evo/x", "c", listOf("app/src/main/java/com/suarez/coder/diff/SearchReplace.kt"), true, 0.02, "x", 0)
        assertTrue(SelfPrActions.mayAutoMerge(3, pr, trustedMerges = 2, watching = false))
        assertFalse(SelfPrActions.mayAutoMerge(2, pr, 2, false))
        assertFalse(SelfPrActions.mayAutoMerge(3, pr, 1, false)) // not trusted yet
        assertFalse(SelfPrActions.mayAutoMerge(3, pr, 2, watching = true)) // previous update still on watch
        assertFalse(SelfPrActions.mayAutoMerge(3, pr.copy(ciPassed = false), 2, false))
        assertFalse(SelfPrActions.mayAutoMerge(3, pr.copy(files = listOf("a.kt", "b.kt", "c.kt", "d.kt")), 2, false))
        assertFalse(SelfPrActions.mayAutoMerge(3, pr.copy(files = listOf("app/src/main/java/com/suarez/coder/data/Settings.kt")), 2, false))

        val m = SelfPrActions.LastMerge(5, "t", "sha", at = 1_000, version = 40, auto = true)
        assertTrue(SelfPrActions.isSpike(m, listOf(2_000L to 41L, 3_000L to 41L)))
        assertFalse(SelfPrActions.isSpike(m, listOf(2_000L to 40L, 3_000L to 40L))) // old version still installed
        assertFalse(SelfPrActions.isSpike(m, listOf(2_000L to 41L)))
        assertFalse(SelfPrActions.isSpike(m.copy(reverted = true), listOf(2_000L to 41L, 3_000L to 41L)))
        assertFalse(SelfPrActions.isSpike(m, listOf(500L to 41L, 600L to 41L))) // before the merge
    }

    @Test fun goalsAreParsedAndClamped() {
        val available = listOf("gemini/gemini-4-hydrogen", "gemini/gemini-3.8-flash", "deepseek/deepseek-chat")
        val p = GoalPlan.parse("""{"summary":"Ucuzlat","cost_weight":1.7,"autonomy_level":9,"exam_weekly_budget":100,
            "model_pool":["gemini/gemini-3.8-flash","deepseek/deepseek-chat","openai/hayali-model"],"learned_routing":true,"scout":null}""", available)!!
        assertEquals(1.0, p.costWeight!!, 1e-9)
        assertEquals(3, p.autonomy)
        assertEquals(20.0, p.examBudget!!, 1e-9)
        assertEquals(listOf("gemini/gemini-3.8-flash", "deepseek/deepseek-chat"), p.pool) // unknown model dropped
        assertNull(p.scout)
        assertNull(GoalPlan.parse("JSON yok", available))
        assertNull(GoalPlan.parse("""{"model_pool":["deepseek/deepseek-chat"]}""", available)!!.pool) // a pool needs two
    }

    @Test fun scoutStackAndDefaultExperiment() {
        assertEquals(listOf("kotlin", "android", "compose", "javascript", "web", "frontend"), SkillScout.stackKeywords(listOf("Kotlin", "JavaScript", "HTML")))
        assertTrue(SkillScout.relevant("Compose performance audit", listOf("compose")))
        assertTrue(SkillScout.relevant("Systematic debugging and test review", emptyList()))
        assertFalse(SkillScout.relevant("Slack GIF creator", listOf("kotlin")))
        val price = mapOf("a/big" to 10.0, "a/mid" to 3.0, "a/small" to 1.0)
        assertEquals("a/small", WeeklyCycle.cheaperModel("a/big", price.keys.toList(), { price.getValue(it) }, emptySet()))
        assertEquals("a/mid", WeeklyCycle.cheaperModel("a/big", price.keys.toList(), { price.getValue(it) }, setOf("a/small")))
        assertNull(WeeklyCycle.cheaperModel("a/small", price.keys.toList(), { price.getValue(it) }, emptySet()))
    }

    @Test fun weeklyReportCounts() {
        val now = 10L * 24 * 3_600_000
        val traces = listOf(trace("a", at = now - 1000, rating = 1, cost = 0.02), trace("b", at = now - 2000, stopped = true, cost = 0.04),
            trace("old", at = 1))
        val r = WeeklyReport.compute(traces, emptyList(), emptyList(), crashes = 1, bestModels = "simple/uyg: flash", now = now)
        assertEquals(2, r.tasks)
        assertEquals(0.5, r.successRate!!, 1e-9)
        assertEquals(0.06, r.totalCost, 1e-9)
        assertEquals(1, r.thumbsUp)
        assertTrue(r.lines().any { it.contains("Çökme: 1") })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManagerSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun poolPickAndApplyUndo() {
        val s = Settings(ApplicationProvider.getApplicationContext())
        s.geminiKey = "k"; s.deepseekKey = ""
        s.learnedRouting = true
        s.modelPool = listOf("gemini/a")
        val manager = SelfManager(s, EvolutionStore(tmp.newFolder()))
        assertTrue(manager.pick("SIMPLE_EDIT") == null) // fewer than two usable models: the user's fixed choice
        s.modelPool = listOf("gemini/a", "deepseek/b") // deepseek has no key
        assertTrue(manager.pick("SIMPLE_EDIT") == null)
        s.modelPool = listOf("gemini/a", "gemini/b")
        assertEquals(setOf("editor"), manager.pick("SIMPLE_EDIT")!!.models.keys)
        assertEquals(setOf("architect", "editor"), manager.pick("COMPLEX_ARCHITECT")!!.models.keys)
        // With a task context the contextual chooser answers and records its propensities.
        val ctx = com.suarez.coder.evolution.RouteContext.features(1.0, 2, doubleArrayOf(1.0, 0.0, 0.0, 0.0))
        val p = manager.pick("COMPLEX_ARCHITECT", ctx)!!
        assertTrue(p.prop!!.contains("architect=") && p.prop!!.contains("editor="))

        // Adopted experiments can be undone from the log.
        val store = EvolutionStore(tmp.newFolder())
        val skills = SkillRepository(tmp.newFolder(), { null }, { _, _ -> null })
        val applier = ExperimentApplier(s, store, skills)
        s.editorModel = "gemini/a"
        applier.apply(Experiment.model("editor", "gemini/b", "t"))
        assertEquals("gemini/b", s.editorModel)
        assertTrue(applier.undo(store.log().last().undo!!))
        assertEquals("gemini/a", s.editorModel)
        applier.apply(Experiment.rule("Read the failing file before patching it", "t"))
        assertEquals(listOf("Read the failing file before patching it"), store.policyRules())
        applier.undo(store.log().last().undo!!)
        assertTrue(store.policyRules().isEmpty())
        // The policy text reaches every system prompt only through the manager.
        store.setPolicyRules(listOf("Run ci_wait after every push"))
        assertEquals("- Run ci_wait after every push", SelfManager(s, store).policyText())
        s.modelPool = emptyList()
    }
}
