package com.suarez.coder.agent

import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Context added to a request: @-mentions and the library versions the project uses. */
internal class RequestContext(private val agent: Agent) {
    private val settings get() = agent.settings
    private val tools get() = agent.tools
    private val context get() = agent.context

    internal val depCache = HashMap<String, List<String>>()

    /** @file, @ci, @diff, @url in the request become one context block (read once, before routing). */
    internal suspend fun mentionContext(text: String): ChatMessage.ContextBlock? {
        val ms = Mentions.parse(text)
        if (ms.isEmpty()) return null
        val repo = tools.activeRepo()
        val ref = context.ref?.ifBlank { null }
        val parts = ArrayList<String>()
        val files = ms.filterIsInstance<Mentions.Mention.File>().map { it.path }
        if (files.isNotEmpty() && repo.isNotBlank()) runCatching { tools.readFilesText(repo, files, ref) }.onSuccess { parts += it }
        for (m in ms) runCatching {
            when (m) {
                Mentions.Mention.Ci -> {
                    val runs = JSONObject(tools.github.raw("GET", "/repos/$repo/actions/runs?per_page=1" + (ref?.let { "&branch=$it" } ?: ""), null).substringAfter('\n'))
                    val r = runs.optJSONArray("workflow_runs")?.optJSONObject(0)
                    parts += if (r == null) "@ci: çalıştırma yok." else "@ci: ${r.optString("name")} ${r.optString("conclusion").ifBlank { r.optString("status") }} " +
                        "(${r.optString("html_url")})\n" + (if (r.optString("conclusion") == "failure") tools.ciErrors(repo, r.optLong("id")) else "")
                }
                Mentions.Mention.Diff -> parts += "@diff:\n" + tools.github.lastCommitDiff(repo, ref)
                is Mentions.Mention.Url -> parts += "@${m.url}:\n" + tools.fetchUrl(m.url)
                is Mentions.Mention.File -> Unit
            }
        }.onFailure { e -> if (e is CancellationException) throw e; parts += "@ bağlamı okunamadı: ${e.message?.take(160)}" }
        if (parts.isEmpty()) return null
        return ChatMessage.ContextBlock("Context the user referenced with @:\n\n" + parts.joinToString("\n\n").take(60_000),
            "@ " + ms.joinToString(", ") { when (it) { is Mentions.Mention.File -> it.path; Mentions.Mention.Ci -> "ci"; Mentions.Mention.Diff -> "diff"; is Mentions.Mention.Url -> it.url } })
    }

    /** Library versions of the project, read once per repo (for fetch_live_docs and to avoid outdated APIs). */
    internal suspend fun dependencies(): List<String> {
        val repo = context.repo.takeIf { it.isNotBlank() }?.let { tools.normalizeRepo(it) } ?: return emptyList()
        depCache[repo]?.let { return it }
        val files = runCatching { tools.github.readFilesBatch(repo, context.ref?.ifBlank { null }, DepVersions.FILES) }.getOrDefault(emptyMap())
            .mapNotNull { (p, t) -> t?.let { p to it } }.toMap()
        return DepVersions.extract(files).also { depCache[repo] = it }
    }
}
