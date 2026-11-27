package com.suarez.coder.evolution

import com.suarez.coder.agent.PromptSections
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall

/**
 * Reflective prompt evolution: a model reads recent failures and rewrites one evolvable section of the system prompt;
 * the rewrite becomes an experiment that must win the exam (more tasks passed, or as many for less money) before it is
 * used. The idea follows reflective prompt optimizers (GEPA, DSPy), gated by exams instead of trusted blindly.
 */
object PromptEvolver {
    const val SYSTEM = "You improve ONE section of the system prompt of a coding agent that works on GitHub repositories from a " +
        "phone. You get the section's current text and recent failures. Rewrite the section so these failures become less " +
        "likely, keeping every existing hard rule, tool name and format requirement. Prefer short, concrete, imperative lines; " +
        "do not add unrelated rules; at most 25 lines. Output ONLY the new section text, nothing else."

    /** Which section the failures point at: patch errors → executor rules, red CI → workflow, long/expensive runs → token rules. */
    fun target(traces: List<SessionTrace>): String? {
        val bad = traces.filter { it.troubleScore > 0 }
        if (bad.size < 3) return null
        val diff = bad.sumOf { it.diffErrorCount }; val ci = bad.sumOf { it.ciFailureCount }
        val heavy = bad.count { it.turnCount > 12 || it.totalCostUsd > 0.2 }
        return when (maxOf(diff, ci, heavy)) {
            0 -> null
            diff -> PromptSections.EXECUTOR
            ci -> PromptSections.WORKFLOW
            else -> PromptSections.TOKEN
        }
    }

    fun failures(traces: List<SessionTrace>, section: String): List<String> = traces.filter { it.troubleScore > 0 }.flatMap { t ->
        when (section) {
            PromptSections.EXECUTOR -> t.diffErrorSamples
            PromptSections.WORKFLOW -> t.ciSignatures.map { "CI: $it" } + (if (t.userCorrection) listOf("User said it was wrong: ${t.request.take(120)}") else emptyList())
            else -> listOf("${t.turnCount} turns, $" + String.format(java.util.Locale.US, "%.3f", t.totalCostUsd) + ": ${t.request.take(120)}")
        }
    }.distinct().take(15)

    fun prompt(section: String, current: String, failures: List<String>) =
        "SECTION (${PromptSections.label(section)}):\n$current\n\nRECENT FAILURES:\n" + failures.joinToString("\n") { "- ${it.take(300)}" }

    /** The model's answer as a section text (fences and preambles removed), or null if unusable. */
    fun clean(section: String, reply: String, current: String): String? {
        val t = reply.trim().removePrefix("```").removeSuffix("```").trim().lines().dropWhile { it.isBlank() || it.endsWith(":") && it.length < 40 && it != "Rules:" }.joinToString("\n").trim()
        if (PromptSections.problem(section, t) != null) return null
        if (t.replace(Regex("\\s+"), " ") == current.replace(Regex("\\s+"), " ")) return null
        return t
    }
}

/**
 * Procedure memory: recurring successful tool sequences ("read the Gradle files → edit the version catalog → edit the
 * wrapper → CI") become skill candidates, tested by exams before they are enabled (Agent Workflow Memory, Voyager).
 */
object ProcedureMiner {
    private val KNOWN = Regex("^(build\\.gradle(\\.kts)?|settings\\.gradle(\\.kts)?|libs\\.versions\\.toml|gradle-wrapper\\.properties|AndroidManifest\\.xml|package\\.json|tsconfig\\.json|project\\.godot|requirements\\.txt|pyproject\\.toml|Dockerfile|README\\.md|NOTES\\.md|.+\\.ya?ml)$")

    fun abstractPath(p: String): String {
        val name = p.trim().substringAfterLast('/')
        if (KNOWN.matches(name)) return if (name.endsWith(".yml") || name.endsWith(".yaml")) (if (p.contains(".github/workflows")) "workflow.yml" else "*.yml") else name
        val ext = name.substringAfterLast('.', "")
        return if (ext.isEmpty()) name else "*.$ext"
    }

    /** One abstract step per meaningful tool call; consecutive duplicates merged. */
    fun steps(run: List<ChatMessage>): List<String> {
        val out = ArrayList<String>()
        fun add(s: String) { if (out.lastOrNull() != s) out += s }
        for (m in run) {
            val a = m as? ChatMessage.Assistant ?: continue
            for (c in a.toolCalls) stepsOf(c).forEach(::add)
        }
        return out.take(40)
    }

    private fun stepsOf(c: ToolCall): List<String> = when (c.name) {
        "github_read_file" -> listOf("read:" + abstractPath(c.args.optString("path")))
        "github_read_files" -> c.args.optJSONArray("paths")?.let { a -> (0 until a.length()).map { "read:" + abstractPath(a.optString(it)) } }.orEmpty().distinct()
        "github_apply_diff" -> Regex("(?m)^([\\w./-]+\\.[\\w]+)\\s*\\n<<<<<<<").findAll(c.args.optString("diff")).map { "edit:" + abstractPath(it.groupValues[1]) }.distinct().toList()
        "github_write_files" -> c.args.optJSONArray("files")?.let { a -> (0 until a.length()).map { "write:" + abstractPath(a.optJSONObject(it)?.optString("path").orEmpty()) } }.orEmpty().distinct()
        "ci_wait" -> listOf("ci")
        "run_cloud_tool" -> listOf("cloud:" + c.args.optString("tool"))
        "fetch_live_docs", "lookup_docs" -> listOf("docs:" + c.args.optString("library").lowercase().take(30))
        "github_create_pull_request" -> listOf("pr")
        "update_todos", "load_skill" -> emptyList()
        else -> listOf(c.name)
    }

    fun lcs(a: List<String>, b: List<String>): List<String> {
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) d[i][j] = if (a[i] == b[j]) 1 + d[i + 1][j + 1] else maxOf(d[i + 1][j], d[i][j + 1])
        val out = ArrayList<String>(); var i = 0; var j = 0
        while (i < a.size && j < b.size) when {
            a[i] == b[j] -> { out += a[i]; i++; j++ }
            d[i + 1][j] >= d[i][j + 1] -> i++
            else -> j++
        }
        return out
    }

    data class Cluster(val traces: List<SessionTrace>, val common: List<String>) {
        val key get() = common.joinToString(">").hashCode().toString(16)
    }

    /** Groups of ≥ [min] successful runs with similar requests sharing ≥ 3 abstract steps in the same order. */
    fun clusters(traces: List<SessionTrace>, min: Int = 3): List<Cluster> {
        val ok = traces.filter { Outcome.of(it) >= 0.8 && it.steps.size >= 3 }.sortedByDescending { it.at }
        val used = HashSet<String>()
        val out = ArrayList<Cluster>()
        for (t in ok) {
            if (t.id in used) continue
            val members = listOf(t) + ok.filter { o -> o.id != t.id && o.id !in used &&
                com.suarez.coder.agent.SimilarTasks.similarity(o.request, t.request) >= 0.2 && lcs(o.steps, t.steps).size >= 3 }
            if (members.size < min) continue
            val common = members.map { it.steps }.reduce { a, b -> lcs(a, b) }
            if (common.size < 3 || common.none { it.startsWith("edit:") || it.startsWith("write:") }) continue
            members.forEach { used += it.id }
            out += Cluster(members, common)
        }
        return out
    }

    const val SYSTEM = "Write a reusable skill (SKILL.md) for a coding agent from a procedure it has done successfully several times. " +
        "Output only the file: YAML front matter with name (kebab-case, starting with proc-), description (one line, in Turkish) and " +
        "triggers (3-6 Turkish/English keywords), then a short Markdown body: when to use it, the numbered steps (general, not " +
        "tied to one repo), and the pitfalls seen. At most 40 lines. No secrets, no shell commands that download and run code."

    fun prompt(c: Cluster) = "REQUESTS:\n" + c.traces.take(6).joinToString("\n") { "- ${it.request.take(200)} (${it.repo})" } +
        "\n\nCOMMON STEPS (abstracted):\n" + c.common.joinToString("\n") { "- $it" }
}
