package com.suarez.coder

import com.suarez.coder.skills.SkillSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SkillsSync: yalnızca .coder/skills/<ad>/ altındaki yollar sayılır; ad normalize edilir. */
class SkillSyncTest {

    @Test fun `skill paths are detected and named`() {
        val paths = listOf(
            ".coder/skills/android-compose/SKILL.md",
            ".coder/skills/Android-Compose/references/api.md",
            "app/src/main/java/A.kt",
            ".coder/NOTES.md",
        )
        assertEquals(setOf("android-compose"), SkillSync.names(paths))
        assertTrue(SkillSync.touches(paths))
    }

    @Test fun `invalid names and unrelated paths are ignored`() {
        assertFalse(SkillSync.touches(listOf("app/Main.kt", ".coder/skills/SKILL.md", ".coder/skills/")))
        assertTrue(SkillSync.names(listOf(".coder/skills/%%%/SKILL.md")).isEmpty())
        assertNull(SkillSync.notice(listOf("README.md")))
    }

    @Test fun `notice names the skill and the next step`() {
        val n = SkillSync.notice(listOf(".coder/skills/blender-3d/SKILL.md", ".coder/skills/review/SKILL.md"))!!
        assertTrue(n.contains("blender-3d") && n.contains("review", ignoreCase = true))
        assertTrue(n.contains("github_read_file"))
    }
}
