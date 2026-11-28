package com.suarez.coder

import com.suarez.coder.proof.InvariantMiner
import com.suarez.coder.proof.ProofCarryingPatchGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvariantMinerAndProofTest {
    @Test
    fun blocksScreenRemovalAndSettingsKeyLossUnlessExplicitlyApproved() {
        val settingsScreen = "package ui\nfun SettingsScreen() { EvolutionScreen(); SkillsScreen() }" + " ".repeat(250)
        val settingsKt = "package data\nclass Settings { val a = \"architect_model\"; val b = \"budget_usd\" }"
        val invariants = InvariantMiner.mine(
            paths = listOf("app/src/main/java/com/suarez/coder/evolution/X.kt", "app/src/main/java/com/suarez/coder/ui/SettingsScreen.kt"),
            keyFileContents = mapOf(
                "app/src/main/java/com/suarez/coder/ui/SettingsScreen.kt" to settingsScreen,
                "app/src/main/java/com/suarez/coder/data/Settings.kt" to settingsKt,
            ),
        )
        assertTrue(invariants.isNotEmpty())

        val brokenScreen = "package ui\nfun SettingsScreen() { SkillsScreen() }" + " ".repeat(250)
        val proofBlocked = ProofCarryingPatchGate.verifyChanges(
            listOf(Triple("app/src/main/java/com/suarez/coder/ui/SettingsScreen.kt", settingsScreen, brokenScreen)),
            invariants,
        )
        assertTrue(proofBlocked.blocked)
        assertTrue(proofBlocked.prSummary().contains("proofHash: #"))

        val brokenIds = proofBlocked.brokenInvariants.map { it.invariant.id }.toSet()
        val proofApproved = ProofCarryingPatchGate.verifyChanges(
            listOf(Triple("app/src/main/java/com/suarez/coder/ui/SettingsScreen.kt", settingsScreen, brokenScreen)),
            invariants,
            userApprovedIds = brokenIds,
        )
        assertFalse(proofApproved.blocked)
    }
}
