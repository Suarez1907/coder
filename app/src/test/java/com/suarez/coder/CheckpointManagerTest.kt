package com.suarez.coder

import com.suarez.coder.orchestrator.OrchestratorMode
import com.suarez.coder.session.CheckpointManager
import com.suarez.coder.session.SpecApprovalGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CheckpointManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun rewindAndForkKeepIndexAndNeverForcePushMain() {
        val mgr = CheckpointManager(File(tmp.root, "cp.json"))
        val main = mgr.record("s", 0, "me/app", "main", "abc1234567890abc", 2, 0.01, 1_000)
        val feature = mgr.record("s", 1, "me/app", "feature", "def1234567890def", 5, 0.04, 2_000)
        val loaded = mgr.load()
        assertEquals(listOf(0, 1), loaded.map { it.turnIndex })
        assertEquals("main", loaded[0].branch)
        assertEquals(5, loaded[1].messageCount)
        assertEquals(0.04, loaded[1].cumulativeCostUsd, 1e-9)

        val rewind = mgr.rewind(main.checkpointId, now = 3_000)
        assertEquals(2, rewind.messageCount)
        assertEquals("me/app", rewind.git.repo)
        assertEquals("abc1234567890abc", rewind.git.sha)
        assertEquals("create_branch", rewind.git.action)
        assertFalse(rewind.git.force)
        assertFalse(rewind.git.forcePushesMain)
        assertNotEquals("main", rewind.git.branch)
        assertTrue(rewind.git.branch.startsWith("coder/rewind-"))

        val featureRewind = mgr.rewind(feature.checkpointId, now = 3_500)
        assertFalse(featureRewind.git.force)
        assertFalse(featureRewind.git.branch == "main" && featureRewind.git.force)
        assertEquals(listOf("a", "b"), mgr.takeMessages(listOf("a", "b", "c"), featureRewind.messageCount).take(2))

        val fork = mgr.fork(feature.checkpointId, now = 4_000)
        assertTrue(fork.branch.startsWith("coder/fork-"))
        assertFalse(fork.force)
        assertEquals(5, fork.messageCount)
        assertEquals(feature.checkpointId, fork.checkpoint.checkpointId)
    }

    @Test fun specGateSkipsSmallTasksAndKeepsArchitect() {
        assertFalse(SpecApprovalGate.needsApproval(true, "App.kt içindeki yazım hatasını düzelt", OrchestratorMode.OFF))
        assertFalse(SpecApprovalGate.needsApproval(false, "a.kt b.kt c.kt hepsini değiştir", OrchestratorMode.CONTRACT_DAG))
        assertTrue(SpecApprovalGate.needsApproval(true, "a.kt b.kt ve c.kt dosyalarını birlikte güncelle", OrchestratorMode.OFF))
        assertTrue(SpecApprovalGate.needsApproval(true, "x".repeat(200), OrchestratorMode.CONTRACT_DAG))
        val card = SpecApprovalGate.draft("ekranı üçe böl ve test ekle", "gemini/locked-architect")
        assertEquals("gemini/locked-architect", SpecApprovalGate.lock(card, "openai/other").architectModelId)
        val approved = SpecApprovalGate.approve(card, setOf(card.items.first().id), "kenar boşluğu kalsın")
        assertFalse(approved.items.first().checked)
        assertEquals("kenar boşluğu kalsın", approved.note)
        assertEquals("gemini/locked-architect", approved.architectModelId)
    }
}
