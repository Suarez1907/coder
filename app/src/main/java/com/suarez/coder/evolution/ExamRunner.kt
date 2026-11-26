package com.suarez.coder.evolution

import android.content.Context
import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.agent.StopException
import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.ToolCall
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Replays a past task as an exam question: a scratch branch at the task's original commit, the agent under the
 * configuration being tested, then CI on that branch. Every write is locked to the scratch branch (no PRs, merges,
 * workflow dispatches); the branch is deleted afterwards. The weekly exam budget is checked before every model call.
 */
class ExamRunner(
    private val context: Context,
    private val settings: Settings,
    private val budgetLeft: () -> Double,
    private val onStatus: (String) -> Unit = {},
) {
    /** The configuration under test; empty fields mean "as the user has it now". */
    data class Variant(val roles: Map<String, ModelRef> = emptyMap(), val rules: List<String>, val skills: Set<String> = emptySet(), val tag: String,
                       /** Prompt sections to try on top of the adopted ones (PROMPT experiments). */
                       val prompts: Map<String, String> = emptyMap())

    companion object {
        const val BRANCH_PREFIX = "coder-exam/"
        const val RUN_TIMEOUT_MS = 20 * 60_000L

        fun branchFor(case: ExamCase, tag: String) =
            BRANCH_PREFIX + case.id.take(8) + "-" + tag.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(20) + "-" + System.currentTimeMillis() % 100_000

        /** Write lock for exam runs: only commits to the one scratch branch of the one repository. */
        fun guard(repo: String, branch: String): (String, String?) -> String? = { r, b ->
            when {
                b == null -> "Sınav çalışmasında PR, merge, workflow ve API yazma işlemleri yasak."
                !r.equals(repo, true) || b != branch -> "Sınav çalışması yalnızca $repo@$branch dalına yazabilir."
                else -> null
            }
        }

        fun prompt(case: ExamCase, branch: String) = case.request.trim() +
            "\n\n(Exam run: repository ${case.repo}, branch $branch, already created at the task's starting commit. " +
            "Work only on this branch; do not open pull requests, merge or start workflows. Commit your change to $branch.)"

        fun ciPassed(report: String) = !report.contains("\nHatalar (") && report.contains(": success")
        fun ciMissing(report: String) = report.contains("başlayan bir workflow yok") || report.contains("Zaman aşımı")
    }

    private val approveAll = object : AgentUi {
        override fun update() {}
        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String) = true
    }

    /** null = the question could not be scored (no CI ran on the branch). */
    /**
     * [realConfig]: run with the user's full configuration (self-review, until-green, critic...) as an unattended run,
     * for release evaluations; otherwise as an isolated evolution session (experiments).
     */
    suspend fun score(case: ExamCase, v: Variant, realConfig: Boolean = false): ExamScore? {
        val main = Agent(settings) // unguarded: creates and deletes the scratch branch, waits for CI
        val branch = branchFor(case, v.tag)
        onStatus("Sınav: ${case.request.take(40)} (${v.tag})")
        main.tools.github.createBranchAt(case.repo, branch, case.baseSha)
        var spent = 0.0
        try {
            val agent = Agent(settings, context.filesDir).apply {
                if (realConfig) unattended = true else isEvolutionSession = true // never recorded, never chained
                budgetDeferred = true
                roleOverrides = v.roles
                policyText = { v.rules.joinToString("\n") { "- $it" } }
                val adopted = runCatching { EvolutionEngine.storeFor(this@ExamRunner.context).promptOverrides() }.getOrDefault(emptyMap())
                promptOverrides = { adopted + v.prompts }
                onUsage = { spent += it }
                beforeCall = { if (spent >= budgetLeft()) throw StopException("Haftalık sınav bütçesi doldu.") }
                tools.repoOverride = case.repo
                context.repoOverride = case.repo
                context.refOverride = branch
                tools.skills?.forceEnabled = v.skills
                tools.github.writeGuard = guard(case.repo, branch)
            }
            runCatching { withTimeout(RUN_TIMEOUT_MS) { agent.run(mutableListOf(ChatMessage.User(prompt(case, branch))), approveAll) } }
                .exceptionOrNull()?.let { if (it is StopException) throw it }
            val head = runCatching { main.tools.github.headSha(case.repo, branch) }.getOrNull()
            if (head == null || head == case.baseSha) return ExamScore(0, 1, spent) // nothing committed: failed
            onStatus("Sınav: CI bekleniyor")
            val ci = main.tools.execute(ToolCall("exam_ci", "ci_wait", JSONObject().put("repo", case.repo).put("ref", branch).put("timeout_minutes", 20)),
                readOnly = false, allowOps = true)
            if (ciMissing(ci)) return null
            return ExamScore(if (ciPassed(ci)) 1 else 0, 1, spent)
        } finally {
            runCatching { main.tools.github.deleteBranch(case.repo, branch) }
            lastCost = spent
        }
    }

    /** Cost of the last [score] call (also when it threw). */
    var lastCost = 0.0
        private set
}
