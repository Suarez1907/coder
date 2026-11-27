package com.suarez.coder

import com.suarez.coder.evolution.CiLessonLearner
import com.suarez.coder.evolution.RuleSource
import com.suarez.coder.evolution.ScopedRuleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CiLessonLearnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val red = """
        > Task :app:compileDebugKotlin
        e: app/src/main/java/Game.kt:12:8 Unresolved reference 'PlayerState'
        FAILURE: Build failed with an exception.
    """.trimIndent()

    private val license = """
        WITHOUT WARRANTY OF ANY KIND, INCLUDING MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE.
        FAILURE OF ESSENTIAL PURPOSE.
    """.trimIndent()

    @Test fun greenAfterRedStoresAVerifiedRule() {
        val rules = ScopedRuleStore(File(tmp.newFolder(), "rules.json"))
        val learner = CiLessonLearner(rules)
        val pending = learner.onRed("me/app", red)
        assertTrue(pending != null)
        assertTrue(pending!!.lesson.length <= 220)
        assertTrue(pending.globs.any { it.contains("Game.kt") })
        assertTrue(rules.active("me/app").isEmpty())
        val stored = learner.onGreen("me/app")
        assertEquals(RuleSource.CI_VERIFIED, stored!!.source)
        assertEquals(listOf(stored.id), rules.active("me/app").map { it.id })
        assertNull(learner.onGreen("me/app"))
    }

    @Test fun licenseNoiseAndAGreenWithoutRedStoreNothing() {
        val rules = ScopedRuleStore(File(tmp.newFolder(), "rules.json"))
        val learner = CiLessonLearner(rules)
        assertNull(learner.onRed("me/app", license))
        assertNull(learner.onGreen("me/app"))
        assertTrue(rules.all().isEmpty())
    }

    @Test fun greenOnAnotherRepoDoesNotConsumeTheLesson() {
        val rules = ScopedRuleStore(File(tmp.newFolder(), "rules.json"))
        val learner = CiLessonLearner(rules)
        learner.onRed("me/app", red)
        assertNull(learner.onGreen("other/repo"))
        assertEquals("me/app", learner.onGreen("me/app")!!.repo)
    }
}
