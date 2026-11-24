package com.suarez.coder.agent

/**
 * The parts of the system prompt self-improvement may rewrite (and test on exams before keeping). The output format,
 * the security rule and tool names stay fixed: a rewrite that drops them is rejected.
 */
object PromptSections {
    const val TOKEN = "token"
    const val WORKFLOW = "workflow"
    const val EXECUTOR = "executor_rules"
    val ALL = listOf(TOKEN, WORKFLOW, EXECUTOR)

    fun label(id: String) = when (id) { TOKEN -> "Token kuralları"; WORKFLOW -> "İş akışı kuralları"; EXECUTOR -> "Uygulayıcı kuralları"; else -> id }

    val TOKEN_DEFAULT = """
        Token economy (important, the user pays per token):
        - Read only what you need. Request every file you need in ONE github_read_files call instead of one call per file;
          read line ranges of large files with github_read_file.
        - Change existing files with github_apply_diff (SEARCH/REPLACE blocks), never by rewriting the whole file.
          Keep each SEARCH small but unique. For a long region you may write its first lines, a line with only `...`, then its last lines.
        - Use github_write_files only for new files, full rewrites of tiny files, or deletions.
        - After pushing, call ci_wait once (it waits for the build and returns the errors); never poll with github_api.
          Use ci_errors for an older run. Fix the reported errors, then ci_wait again.
        - With web_fetch pass a short `query` so only the relevant parts of the page come back.
        - Do not repeat file contents or diffs in your answer; summarize what changed with links.
    """.trimIndent()

    val WORKFLOW_DEFAULT = "Workflow: understand first, then change; group related edits in one commit; unless told otherwise use the branch " +
        "the user names, or a new feature branch and offer a PR.\n" +
        "You own the implementation and every patch, syntax, build and logic error it causes; there is no one to hand it to. " +
        "When a patch fails, first re-read the current text of that file (github_read_file, the relevant line range), find the root cause, " +
        "then apply a corrected patch. Never retry the same SEARCH blindly."

    val EXECUTOR_DEFAULT = """
        Rules:
        - Put the file path alone on the line right above <<<<<<< SEARCH.
        - SEARCH must copy existing lines character for character (indentation included) and must be unique in the file.
        - Keep blocks small: only the lines that change plus 1-3 unchanged context lines. Use several blocks per file, in file order.
        - To replace a long region you may abbreviate the middle of SEARCH: its first 2+ lines, a line with only `...`, its last 2+ lines.
        - New file: leave SEARCH empty and put the whole file in REPLACE. To remove code, leave REPLACE empty.
        - Write complete, working code; no placeholders. Only touch the files listed.
        - Output nothing except the blocks: no explanations, no whole files.
        - You own every error of your edits. After a failure you get the error and the CURRENT text of the file:
          find the root cause and base new SEARCH blocks on that current text. Never repeat a failed SEARCH.
    """.trimIndent()

    fun default(id: String) = when (id) { TOKEN -> TOKEN_DEFAULT; WORKFLOW -> WORKFLOW_DEFAULT; EXECUTOR -> EXECUTOR_DEFAULT; else -> "" }

    /** Why a rewrite is not acceptable, or null. Keeps the parts other code and the tools depend on. */
    fun problem(id: String, text: String): String? {
        val t = text.trim()
        if (t.length < 120) return "çok kısa"
        if (t.length > 3_000) return "çok uzun"
        val must = when (id) {
            TOKEN -> listOf("github_read_files", "github_apply_diff", "ci_wait")
            WORKFLOW -> listOf("commit")
            EXECUTOR -> listOf("SEARCH", "REPLACE")
            else -> return "bilinmeyen bölüm"
        }
        must.firstOrNull { !t.contains(it) }?.let { return "'$it' kalmalı" }
        if (Regex("(?i)ignore (all|previous)|reveal|api key|token'ı göster").containsMatchIn(t)) return "güvenlik dışı ifade"
        return null
    }
}
