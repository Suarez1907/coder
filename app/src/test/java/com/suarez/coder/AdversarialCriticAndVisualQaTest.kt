package com.suarez.coder

import com.suarez.coder.data.Provider
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.qa.AdversarialCritic
import com.suarez.coder.qa.ShipGuard
import com.suarez.coder.qa.VisualQaGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdversarialCriticAndVisualQaTest {
    @Test fun criticUsesAnotherFamilyAndDoesNotTouchArchitect() {
        val implementer = ModelRef(Provider.GEMINI, "gemini-2.5-pro")
        val locked = "gemini/gemini-architect-locked"
        val catalog = listOf(
            implementer,
            ModelRef(Provider.GEMINI, "gemini-2.5-flash"),
            ModelRef(Provider.OPENROUTER, "anthropic/claude-haiku-4"),
        )
        val pick = AdversarialCritic.select(implementer, catalog, locked)
        assertEquals("anthropic", pick.family)
        assertEquals("anthropic/claude-haiku-4", pick.modelId)
        assertEquals(locked, pick.architectModelId)
        assertFalse(pick.changedArchitect)
        assertNotEquals(AdversarialCritic.familyOf(implementer.provider, implementer.model), pick.family)

        val sameProvider = AdversarialCritic.select(
            implementer,
            listOf(implementer, ModelRef(Provider.GEMINI, "gemini-2.5-flash")),
            locked,
        )
        assertEquals(Provider.GEMINI, sameProvider.provider)
        assertEquals("gemini-2.5-flash", sameProvider.modelId)
        assertEquals(0.0, sameProvider.temperature, 0.0)
        assertEquals(locked, sameProvider.architectModelId)
        assertFalse(sameProvider.changedArchitect)
    }

    @Test fun criticalAndVisualDefectStopAutomaticMerge() {
        val block = AdversarialCritic.parse(
            """{"verdict":"BLOCK_CRITICAL","findings":[{"file":"A.kt","line":4,"category":"Null/Crash Kenar Durumu","detail":"npe"}]}""",
        )
        assertEquals("BLOCK_CRITICAL", block.status)
        assertEquals("A.kt:4", block.findings.single().site)
        val defect = VisualQaGate.parse("""{"verdict":"VISUAL_DEFECT","issues":["kesilen metin"]}""")
        assertEquals(listOf("kesilen metin"), defect.issues)
        assertTrue(ShipGuard.blocksMerge(defect, null))
        assertTrue(ShipGuard.blocksMerge(null, block))
        val pass = VisualQaGate.parse("""{"verdict":"PASS","issues":[]}""")
        val ok = AdversarialCritic.parse("""{"verdict":"APPROVE","findings":[]}""")
        assertFalse(ShipGuard.blocksMerge(pass, ok))
        assertFalse(ShipGuard.allowAnotherCriticFix(AdversarialCritic.MAX_FIX_ROUNDS))
        assertTrue(ShipGuard.allowAnotherVisualFix(1))
        assertFalse(ShipGuard.allowAnotherVisualFix(VisualQaGate.MAX_FIX_ROUNDS))
        assertTrue(AdversarialCritic.applies("Orta", 10))
        assertTrue(AdversarialCritic.applies("Düşük", 80))
        assertFalse(AdversarialCritic.applies("Düşük", 12))
    }

    @Test fun visualGateCollectsUiSnapshotsAndFlagsBlackFrames() {
        assertTrue(VisualQaGate.touchesUi(listOf("app/src/main/java/com/x/ui/Chat.kt", "notes.md")))
        assertTrue(VisualQaGate.touchesUi(listOf("res/layout/activity_main.xml")))
        assertTrue(VisualQaGate.touchesUi(listOf("scenes/player.tscn")))
        assertFalse(VisualQaGate.touchesUi(listOf("app/src/main/java/com/x/data/Settings.kt")))
        assertEquals(
            listOf("out/preview.png", "shot/roborazzi.png"),
            VisualQaGate.collectArtifacts(listOf("log.txt", "shot/roborazzi.png", "out/preview.png")),
        )
        val black = VisualQaGate.heuristic(listOf(VisualQaGate.Probe("preview.png", ByteArray(64))))
        assertEquals(VisualQaGate.VISUAL_DEFECT, black.verdict)
        val bytes = ByteArray(64) { 7 }
        assertEquals(VisualQaGate.PASS, VisualQaGate.heuristic(listOf(VisualQaGate.Probe("preview.png", bytes))).verdict)
    }
}
