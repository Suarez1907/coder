package com.suarez.coder

import com.suarez.coder.evolution.EvolutionGuard
import com.suarez.coder.evolution.RuleSource
import com.suarez.coder.evolution.ScopedRuleStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScopedRuleStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = ScopedRuleStore(File(tmp.newFolder(), "rules.json"))

    @Test fun globsInjectOnlyForMatchingFiles() {
        val s = store()
        s.add("me/app", listOf("ui/**/*.kt"), "Compose ekranlarında sabit yükseklik verme", RuleSource.REFLECTOR)
        s.add("me/app", listOf("*.gradle.kts"), "Gradle JDK 21 ile derlenir", RuleSource.CI_VERIFIED)
        s.add("me/app", listOf("**/*.gd"), "GDScript sinyal adları snake_case", RuleSource.MANUAL)
        val ui = s.selectForTurn("me/app", listOf("ui/Screen.kt"), now = 10)
        assertEquals(listOf("Compose ekranlarında sabit yükseklik verme"), ui.map { it.ruleText })
        assertEquals(1, ui.single().hitCount)
        assertEquals(10L, ui.single().lastMatchedAt)
        assertTrue(s.selectForTurn("me/app", listOf("data/Settings.kt"), now = 11).isEmpty())
        assertEquals(listOf("Gradle JDK 21 ile derlenir"), s.selectForTurn("me/app", listOf("build.gradle.kts"), now = 12).map { it.ruleText })
        assertEquals(listOf("GDScript sinyal adları snake_case"), s.selectForTurn("me/app", listOf("game/player.gd"), now = 13).map { it.ruleText })
        val block = ScopedRuleStore.formatBlock(ui)!!
        assertTrue(block.startsWith(ScopedRuleStore.BLOCK_HEADER))
        assertTrue(!block.contains("SYSTEM PROMPT"))
    }

    @Test fun atMostFiveRulesPerTurnBySpecificityThenSource() {
        val s = store()
        s.add("me/app", listOf("**/*.kt"), "zayif genel kural burada", RuleSource.REFLECTOR, preventedErrorCount = 0, createdAt = 1, lastMatchedAt = 1)
        s.add("me/app", listOf("ui/**/*.kt"), "en spesifik compose kurali", RuleSource.REFLECTOR, preventedErrorCount = 0, createdAt = 2, lastMatchedAt = 2)
        s.add("me/app", listOf("**/*.kt"), "ci dogrulandi orta kural", RuleSource.CI_VERIFIED, preventedErrorCount = 1, createdAt = 3, lastMatchedAt = 3)
        s.add("me/app", listOf("**/*.kt"), "cok hata onledi kurali", RuleSource.REFLECTOR, preventedErrorCount = 9, createdAt = 4, lastMatchedAt = 4)
        s.add("me/app", listOf("**/*.kt"), "guncel eslesen kural", RuleSource.REFLECTOR, preventedErrorCount = 2, createdAt = 5, lastMatchedAt = 50)
        s.add("me/app", listOf("**/*.kt"), "eski dusuk oncelik kural", RuleSource.REFLECTOR, preventedErrorCount = 2, createdAt = 6, lastMatchedAt = 5)
        val picked = s.selectForTurn("me/app", listOf("ui/Screen.kt"), now = 100)
        assertEquals(5, picked.size)
        assertEquals("en spesifik compose kurali", picked.first().ruleText)
        assertTrue(picked.none { it.ruleText.startsWith("zayif") })
        assertEquals(1, picked.first { it.ruleText.startsWith("en spesifik") }.hitCount)
    }

    @Test fun idleAutomaticRulesArchiveAndManualRulesStay() {
        val s = store()
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        val old = s.add("me/app", listOf("*"), "otomatik eski kural metni", RuleSource.REFLECTOR, createdAt = now - 31 * day, lastMatchedAt = now - 31 * day)!!
        val manual = s.add("me/app", listOf("*"), "manuel kalici kural metni", RuleSource.MANUAL, createdAt = now - 40 * day, lastMatchedAt = now - 40 * day)!!
        val fresh = s.add("me/app", listOf("*"), "yeni eslesen kural metni", RuleSource.REFLECTOR, createdAt = now - 2 * day, lastMatchedAt = now - day)!!
        assertEquals(1, s.decay(now))
        val left = s.all().associateBy { it.id }
        assertTrue(left.getValue(old.id).archived)
        assertTrue(!left.getValue(manual.id).archived)
        assertTrue(!left.getValue(fresh.id).archived)
        assertTrue(s.restore(old.id))
        assertTrue(!s.all().first { it.id == old.id }.archived)
    }

    @Test fun activeCapIsFortyAndNotesStayWithinLimit() {
        val s = store()
        repeat(40) { i ->
            s.add("me/app", listOf("*"), "manuel kural numarasi $i yeterince uzun", RuleSource.MANUAL, createdAt = i.toLong())
        }
        assertNull(s.add("me/app", listOf("*"), "bu kural sigmaz artik", RuleSource.REFLECTOR))
        assertEquals(40, s.active("me/app").size)
        val other = store()
        repeat(15) { i ->
            other.add("me/app", listOf("*"), "k".repeat(200) + " $i", RuleSource.REFLECTOR, createdAt = i.toLong())
        }
        val notes = other.syncNotes("## Öğrenilen dersler\n- Gradle JDK 21 gerektirir", "me/app")
        assertTrue(notes.length <= EvolutionGuard.NOTES_LIMIT)
        assertTrue(notes.contains("Gradle JDK 21"))
        assertTrue(notes.contains(ScopedRuleStore.NOTES_START))
    }
}
