package com.suarez.coder

import com.suarez.coder.evolution.CrossRepoKnowledge
import com.suarez.coder.evolution.EvolutionCompletion
import com.suarez.coder.evolution.EvolutionCompleter
import com.suarez.coder.evolution.EvolutionProvider
import com.suarez.coder.evolution.GeneralRule
import com.suarez.coder.evolution.PrivacyFilter
import com.suarez.coder.evolution.RuleSource
import com.suarez.coder.evolution.ScopedRule
import com.suarez.coder.evolution.SessionTrace
import com.suarez.coder.evolution.SkillSynthesizer
import com.suarez.coder.skills.Skill
import com.suarez.coder.skills.SkillRepository
import kotlinx.coroutines.runBlocking
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

    @Test fun modelFailureAndLeakFallBackWithoutCallingTheNetwork() = runBlocking {
        val traces = listOf(trace("a", "s1"), trace("b", "s2"))
        var calls = 0
        val down = EvolutionCompleter { _, _, _ ->
            calls++
            null
        }
        val fallback = SkillSynthesizer.draftWithModel(traces, down, 0.05)
        assertEquals(1, calls)
        assertNotNull(fallback)
        assertTrue(fallback!!.body.contains("Kontrol listesi"))
        assertFalse(fallback.body.contains("ghp_"))
        var blocked = 0
        val unused = EvolutionCompleter { _, _, _ -> blocked++; error("bütçe yok") }
        val free = SkillSynthesizer.draftWithModel(traces, unused, 0.0)
        assertEquals(0, blocked)
        assertEquals(fallback.body, free!!.body)
        val leaky = EvolutionCompleter { _, _, _ ->
            EvolutionCompletion(
                "Kontrol listesi\n1. SecretOrg/private-app ghp_abcdefghijklmnopqrstuvwxyz https://example.com/x\nTuzaklar\n- sızdı",
                0.01, "gemini-2.5-flash", EvolutionProvider.GEMINI,
            )
        }
        val masked = SkillSynthesizer.draftWithModel(traces, leaky, 0.05)!!
        assertFalse(masked.body.contains("ghp_"))
        assertFalse(masked.body.contains("https://"))
        assertFalse(masked.body.contains("SecretOrg/private-app"))
        val knowledge = CrossRepoKnowledge(File(tmp.newFolder("evo2"), "general.json"))
        val rule = ScopedRule("r2", "SecretOrg/private-app", listOf("**/*.kt"), "kotlin kuralı yeterince uzun olmalı", RuleSource.CI_VERIFIED, preventedErrorCount = 2)
        var seen = ""
        val llm = EvolutionCompleter { _, user, _ ->
            seen = user
            EvolutionCompletion("SecretOrg/private-app ghp_zzzzzzzzzzzzzzzz için https://github.com/secret/repo kuralı yeterince uzun", 0.01, "openai/gpt", EvolutionProvider.OPENROUTER)
        }
        val promoted = knowledge.promoteWithModel(listOf(rule), llm, 0.02)
        assertTrue(seen.isNotBlank())
        assertTrue(promoted.isEmpty() || promoted.all { !it.text.contains("ghp_") && !it.text.contains("https://") && !it.text.contains("SecretOrg") })
        val det = knowledge.promoteWithModel(listOf(rule), llm, 0.0)
        assertTrue(det.isNotEmpty() || knowledge.all().isNotEmpty())
    }
}
