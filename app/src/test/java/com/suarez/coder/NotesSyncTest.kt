package com.suarez.coder

import com.suarez.coder.evolution.NotesSync
import com.suarez.coder.evolution.RuleSource
import com.suarez.coder.evolution.ScopedRuleStore
import com.suarez.coder.tools.GitHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NotesSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun toggleOffLeavesTheCommitAlone() {
        val rules = ScopedRuleStore(File(tmp.newFolder(), "rules.json"))
        rules.add("me/app", listOf("*"), "Gradle JDK 21 gerektirir", RuleSource.MANUAL)
        val files = listOf(GitHub.FileChange("A.kt", "fun a() = 1\n", false))
        assertEquals(files, NotesSync.attach("old notes", "me/app", files, rules, enabled = false))
    }

    @Test fun toggleOnAddsNotesToTheRequestedCommitAndDoesNotReplaceAnExistingNotesEdit() {
        val rules = ScopedRuleStore(File(tmp.newFolder(), "rules.json"))
        rules.add("me/app", listOf("ui/**/*.kt"), "Compose sabit yukseklik verme", RuleSource.CI_VERIFIED)
        val files = listOf(GitHub.FileChange("A.kt", "fun a() = 1\n", false))
        val with = NotesSync.attach("", "me/app", files, rules, enabled = true)
        assertEquals(2, with.size)
        val notes = with.single { it.path == NotesSync.PATH }
        assertTrue(notes.content!!.contains("Compose sabit yukseklik verme"))
        assertTrue(notes.content!!.length <= 4_000)
        val already = listOf(GitHub.FileChange(NotesSync.PATH, "kullanici notu", false))
        assertEquals(already, NotesSync.attach("", "me/app", already, rules, enabled = true))
    }
}
