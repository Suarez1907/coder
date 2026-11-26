package com.suarez.coder.evolution

import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.skills.SkillImporter
import com.suarez.coder.skills.SkillRepository
import com.suarez.coder.skills.SkillSafety
import com.suarez.coder.tools.GitHub
import org.json.JSONObject

/**
 * Weekly GitHub scout: finds skill collections for the user's stack, imports at most two promising skills per week
 * (switched off, safety-scanned) and queues them as exam experiments. Only the exam decides whether they stay;
 * nothing found on GitHub is ever trusted as an instruction by itself. Costs GitHub API calls only.
 */
class SkillScout(
    private val gh: GitHub,
    private val importer: SkillImporter,
    private val skills: SkillRepository,
    private val store: EvolutionStore,
) {
    companion object {
        const val MAX_PER_WEEK = 2
        const val MIN_STARS = 50

        /** Search words for the languages of the user's repositories. */
        fun stackKeywords(languages: Collection<String>): List<String> = languages.flatMap { l ->
            when (l.lowercase()) {
                "kotlin" -> listOf("kotlin", "android", "compose")
                "java" -> listOf("android", "java")
                "javascript", "typescript" -> listOf("javascript", "web", "frontend")
                "html", "css" -> listOf("web", "frontend")
                "python" -> listOf("python")
                "gdscript" -> listOf("godot")
                "c#" -> listOf("unity")
                "swift" -> listOf("ios", "swift")
                "rust" -> listOf("rust")
                "go" -> listOf("go")
                else -> emptyList()
            }
        }.distinct()

        /** A skill is relevant when its name/description mentions the stack or general engineering practice. */
        fun relevant(text: String, keywords: List<String>): Boolean {
            val t = text.lowercase()
            val general = listOf("debug", "test", "review", "refactor", "security", "performance", "ci", "git")
            return keywords.any { t.contains(it) } || general.count { t.contains(it) } >= 2
        }
    }

    suspend fun run(repos: List<String>): String {
        val languages = repos.mapNotNull { r -> runCatching { JSONObject(gh.raw("GET", "/repos/$r", null).substringAfter('\n')).optString("language") }.getOrNull() }
            .filter { it.isNotBlank() && it != "null" }
        val keywords = stackKeywords(languages).ifEmpty { listOf("coding") }
        val seen = store.seenRepos()
        val sources = (SkillImporter.RECOMMENDED.map { it.repo } +
            keywords.take(2).flatMap { kw -> runCatching { gh.searchRepos("$kw agent skills SKILL.md stars:>=$MIN_STARS", 8) }.getOrDefault(emptyList()).map { it.fullName } })
            .distinct().filter { it.lowercase() !in seen }
        val existing = skills.globals().map { it.name }.toSet()
        val queued = ArrayList<String>()
        val looked = ArrayList<String>()
        for (repo in sources.take(4)) {
            if (queued.size >= MAX_PER_WEEK) break
            looked += repo
            val found = runCatching { importer.scan(repo) }.getOrDefault(emptyList())
                .filter { it.name !in existing && !it.hasScripts && relevant(it.name + " " + it.description, keywords) }
            for (c in found) {
                if (queued.size >= MAX_PER_WEEK) break
                val s = runCatching { importer.import(c) }.getOrNull() ?: continue
                if (SkillSafety.scan(s).isNotEmpty()) continue
                skills.saveGlobal(s)
                skills.setEnabled(s.name, false)
                if (store.queue(Experiment.skill(s.name, "keşif: $repo"))) queued += s.name
            }
        }
        store.markSeen(looked)
        return if (queued.isEmpty()) "Keşif: ${looked.size} kaynak tarandı, yeni aday yok."
        else "Keşif: ${queued.joinToString { "/$it" }} bulundu, sınava alındı (kapalı eklendi)."
    }
}

/** The user's goal in natural language turned into bounded settings, shown for confirmation before applying. */
data class GoalPlan(
    val summary: String,
    val costWeight: Double? = null,
    val autonomy: Int? = null,
    val examBudget: Double? = null,
    val evoBudget: Double? = null,
    val pool: List<String>? = null,
    val learnedRouting: Boolean? = null,
    val scout: Boolean? = null,
) {
    fun changes(s: Settings): List<String> = buildList {
        costWeight?.let { add("Kalite/maliyet dengesi: ${pct(s.costWeight)} → ${pct(it)} ucuzluk ağırlığı") }
        autonomy?.let { add("Yetki seviyesi: ${s.autonomyLevel} → $it") }
        examBudget?.let { add("Haftalık sınav bütçesi: $${s.examWeeklyBudget} → $$it") }
        evoBudget?.let { add("Günlük öz-gelişim bütçesi: $${s.evoDailyBudget} → $$it") }
        pool?.let { add("Model havuzu: " + it.joinToString { m -> m.substringAfter('/') }) }
        learnedRouting?.let { add("Öğrenen model seçimi: ${if (it) "açık" else "kapalı"}") }
        scout?.let { add("GitHub keşfi: ${if (it) "açık" else "kapalı"}") }
    }

    fun apply(s: Settings) {
        costWeight?.let { s.costWeight = it }
        autonomy?.let { s.autonomyLevel = it }
        examBudget?.let { s.examWeeklyBudget = it }
        evoBudget?.let { s.evoDailyBudget = it }
        pool?.let { s.modelPool = it }
        learnedRouting?.let { s.learnedRouting = it }
        scout?.let { s.scoutEnabled = it }
    }

    private fun pct(v: Double) = "%${(v * 100).toInt()}"

    companion object {
        const val SYSTEM = "You turn a user's goal for their coding assistant into settings. Reply with ONE JSON object only: " +
            "{\"summary\": \"one Turkish sentence\", \"cost_weight\": 0..1 or null (0 = best quality, 1 = cheapest), " +
            "\"autonomy_level\": 1|2|3 or null, \"exam_weekly_budget\": USD or null, \"evo_daily_budget\": USD or null, " +
            "\"model_pool\": [\"provider/model\", ...] or null (only ids from AVAILABLE), \"learned_routing\": true|false|null, " +
            "\"scout\": true|false|null}. Change only what the goal asks for; everything else null."

        fun prompt(goal: String, available: List<String>, s: Settings) = "GOAL: $goal\n\nCURRENT: cost_weight=${s.costWeight}, " +
            "autonomy_level=${s.autonomyLevel}, exam_weekly_budget=${s.examWeeklyBudget}, evo_daily_budget=${s.evoDailyBudget}, " +
            "model_pool=${s.modelPool}, learned_routing=${s.learnedRouting}\n\nAVAILABLE:\n" + available.take(120).joinToString("\n")

        /** Parses and clamps; pool entries not in [available] are dropped. */
        fun parse(text: String, available: List<String>): GoalPlan? {
            val o = ReflectorEngine.extractJson(text) ?: return null
            fun d(k: String) = if (o.has(k) && !o.isNull(k)) o.optDouble(k).takeIf { !it.isNaN() } else null
            fun b(k: String) = if (o.has(k) && !o.isNull(k)) o.optBoolean(k) else null
            val pool = o.optJSONArray("model_pool")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }
                ?.filter { m -> ModelRef.parse(m) != null && available.any { it.equals(m, true) } }?.distinct()?.takeIf { it.size >= 2 }
            return GoalPlan(
                summary = o.optString("summary").ifBlank { "Hedef ayarlara çevrildi." }.take(300),
                costWeight = d("cost_weight")?.coerceIn(0.0, 1.0),
                autonomy = d("autonomy_level")?.toInt()?.coerceIn(1, 3),
                examBudget = d("exam_weekly_budget")?.coerceIn(0.0, 20.0),
                evoBudget = d("evo_daily_budget")?.coerceIn(0.0, 10.0),
                pool = pool,
                learnedRouting = b("learned_routing"),
                scout = b("scout"),
            )
        }
    }
}
