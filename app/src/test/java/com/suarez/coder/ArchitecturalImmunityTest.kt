package com.suarez.coder

import com.suarez.coder.security.ArchitecturalImmunity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitecturalImmunityTest {

    @Test
    fun learnsBlocksRepeatingRegressionAndSupportsDisabling() {
        val store = ArchitecturalImmunity.Store()
        val rule = store.learnFromForensic(
            rawLog = "e: SequentialOrchestrator.kt:88: Unresolved reference 'commitFiles' without preCommit",
            forbiddenAntiPattern = """res\.close\(\)""",
            suggestedFix = "Http.Result üzerinde close() yok; response.use {} kullan.",
            stackTag = "kotlin",
            confidence = 0.85,
        )

        val before = "package a\nfun send() { val x = 1 }"
        val badAfter = "package a\nfun send() { val x = 1; res.close() }"
        val matches = store.checkAll(listOf(Triple("OpenAiCompatClient.kt", before, badAfter)))
        assertEquals(1, matches.size)
        assertEquals(rule.id, matches.single().rule.id)
        assertTrue(ArchitecturalImmunity.formatBlockMessage(matches).contains("response.use"))

        // Disabling the rule must immediately stop blocking (false-positive guard)
        assertTrue(store.setEnabled(rule.id, false))
        val afterDisable = store.checkAll(listOf(Triple("OpenAiCompatClient.kt", before, badAfter)))
        assertTrue(afterDisable.isEmpty())

        // Serialization round-trip preserves disabled state and hit count
        val restored = ArchitecturalImmunity.Store()
        restored.loadJsonString(store.toJsonString())
        assertEquals(1, restored.all().size)
        assertEquals(false, restored.all().single().enabled)
        assertTrue(restored.all().single().hits >= 2)
    }
}
