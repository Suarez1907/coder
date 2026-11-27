package com.suarez.coder.evolution

import com.suarez.coder.skills.Skill
import com.suarez.coder.skills.SkillParser

/**
 * Turns a repeated, CI-green, high-complexity workflow into a draft SKILL.md.
 * The draft is deterministic from the traces, so it cannot change the architect model and costs nothing.
 * EvolutionWorker calls this on the isolated evolution path; the user activates the draft.
 */
object SkillSynthesizer {
    const val MAX_BYTES = 6 * 1024
    const val MIN_TOOL_STEPS = 6

    fun highComplexity(t: SessionTrace): Boolean {
        val files = t.steps.map { it.substringAfter(':') }.filter { it.isNotBlank() }.distinct()
        return t.steps.size >= MIN_TOOL_STEPS || files.size >= 3 || t.steps.any { it.startsWith("cloud:") || it == "run_cloud_tool" }
    }

    fun patternKey(t: SessionTrace): String = t.steps.joinToString(">")

    /** A draft when the same successful pattern shows up in at least two sessions. */
    fun consider(traces: List<SessionTrace>): Skill? {
        val good = traces.filter { it.success && it.ciFailureCount == 0 && !it.stopped && highComplexity(it) && it.steps.isNotEmpty() }
        val group = good.groupBy { patternKey(it) }.values.firstOrNull { g ->
            g.map { it.sessionId }.filter { it.isNotBlank() }.distinct().size >= 2
        } ?: return null
        return draft(group)
    }

    fun draft(group: List<SessionTrace>): Skill? {
        val steps = group.first().steps
        if (steps.isEmpty()) return null
        val name = SkillParser.normalizeName("auto-" + steps.joinToString("-") { it.substringBefore(':').ifBlank { it } })
        if (!SkillParser.isValidName(name)) return null
        val triggers = group.map { it.request }.flatMap { req ->
            req.lowercase().split(Regex("[^a-z0-9çğıöşü]+")).filter { it.length >= 4 }
        }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.distinct().take(6)
            .ifEmpty { listOf("onar", "test") }
        val checklist = steps.mapIndexed { i, s -> "${i + 1}. ${s.replace(':', ' ')}" }.joinToString("\n")
        val pitfalls = listOf(
            "- Aynı SEARCH bloğunu körlemesine tekrarlama; dosyanın güncel halini oku.",
            "- Paylaşılan bir dosyayı iki alt göreve verme.",
            "- CI kırmızıysa logu yerelde daralt, tüm logu bağlama yapıştırma.",
        ).joinToString("\n")
        var body = buildString {
            append("## Ne zaman\n\n")
            append("Bu iş akışı en az iki seansta CI yeşiliyle bitti.\n\n")
            append("## Kontrol listesi\n\n").append(checklist).append("\n\n")
            append("## Tuzaklar\n\n").append(pitfalls).append('\n')
        }
        val description = "Tekrarlayan yeşil iş akışı: " + steps.take(4).joinToString(", ") { it.substringBefore(':') }
        var skill = Skill(
            name = name,
            description = description.take(140),
            triggers = triggers,
            body = body.trim(),
            source = Skill.Source.AUTO_SYNTHESIZED,
            enabled = false,
            status = Skill.Status.DRAFT,
        )
        while (skill.toMarkdown().toByteArray(Charsets.UTF_8).size > MAX_BYTES && body.length > 200) {
            body = body.dropLast(200)
            skill = skill.copy(body = body.trim())
        }
        if (skill.toMarkdown().toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        if (skill.triggers.isEmpty() || !skill.body.contains("Kontrol listesi") || !skill.body.contains("Tuzaklar")) return null
        return skill
    }

    /**
     * Asks the selected evolution model for the skill text. A null completer, an empty budget, a failed call,
     * an over-budget reply, or a privacy rejection falls back to [consider].
     */
    suspend fun draftWithModel(
        traces: List<SessionTrace>,
        llm: EvolutionCompleter?,
        budgetUsd: Double,
    ): Skill? {
        val skill = consider(traces) ?: return null
        if (llm == null || budgetUsd <= 0.0) return skill
        val repos = traces.map { it.repo }.filter { it.isNotBlank() }.distinct()
        val completion = runCatching {
            llm.complete(
                "Write a SKILL.md body in Turkish. Include the headings 'Kontrol listesi' and 'Tuzaklar'. No repository names, URLs, or tokens.",
                skill.body,
                budgetUsd,
            )
        }.getOrNull() ?: return skill
        if (completion.costUsd > budgetUsd) return skill
        val filtered = PrivacyFilter.apply(completion.text, repos)
        if (filtered.rejected || filtered.text.isNullOrBlank()) return skill
        val body = filtered.text
        if (!body.contains("Kontrol listesi") || !body.contains("Tuzaklar")) return skill
        val next = skill.copy(body = body)
        return if (next.toMarkdown().toByteArray(Charsets.UTF_8).size > MAX_BYTES) skill else next
    }
}
