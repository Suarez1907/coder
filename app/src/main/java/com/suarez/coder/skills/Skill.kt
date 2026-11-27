package com.suarez.coder.skills

/** One skill: a catalog line for the system prompt, and a body that is only sent when the agent loads it. */
data class Skill(
    val name: String,
    val description: String,
    val triggers: List<String> = emptyList(),
    /** Cloud tool this skill uses with run_cloud_tool ("blender" / "toolbox"), if any. */
    val workflow: String? = null,
    val body: String,
    val source: Source = Source.GLOBAL,
    val enabled: Boolean = true,
    /** Repo skills: the SKILL.md path in the repository. */
    val path: String? = null,
    /** Imported skills: where they came from ("owner/repo@ref:path/SKILL.md"), for "Güncelle". */
    val origin: String? = null,
    /** Files bundled next to SKILL.md (references, scripts, templates), by path relative to the skill folder. */
    val resources: Map<String, String> = emptyMap(),
    val status: Status = Status.ACTIVE,
    val usageCount: Int = 0,
    val ciSuccesses: Int = 0,
    val ciAttempts: Int = 0,
    val consecutiveCiFailures: Int = 0,
    val reviewNote: String? = null,
) {
    /** Bundled scripts expect a computer; Coder can only run them in GitHub Actions. */
    val hasScripts get() = resources.keys.any { SkillImporter.isScript(it) }

    /** Null until a load has been followed by a CI result. */
    val postLoadCiSuccessRate: Double? get() = if (ciAttempts == 0) null else ciSuccesses.toDouble() / ciAttempts

    fun statLine(): String {
        val ci = postLoadCiSuccessRate?.let { " · %${(it * 100).toInt()} CI" }.orEmpty()
        return "$usageCount kullanım$ci"
    }

    enum class Source(val label: String) {
        BUILTIN("yerleşik"), GLOBAL("genel"), REPO("repo"), AUTO_SYNTHESIZED("otomatik sentez"),
    }

    enum class Status { ACTIVE, DRAFT, REVIEW_REQUIRED }

    /** One catalog line: kept short and stable so the system prompt prefix stays cacheable. */
    fun catalogLine() = "- $name: ${description.lines().first().take(140)}"

    fun toMarkdown(): String = buildString {
        append("---\n")
        append("name: ").append(name).append('\n')
        append("description: ").append(description.lines().first()).append('\n')
        if (triggers.isNotEmpty()) append("triggers: [").append(triggers.joinToString(", ")).append("]\n")
        workflow?.let { append("workflow: ").append(it).append('\n') }
        append("---\n\n").append(body.trim()).append('\n')
    }
}

/**
 * Reads SKILL.md: optional YAML front matter (name, description, triggers, workflow) then the Markdown body.
 * Tolerant by design: unknown keys are ignored, broken front matter falls back to the file name and the first line,
 * and lists may be written inline ([a, b]) or as "- item" lines.
 */
object SkillParser {
    private val NAME_RE = Regex("^[a-z0-9][a-z0-9-]{0,47}$")

    fun normalizeName(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(48)

    fun isValidName(s: String) = NAME_RE.matches(s)

    fun parse(text: String, fallbackName: String = "", source: Skill.Source = Skill.Source.GLOBAL, path: String? = null): Skill? {
        val src = text.replace("\r\n", "\n").trimStart('﻿')
        var meta = emptyMap<String, Any>()
        var body = src
        if (src.trimStart().startsWith("---")) {
            val start = src.indexOf("---")
            val end = src.indexOf("\n---", start + 3)
            if (end > 0) {
                meta = parseFrontMatter(src.substring(start + 3, end))
                body = src.substring(end + 4).substringAfter('\n', "")
            }
        }
        val name = normalizeName((meta["name"] as? String)?.ifBlank { null } ?: fallbackName)
        if (!isValidName(name)) return null
        val bodyText = body.trim()
        val description = ((meta["description"] as? String)?.ifBlank { null }
            ?: bodyText.lines().firstOrNull { it.isNotBlank() }?.trimStart('#', ' ')
            ?: name).trim()
        @Suppress("UNCHECKED_CAST")
        val triggers = when (val t = meta["triggers"]) {
            is List<*> -> (t as List<String>)
            is String -> t.split(',').map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
        val workflow = (meta["workflow"] as? String)?.trim()?.lowercase()?.removePrefix("coder-")?.removeSuffix(".yml")?.ifBlank { null }
        return Skill(name, description, triggers, workflow, bodyText, source, path = path)
    }

    /** A small YAML subset: `key: value`, `key: [a, b]`, `key:` followed by `- item` lines, quotes stripped. */
    fun parseFrontMatter(block: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        var listKey: String? = null
        for (raw in block.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val item = Regex("^\\s*-\\s+(.*)$").find(line)
            if (item != null && listKey != null) {
                @Suppress("UNCHECKED_CAST")
                (out.getOrPut(listKey) { ArrayList<String>() } as? MutableList<String>)?.add(unquote(item.groupValues[1]))
                continue
            }
            val kv = Regex("^([A-Za-z_][A-Za-z0-9_-]*)\\s*:\\s*(.*)$").find(line.trim()) ?: continue
            val key = kv.groupValues[1].lowercase()
            val value = kv.groupValues[2].trim()
            listKey = null
            when {
                value.isEmpty() -> { listKey = key; out[key] = ArrayList<String>() }
                value.startsWith("[") && value.endsWith("]") ->
                    out[key] = value.substring(1, value.length - 1).split(',').map { unquote(it.trim()) }.filter { it.isNotBlank() }
                else -> out[key] = unquote(value)
            }
        }
        return out
    }

    private fun unquote(s: String) = s.trim().removeSurrounding("\"").removeSurrounding("'")
}
