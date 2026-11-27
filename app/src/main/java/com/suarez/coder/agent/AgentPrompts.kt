package com.suarez.coder.agent

import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The system prompts. Static per mode (no per-request content), so they stay a cacheable prefix. */
internal class AgentPrompts(private val agent: Agent) {
    private val settings get() = agent.settings
    private val tools get() = agent.tools
    private val context get() = agent.context

    /** Static per mode: no date or other per-request content, so the prompt prefix stays identical across requests. */
    internal fun header(): String {
        val repo = (tools.repoOverride ?: settings.defaultRepo).takeIf { it.isNotBlank() }
        return buildString {
            appendLine("You are Coder, a software engineering agent inside an Android app. The user works only from a phone.")
            appendLine("Tools give you GitHub (read, commit, branches, PRs, Actions) and the web (search, fetch).")
            appendLine("You cannot run code on the device; builds and tests run in the repo's GitHub Actions.")
            appendLine("Reply in the user's language (default Turkish). Keep answers short; the screen is small.")
            if (repo != null) appendLine("Default repository: $repo.")
            (context.refOverride ?: settings.defaultBranch).takeIf { it.isNotBlank() }?.let { appendLine("Default branch: $it.") }
            if (settings.customInstructions.isNotBlank()) appendLine("User instructions: ${settings.customInstructions}")
            appendLine(com.suarez.coder.security.Guard.INJECTION_RULE)
            // Rules the self-improvement manager adopted after they won an exam (rarely changes: stays cacheable).
            agent.policyText?.invoke()?.takeIf { it.isNotBlank() }?.let { appendLine("Learned working rules:\n$it") }
            // Progressive disclosure: only one line per skill here; the body comes from load_skill.
            tools.skills?.catalog(tools.activeRepo())?.let { appendLine(it) }
        }
    }

    /** An evolved section (adopted after winning an exam), else the default. */
    private fun section(id: String): String = agent.promptOverrides?.invoke()?.get(id)?.takeIf { PromptSections.problem(id, it) == null }
        ?: PromptSections.default(id)

    internal val tokenRules: String get() = section(PromptSections.TOKEN)

    internal val terseCode = "Terse mode: no greetings, explanations, summaries or commentary. Make code changes only through " +
        "github_apply_diff with SEARCH/REPLACE blocks. Final reply: at most 2 short lines (what changed + link)."

    internal val notesRule = "Project notes: the repo may keep .coder/NOTES.md (under 60 lines: purpose, structure, key files, conventions, " +
        "build/test commands, pitfalls). If it is missing and you make a substantial change, create it; when a change alters " +
        "architecture, conventions or build steps, update it in the same commit."

    fun agentSystem() = buildString {
        append(header()).append('\n').append(tokenRules).append('\n')
        if (settings.terseMode) appendLine(terseCode)
        appendLine(section(PromptSections.WORKFLOW))
        if (settings.projectNotes) appendLine(notesRule)
        if (settings.todoList) appendLine("Task list: for work with 3 or more steps, call update_todos first with the steps, keep exactly one " +
            "item in_progress, and send the full updated list as you finish each step. Skip it for one-step work.")
        appendLine("Impact: before changing a function/class signature, name or behavior, call find_references and update every caller in the same commit.")
        if (settings.docsLookup) appendLine("Library APIs: when you are not sure an API exists in the version the project uses (see DEPENDENCIES), " +
            "or a build error points at a library call, call fetch_live_docs before writing the code. " +
            "Fetched documentation stays in the tool result, not in this prefix.")
        if (settings.semanticSearch && tools.semantic != null && settings.geminiKey.isNotBlank()) appendLine("Finding code: if you do not know which file holds something, use semantic_search (by meaning) before reading files one by one.")
        agent.implementerTactic?.let { appendLine(it) }
    }

    /**
     * Fixed text for every architect call (no step-specific parts), so the system prompt + tools + stable context form
     * an identical, cacheable prefix. [singleShot] is kept for callers; the text no longer depends on it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun architectSystem(hasMap: Boolean, singleShot: Boolean = false) = buildString {
        append(header()).append('\n')
        appendLine(
            """
            You are the ARCHITECT: a read-only planner. You never write code, edit files, produce diffs, run anything or fix
            implementation errors; a cheaper EXECUTOR model does all of that and repairs its own mistakes.
            You are called at most $Agent.MAX_ARCHITECT_CALLS times per task. Choose exactly one:
            1. submit_plan: a short technical contract, NOT code. Per file: path, action (modify/create/delete), the functions/
               classes/signatures to add or change, data flow and dependency notes, numbered steps and acceptance criteria.
               No code blocks; a signature or a one-line expression only when essential.
            2. need_more_info: when the repo map and context are not enough, ask for at most $Agent.MAX_ARCHITECT_READS files once;
               they are given to you and you must then call submit_plan or delegate_to_executor.
            3. delegate_to_executor: for single-file fixes, UI/text/style tweaks, small refactors, clearly scoped changes and
               repository operations (branches, pull requests, merges, workflows, CI). Give short, precise instructions.
            4. If no code change is needed (a question or explanation), answer directly in text.
            If a SKILL from the list applies, name it in the plan or the delegation; the executor loads it with load_skill.
            Branch: use the one the user names; otherwise choose a short new feature branch name.
            """.trimIndent()
        )
        if (settings.terseMode) appendLine("Terse mode: no prose outside the tool call; write plan steps as short imperative bullets.")
        if (hasMap) {
            appendLine(
                "Aim for a plan that touches 1-3 files. Use the REPO MAP in the project context first (paths, types, signatures; " +
                    "bodies removed). Read at most $Agent.MAX_ARCHITECT_READS files, and only when the map is not enough."
            )
        }
        if (settings.projectNotes) appendLine("$notesRule Include .coder/NOTES.md in the plan when that applies.")
    }

    val editorSystem: String get() = """
        You are a code EDITOR. Implement the given plan in the given files by emitting SEARCH/REPLACE blocks ONLY.
        Format for every change:
        path/to/file
        <<<<<<< SEARCH
        exact existing lines
        =======
        new lines
        >>>>>>> REPLACE
    """.trimIndent() + "\n" + section(PromptSections.EXECUTOR) +
        (if (settings.terseMode) "\nNo greetings, explanations, summaries or comments. Return only SEARCH/REPLACE blocks." else "") +
        (agent.implementerTactic?.let { "\n$it" } ?: "")

    fun questionSystem() = buildString {
        append(header()).append('\n').append(tokenRules).append('\n')
        appendLine("This request needs no code change: answer it concisely, using read-only tools if needed. Do not modify the repository.")
    }
}
