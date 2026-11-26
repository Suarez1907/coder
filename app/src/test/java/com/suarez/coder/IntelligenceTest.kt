package com.suarez.coder

import com.suarez.coder.agent.Candidates
import com.suarez.coder.agent.ChangeRecord
import com.suarez.coder.agent.PlanUncertainty
import com.suarez.coder.agent.PromptSections
import com.suarez.coder.agent.RiskFeatures
import com.suarez.coder.agent.RiskModel
import com.suarez.coder.evolution.Experiment
import com.suarez.coder.evolution.LinearThompson
import com.suarez.coder.evolution.OffPolicy
import com.suarez.coder.evolution.ProcedureMiner
import com.suarez.coder.evolution.PromptEvolver
import com.suarez.coder.evolution.RouteContext
import com.suarez.coder.evolution.SessionTrace
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random

class IntelligenceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun riskModelLearnsWhatBreaksBuilds() {
        val r = Random(1)
        // Ground truth: build-file changes fail 70%, others 10%.
        val data = (0 until 120).map { i ->
            val build = r.nextBoolean()
            val paths = if (build) listOf("app/build.gradle.kts", "app/src/A.kt") else listOf("app/src/A.kt")
            val failed = r.nextDouble() < if (build) 0.7 else 0.1
            ChangeRecord("c$i", "me/app", "s$i", i.toLong(), RiskFeatures.of(paths, 0, 1.0, null), failed)
        }
        assertNull(RiskModel.train(data.take(10))) // too little data
        val m = RiskModel.train(data)!!
        val pBuild = m.pFail(RiskFeatures.of(listOf("build.gradle.kts", "A.kt"), 0, 1.0, null))
        val pCode = m.pFail(RiskFeatures.of(listOf("A.kt"), 0, 1.0, null))
        assertTrue("build $pBuild code $pCode", pBuild > 0.5 && pCode < 0.25)
        assertTrue(m.auc > 0.75)
        assertTrue(RiskModel.prior(RiskFeatures.of(listOf("build.gradle.kts"), 1, 3.0, null)) > RiskModel.prior(RiskFeatures.of(listOf("A.kt"), 0, 0.0, null)))
        assertEquals(0.5, RiskModel.auc(listOf(0.3 to true, 0.3 to false)), 1e-9)
    }

    @Test fun candidatesPreferPassingReviewedAndAgreed() {
        val ranked = Candidates.rank(listOf(
            Candidates.Scored(0, passes = false, reviewOk = null, votes = 0, size = 5),
            Candidates.Scored(1, passes = true, reviewOk = true, votes = 1, size = 3),
            Candidates.Scored(2, passes = true, reviewOk = true, votes = 2, size = 9),
            Candidates.Scored(3, passes = true, reviewOk = false, votes = 2, size = 1),
        ))
        assertEquals(listOf(2, 1, 3, 0), ranked.map { it.index })
        assertEquals(Candidates.fingerprint(mapOf("a" to "x = 1\n")), Candidates.fingerprint(mapOf("a" to "x  =  1")))
    }

    @Test fun planUncertainty() {
        assertEquals(setOf("a.kt", "b.kt"), PlanUncertainty.parse("```json\n{\"files\":[\"/a.kt\",\"b.kt\"]}\n```"))
        assertNull(PlanUncertainty.parse("bilmiyorum"))
        assertEquals(1.0, PlanUncertainty.agreement(listOf(setOf("a"), setOf("a"), setOf("a"))), 1e-9)
        assertTrue(PlanUncertainty.agreement(listOf(setOf("a"), setOf("b"), setOf("c"))) < 0.01)
        assertEquals(listOf("c.kt"), PlanUncertainty.missing(setOf("a.kt"), listOf(setOf("a.kt", "c.kt"), setOf("c.kt"))))
    }

    @Test fun contextualBanditLearnsWhichModelFitsWhichContext() {
        val b = LinearThompson(tmp.newFile("cb.json").also { it.delete() }, random = Random(3))
        val kotlin = RouteContext.features(1.0, 2, doubleArrayOf(1.0, 0.0, 0.0, 0.0))
        val web = RouteContext.features(1.0, 2, doubleArrayOf(0.0, 1.0, 0.0, 0.0))
        val r = Random(5)
        // Truth: model A succeeds on Kotlin (90%) and fails on web (20%); model B the opposite.
        repeat(300) {
            val x = if (r.nextBoolean()) kotlin else web
            val isKotlin = x === kotlin
            val c = b.choose("SIMPLE_EDIT|editor", listOf("A", "B"), x, penalty = { 0.0 }, draws = 50)
            val p = if ((c.model == "A") == isKotlin) 0.9 else 0.2
            b.record("SIMPLE_EDIT|editor|${c.model}", x, if (r.nextDouble() < p) 1.0 else 0.0)
            assertTrue(c.propensity in 0.0..1.0)
        }
        assertTrue(b.predict("SIMPLE_EDIT|editor", "A", kotlin) > b.predict("SIMPLE_EDIT|editor", "B", kotlin))
        assertTrue(b.predict("SIMPLE_EDIT|editor", "B", web) > b.predict("SIMPLE_EDIT|editor", "A", web))
        val k = (0 until 50).count { b.choose("SIMPLE_EDIT|editor", listOf("A", "B"), kotlin, penalty = { 0.0 }, draws = 20).model == "A" }
        assertTrue("A chosen $k/50 for Kotlin", k >= 40)
    }

    @Test fun offPolicyEvaluationRecoversTheBetterRule() {
        val r = Random(9)
        val kotlin = doubleArrayOf(1.0, 0.0); val web = doubleArrayOf(0.0, 1.0)
        // Logged with a uniform random rule (propensity 0.5); truth as above.
        val logs = (0 until 2_000).map {
            val isK = r.nextBoolean(); val m = if (r.nextBoolean()) "A" else "B"
            val p = if ((m == "A") == isK) 0.9 else 0.2
            OffPolicy.Log("SIMPLE_EDIT", "editor", m, if (isK) kotlin else web, 0.5, if (r.nextDouble() < p) 1.0 else 0.0, 0)
        }
        val rhat = { _: OffPolicy.Log, _: String -> 0.5 }
        val smart = OffPolicy.evaluate("akıllı", logs, { l -> if (l.x[0] > 0) "A" else "B" }, rhat)
        val alwaysA = OffPolicy.evaluate("hep A", logs, { "A" }, rhat)
        assertEquals(0.9, smart.dr, 0.05)  // the true value of the context-aware rule
        assertEquals(0.55, alwaysA.dr, 0.05) // the true value of "always A"
        assertTrue(smart.lo > alwaysA.hi) // the difference is clear on this much data
        assertTrue(smart.ess > 500)
    }

    @Test fun promptSectionsKeepTheirHardParts() {
        assertNull(PromptSections.problem(PromptSections.EXECUTOR, PromptSections.EXECUTOR_DEFAULT))
        assertNotNull(PromptSections.problem(PromptSections.EXECUTOR, "Rules:\n- Be careful and write good code. ".repeat(5))) // lost SEARCH/REPLACE
        assertNotNull(PromptSections.problem(PromptSections.TOKEN, "short"))
        val traces = listOf(trace(diffErr = 2), trace(diffErr = 1), trace(diffErr = 1, ci = 1))
        assertEquals(PromptSections.EXECUTOR, PromptEvolver.target(traces))
        assertNull(PromptEvolver.clean(PromptSections.EXECUTOR, PromptSections.EXECUTOR_DEFAULT, PromptSections.EXECUTOR_DEFAULT)) // unchanged
        val better = PromptSections.EXECUTOR_DEFAULT + "\n- Before writing SEARCH, copy the lines from the CURRENT file text exactly; never from memory."
        assertEquals(better, PromptEvolver.clean(PromptSections.EXECUTOR, "```\n$better\n```", PromptSections.EXECUTOR_DEFAULT))
        val e = Experiment.prompt(PromptSections.EXECUTOR, better, "test")
        assertTrue(e.key.startsWith("PROMPT:executor_rules#"))
        assertEquals(e.key, Experiment.fromJson(e.toJson()).key)
    }

    private fun trace(diffErr: Int = 0, ci: Int = 0, req: String = "x", steps: List<String> = emptyList(), at: Long = 0) =
        SessionTrace("t${Math.random()}", "s", "me/app", at, req, listOf("m"), 5, null, 0.01, diffErr, listOf("SEARCH bulunamadı"), ci,
            if (ci > 0) listOf("e: x") else emptyList(), emptyList(), null, false, false, ciRuns = 1, steps = steps)

    @Test fun proceduresAreMinedFromRepeatedSuccesses() {
        val run = listOf<ChatMessage>(ChatMessage.Assistant("", listOf(
            ToolCall("1", "github_read_files", JSONObject().put("paths", JSONArray(listOf("gradle/libs.versions.toml", "app/build.gradle.kts")))),
            ToolCall("2", "github_apply_diff", JSONObject().put("diff", "gradle/libs.versions.toml\n<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE")),
            ToolCall("3", "ci_wait", JSONObject()))))
        assertEquals(listOf("read:libs.versions.toml", "read:build.gradle.kts", "edit:libs.versions.toml", "ci"), ProcedureMiner.steps(run))
        assertEquals("*.kt", ProcedureMiner.abstractPath("app/src/Main.kt"))
        assertEquals(listOf("b", "d"), ProcedureMiner.lcs(listOf("a", "b", "c", "d"), listOf("b", "x", "d")))
        val s = listOf("read:libs.versions.toml", "edit:libs.versions.toml", "edit:gradle-wrapper.properties", "ci")
        val traces = listOf(
            trace(req = "kotlin sürümünü yükselt", steps = s, at = 3), trace(req = "kotlin ve agp sürümünü yükselt", steps = listOf("read:build.gradle.kts") + s, at = 2),
            trace(req = "agp ve kotlin sürüm yükseltme", steps = s + "pr", at = 1), trace(req = "menüye buton ekle", steps = listOf("read:*.kt", "edit:*.kt", "ci"), at = 0),
        )
        val c = ProcedureMiner.clusters(traces).single()
        assertEquals(3, c.traces.size)
        assertEquals(s, c.common)
    }
}
