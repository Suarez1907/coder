package com.suarez.coder.agent

import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Hard rules that hold whatever a file, page, issue or auto-approve says (see security/Guard). */
internal class SafetyPolicy(private val agent: Agent) {
    private val settings get() = agent.settings
    private val tools get() = agent.tools
    private val context get() = agent.context

    /** Repos the user approved writing to in this session (besides the configured ones). */
    internal val confirmedRepos = HashSet<String>()

    /** Repos the user works with: writes elsewhere (e.g. pushed by injected text) need a confirmation. */
    internal fun knownRepos(): Set<String> = (listOf(settings.defaultRepo, settings.contextRepo) + settings.profiles().map { it.repo } +
        listOfNotNull(tools.repoOverride, context.repoOverride)).map { tools.normalizeRepo(it).lowercase() }.filter { it.isNotBlank() }.toSet() + confirmedRepos

    /** (title, body) of the security question this call needs, or null. */
    fun securityQuestion(call: ToolCall, method: String?): Pair<String, String>? {
        if (call.name == "github_api") {
            val path = call.args.optString("path")
            com.suarez.coder.security.Guard.dangerousApi(method, path)?.let { what ->
                return "Tehlikeli GitHub işlemi" to "Bu çağrı $what:\n${method ?: "GET"} $path\n\nOtomatik onay açık olsa da sorulur. Bunu sen mi istedin?"
            }
        }
        if (call.name in ToolRegistry.WRITE_TOOLS && call.name != "ci_wait") {
            val repo = call.args.optString("repo").takeIf { it.isNotBlank() }?.let { tools.normalizeRepo(it).lowercase() } ?: return null
            val known = knownRepos()
            if (known.isNotEmpty() && repo !in known) {
                return "Başka bir repoya yazma" to "${call.name} → $repo\nBu repo ayarlarındaki projelerden biri değil. Yazılsın mı?"
            }
        }
        return null
    }

    /** Queue runs end with a pull request: merging and raw write calls are left to the user. */
    fun unattendedDenied(name: String, method: String?): String? = when {
        name == "github_merge_pull_request" -> "Kuyruk görevi merge yapamaz; PR açık bırakılır, kullanıcı birleştirir."
        name == "github_api" && (method ?: "GET").uppercase() != "GET" -> "Kuyruk görevinde github_api yalnızca GET olabilir."
        else -> null
    }
}
