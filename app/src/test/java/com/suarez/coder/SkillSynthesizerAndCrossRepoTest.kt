package com.suarez.coder

import com.suarez.coder.evolution.CrossRepoKnowledge
import com.suarez.coder.evolution.GeneralRule
import com.suarez.coder.evolution.PrivacyFilter
import com.suarez.coder.evolution.RuleSource
import com.suarez.coder.evolution.ScopedRule
import com.suarez.coder.evolution.SessionTrace
import com.suarez.coder.evolution.SkillSynthesizer
import com.suarez.coder.skills.Skill
import com.suarez.coder.skills.SkillRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillSynthesizerAndCrossRepoTest {
    @get:Rule val tmp = TemporaryFolder()

    private val steps = listOf("read:*.kt", "edit:*.kt", "ci", "read:*.kts", "edit:*.kts", "pr")

    private fun trace(id: String, session: String) = SessionTrace(
        id = id, sessionId = session, repo = "SecretOrg/private-app", at = 1, request = "ci yeşil onarım akışı",
        modelsUsed = emptyList(), turnCount = 2, cacheHitPercent = null, totalCostUsd = 0.02,
        diffErrorCount = 0, diffErrorSamples = emptyList(), ciFailureCount = 0, ciSignatures = emptyList(),
        escalations = emptyList(), routing = null, upgradedToArchitect = false, stopped = false,
        steps = steps,
    )

    @Test fun repeatedGreenWorkflowBecomesDraftSkill() {
        val skill = SkillSynthesizer.consider(listOf(trace("a", "s1"), trace("b", "s2")))
        assertNotNull(skill)
        skill!!
        assertEquals(Skill.Source.AUTO_SYNTHESIZED, skill.source)
        assertEquals(Skill.Status.DRAFT, skill.status)
        assertTrue(skill.triggers.isNotEmpty())
        assertTrue(skill.body.contains("Kontrol listesi"))
        assertTrue(skill.body.contains("Tuzaklar"))
        assertTrue(skill.toMarkdown().toByteArray(Charsets.UTF_8).size <= SkillSynthesizer.MAX_BYTES)
        assertTrue(SkillSynthesizer.consider(listOf(trace("a", "s1"))) == null)
        val repo = SkillRepository(tmp.newFolder("skills"), { null }, { _, _ -> null })
        repo.saveGlobal(skill)
        repo.setEnabled(skill.name, false)
        assertEquals(Skill.Status.DRAFT, repo.global(skill.name)!!.status)
        repo.recordOutcome(skill.name, false)
        repo.recordOutcome(skill.name, false)
        val flagged = repo.global(skill.name)!!
        assertEquals(Skill.Status.REVIEW_REQUIRED, flagged.status)
        assertTrue(flagged.reviewNote!!.isNotBlank())
        repo.approve(skill.name)
        assertEquals(Skill.Status.ACTIVE, repo.global(skill.name)!!.status)
    }

    @Test fun crossRepoPromotionMasksSecretsAndKeepsTheCap() {
        val secret = "Suarez1907/coder içinde ghp_abcdefghijklmnopqrstuvwxyz kullanma ve önbelleği temizle"
        val rule = ScopedRule("r1", "Suarez1907/coder", listOf("**/*.kt"), secret, RuleSource.CI_VERIFIED, preventedErrorCount = 2)
        val knowledge = CrossRepoKnowledge(File(tmp.newFolder("evo"), "general.json"))
        val created = knowledge.promote(listOf(rule))
        assertEquals(1, created.size)
        assertEquals("kotlin-compose", created.single().techTag)
        assertFalse(created.single().text.contains("Suarez1907"))
        assertFalse(created.single().text.contains("ghp_"))
        assertFalse(created.single().text.contains("http"))
        val leaked = PrivacyFilter.apply("token ghp_zzzzzzzzzzzzzzzz kaldı ve https://github.com/secret/repo", listOf("secret/repo"))
        assertFalse(leaked.rejected)
        assertFalse(leaked.text!!.contains("ghp_"))
        assertFalse(leaked.text.contains("https://"))
        assertFalse(leaked.text.contains("secret/repo"))

        val repoRules = (1..5).map { ScopedRule("k$it", "me/app", listOf("**/*.kt"), "kural $it yeterince uzun", RuleSource.MANUAL) }
        val general = listOf(GeneralRule("g", "kotlin-compose", "genel kural yeterince uzun", 2))
        assertEquals(5, CrossRepoKnowledge.merge(repoRules, general, listOf("app/A.kt")).size)
        val room = CrossRepoKnowledge.merge(repoRules.take(4), general, listOf("app/A.kt"))
        assertEquals(5, room.size)
        assertTrue(room.any { it.repo == "*" })
        val skipped = CrossRepoKnowledge.merge(repoRules.take(4), general, listOf("notes.txt"))
        assertEquals(4, skipped.size)
        assertTrue(skipped.none { it.repo == "*" })
    }
}
