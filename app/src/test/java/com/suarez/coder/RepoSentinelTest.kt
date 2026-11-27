package com.suarez.coder

import com.suarez.coder.evolution.CiSnapshot
import com.suarez.coder.evolution.RepoSentinel
import com.suarez.coder.evolution.RepoSentinelEngine
import com.suarez.coder.evolution.SentinelAction
import com.suarez.coder.evolution.SentinelConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val actions = runBlocking { RepoSentinelEngine(config).scan(now = 42, probe = {
            CiSnapshot("main", branchRed = true, failingPr = null, cause = "e: unresolved")
        }, openDraft = { req ->
            branches += req.branch
            assertTrue(RepoSentinel.allowsPush(req.branch, merge = false))
            assertFalse(req.branch.equals("main", true))
            assertFalse(req.branch.equals(req.base, true) && req.branch == "main")
            17
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
}