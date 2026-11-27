package com.suarez.coder.evolution

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Promotes a CI-verified lesson out of one repository once it has earned its keep, after a deterministic privacy
 * filter. Promoted rules are injected only for a matching technology, and only inside the per-turn cap.
 */
class CrossRepoKnowledge(private val file: File) {
    @Synchronized fun all(): List<GeneralRule> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { runCatching { GeneralRule.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    /**
     * [abstractor] may be an evolution-model paraphrase. Its output is never stored raw: [PrivacyFilter] runs after it
     * and drops anything that still contains a repo name, URL or token.
     */
    @Synchronized fun promote(
        rules: List<ScopedRule>,
        abstractor: (ScopedRule) -> String = { deterministicAbstract(it) },
    ): List<GeneralRule> {
        val repos = rules.map { it.repo }.filter { it.isNotBlank() }.distinct()
        val eligible = eligible(rules)
        val kept = all().toMutableList()
        val created = ArrayList<GeneralRule>()
        for (rule in eligible) {
            val tag = techOf(rule) ?: continue
            val filtered = PrivacyFilter.apply(abstractor(rule), repos)
            val text = filtered.text ?: continue
            if (filtered.rejected) continue
            if (repos.any { it.isNotBlank() && text.contains(it, true) }) continue
            if (kept.any { it.techTag == tag && it.text == text }) continue
            val g = GeneralRule(UUID.randomUUID().toString().take(8), tag, text, repos.size.coerceAtLeast(1))
            kept += g
            created += g
        }
        if (created.isNotEmpty()) save(kept)
        return created
    }

    fun matching(paths: List<String>): List<GeneralRule> = all().filter { matches(it.techTag, paths) }

    private fun save(rules: List<GeneralRule>) {
        file.parentFile?.mkdirs()
        file.writeText(JSONArray().also { a -> rules.forEach { a.put(it.toJson()) } }.toString())
    }

    companion object {
        fun eligible(rules: List<ScopedRule>): List<ScopedRule> {
            val active = rules.filter { !it.archived && it.ruleText.isNotBlank() }
            val byText = active.groupBy { it.ruleText.trim().lowercase() }
            return active.filter { rule ->
                val shared = byText[rule.ruleText.trim().lowercase()].orEmpty().map { it.repo.lowercase() }.distinct().size >= 2
                (rule.source == RuleSource.CI_VERIFIED && rule.preventedErrorCount >= 2) || shared
            }.distinctBy { it.ruleText.trim().lowercase() + "|" + (techOf(it) ?: "") }
        }

        fun techOf(rule: ScopedRule): String? {
            val blob = (rule.scopeGlobs + listOf(rule.ruleText)).joinToString(" ").lowercase()
            return when {
                blob.contains(".gd") || blob.contains("gdscript") || blob.contains("godot") -> "godot-gdscript"
                blob.contains("blender") || blob.contains(".py") || blob.contains("bpy") -> "blender-bpy"
                blob.contains(".yml") || blob.contains(".yaml") || blob.contains("github/workflows") || blob.contains("actions") -> "github-actions"
                blob.contains("gradle") -> "android-gradle"
                blob.contains(".kt") || blob.contains("compose") || blob.contains("kotlin") -> "kotlin-compose"
                else -> null
            }
        }

        fun matches(techTag: String, paths: List<String>): Boolean {
            val ext = paths.map { it.substringAfterLast('.', "").lowercase().substringBefore('?') }
            val blob = paths.joinToString(" ").lowercase()
            return when (techTag) {
                "godot-gdscript" -> ext.any { it == "gd" }
                "blender-bpy" -> ext.any { it == "py" } || blob.contains("blender")
                "github-actions" -> ext.any { it == "yml" || it == "yaml" } || blob.contains(".github/workflows")
                "android-gradle" -> paths.any { it.endsWith(".gradle") || it.endsWith(".gradle.kts") }
                "kotlin-compose" -> ext.any { it == "kt" || it == "kts" }
                else -> false
            }
        }

        fun deterministicAbstract(rule: ScopedRule): String {
            val tag = techOf(rule) ?: "genel"
            return "$tag: ${rule.ruleText.trim()}"
        }

        /**
         * Repo rules first, then general rules whose technology is present in [paths]. Hard cap is 5 per turn.
         * The returned list is for the volatile task context only.
         */
        fun merge(repoRules: List<ScopedRule>, general: List<GeneralRule>, paths: List<String>, limit: Int = ScopedRuleStore.MAX_PER_TURN): List<ScopedRule> {
            val cap = limit.coerceAtLeast(0)
            val head = repoRules.take(cap)
            val room = cap - head.size
            if (room <= 0) return head
            val extra = general.filter { matches(it.techTag, paths) }.take(room).map { it.asScoped() }
            return head + extra
        }
    }
}

data class GeneralRule(val id: String, val techTag: String, val text: String, val fromRepos: Int) {
    fun asScoped() = ScopedRule(id, "*", listOf(techTag), text, RuleSource.CI_VERIFIED)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("tag", techTag).put("text", text).put("repos", fromRepos)

    companion object {
        fun fromJson(o: JSONObject) = GeneralRule(o.getString("id"), o.optString("tag"), o.optString("text"), o.optInt("repos"))
    }
}

/** Deterministic mask. A leftover token or a known repo name rejects the text instead of storing it. */
object PrivacyFilter {
    data class Result(val text: String?, val rejected: Boolean, val reason: String?)

    private val TOKEN = Regex("""(?i)(ghp_[A-Za-z0-9]+|github_pat_[A-Za-z0-9_]+|sk-[A-Za-z0-9]{8,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]+|Bearer\s+[A-Za-z0-9._\-]+)""")
    private val URL = Regex("""https?://\S+""")
    private val PAIR = Regex("""\b([A-Za-z0-9_.-]{2,})/([A-Za-z0-9_.-]{2,})\b""")
    private val PATHY = setOf(
        "app", "src", "main", "java", "test", "res", "com", "org", "android", "github", "workflows",
        "kotlin", "resources", "debug", "release", "gradle",
    )

    fun apply(text: String, repoNames: List<String> = emptyList()): Result {
        var t = text.replace(TOKEN, "<token>").replace(URL, "<url>")
        for (name in repoNames.map { it.trim() }.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            t = t.replace(name, "<repo>", ignoreCase = true)
        }
        t = PAIR.replace(t) { m ->
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            if (a.lowercase() in PATHY || '.' in b) m.value else "<repo>"
        }
        val reason = when {
            TOKEN.containsMatchIn(t) -> "token"
            URL.containsMatchIn(t) -> "url"
            repoNames.any { it.isNotBlank() && t.contains(it, true) } -> "repo"
            t.trim().length < 8 -> "kısa"
            else -> null
        }
        return if (reason != null) Result(null, true, reason) else Result(t.trim().replace(Regex("\\s+"), " "), false, null)
    }
}
