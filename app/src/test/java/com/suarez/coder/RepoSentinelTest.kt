package com.suarez.coder

import com.suarez.coder.evolution.CiSnapshot
import com.suarez.coder.evolution.EvolutionChoice
import com.suarez.coder.evolution.EvolutionCompletion
import com.suarez.coder.evolution.EvolutionModel
import com.suarez.coder.evolution.EvolutionProvider
import com.suarez.coder.evolution.RepoSentinel
import com.suarez.coder.evolution.RepoSentinelEngine
import com.suarez.coder.evolution.SentinelAction
import com.suarez.coder.evolution.SentinelConfig
import com.suarez.coder.evolution.SentinelPatchParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoSentinelTest {
    @Test fun overBudgetDoesNotRun() {
        val config = SentinelConfig(enabled = true, repos = listOf("me/app"), dailyBudgetUsd = 0.10, spentTodayUsd = 0.10)
        assertFalse(RepoSentinel.canRun(config))
        var probed = false
        val actions = runBlocking {
            RepoSentinelEngine(config).scan(probe = {
                probed = true
                error("çağrılmamalı")
            })
        }
        assertTrue(actions.isEmpty())
        assertFalse(probed)
    }

    @Test fun neverPushesOrMergesMain() {
        assertFalse(RepoSentinel.allowsPush("main", false))
        assertFalse(RepoSentinel.allowsPush("master", false))
        assertFalse(RepoSentinel.allowsPush("refs/heads/main", false))
        assertFalse(RepoSentinel.allowsPush("sentinel/fix-1", true))
        assertTrue(RepoSentinel.allowsPush("sentinel/fix-1", false))
        val config = SentinelConfig(enabled = true, repos = listOf("me/app"), dailyBudgetUsd = 0.10, draftFix = true)
        val branches = ArrayList<String>()
        val patch = """{"summary":"fix","files":[{"path":"app/A.kt","content":"fun a() = 1\n"},{"path":"app/src/test/ATest.kt","content":"class ATest\n"}]}"""
        val actions = runBlocking { RepoSentinelEngine(config).scan(now = 42, probe = {
            CiSnapshot("main", branchRed = true, failingPr = null, cause = "e: unresolved")
        }, openDraft = { req, files ->
            branches += req.branch
            assertTrue(RepoSentinel.allowsPush(req.branch, merge = false))
            assertFalse(req.branch.equals("main", true))
            assertTrue(files.any { SentinelPatchParser.isTest(it.first) })
            17
        }, model = EvolutionChoice(EvolutionProvider.OPENROUTER, "nvidia/nemotron-3-ultra", "gemini/locked"),
            budgetLeft = 0.10,
            proposePatch = { _, choice, _ ->
                assertEquals("gemini/locked", choice.architectModelId)
                EvolutionCompletion(patch, 0.01, choice.modelId, choice.provider)
            }) }
        assertEquals(listOf("sentinel/fix-42"), branches)
        val draft = actions.single() as SentinelAction.Draft
        assertEquals("sentinel/fix-42", draft.branch)
        assertEquals("main", draft.base)
        assertEquals(17, draft.pr)
        val text = RepoSentinel.notification("me/app", "main", 17)
        assertTrue(text.contains("me/app `main` CI kırıldı"))
        assertTrue(text.contains("PR #17"))
        assertFalse(text.contains("✅") || text.contains("🔴"))
    }

    @Test fun budgetBlocksTheModelAndAPatchWithoutTestsIsNotPushed() = runBlocking {
        val architect = "gemini/architect-lock"
        val config = SentinelConfig(enabled = true, repos = listOf("me/app"), dailyBudgetUsd = 0.10, draftFix = true, spentTodayUsd = 0.0)
        var diagnosed = 0
        val quiet = RepoSentinelEngine(config).scan(
            probe = { CiSnapshot("main", true, null, "e: unresolved") },
            openDraft = { _, _ -> error("push yok") },
            model = EvolutionModel.select(architect, EvolutionProvider.GEMINI, "gemini-2.5-flash"),
            budgetLeft = 0.0,
            diagnose = { _, _, _ -> diagnosed++; error("model yok") },
        )
        assertEquals(0, diagnosed)
        assertTrue(quiet.single() is SentinelAction.Report)
        assertEquals(architect, EvolutionModel.select(architect, EvolutionProvider.GEMINI, "gemini-2.5-flash").architectModelId)
        val seen = ArrayList<EvolutionProvider>()
        val noTest = """{"files":[{"path":"app/A.kt","content":"fun a()=1"}]}"""
        val withTest = """{"files":[{"path":"app/A.kt","content":"fun a()=1"},{"path":"app/src/test/ATest.kt","content":"class ATest"}]}"""
        var pushes = 0
        for (provider in listOf(EvolutionProvider.GEMINI, EvolutionProvider.OPENROUTER)) {
            val choice = EvolutionModel.select(architect, provider, if (provider == EvolutionProvider.GEMINI) "gemini-2.5-flash" else "nvidia/nemotron-3-ultra")
            RepoSentinelEngine(config).scan(
                now = 7,
                probe = { CiSnapshot("main", true, null, "e: unresolved reference Secret/app ghp_abcdefghijklmnopqrstuvwxyz") },
                openDraft = { req, _ ->
                    pushes++
                    assertTrue(req.branch.startsWith("sentinel/fix-"))
                    assertFalse(req.branch == "main")
                    3
                },
                model = choice,
                budgetLeft = 0.05,
                diagnose = { _, c, _ ->
                    seen += c.provider
                    assertEquals(architect, c.architectModelId)
                    EvolutionCompletion("kök neden ghp_abcdefghijklmnopqrstuvwxyz https://github.com/Secret/app", 0.01, c.modelId, c.provider)
                },
                proposePatch = { _, c, _ ->
                    EvolutionCompletion(if (c.provider == EvolutionProvider.GEMINI) noTest else withTest, 0.01, c.modelId, c.provider)
                },
            )
        }
        assertEquals(listOf(EvolutionProvider.GEMINI, EvolutionProvider.OPENROUTER), seen)
        assertEquals(1, pushes)
        assertNull(SentinelPatchParser.parse(noTest))
    }
}