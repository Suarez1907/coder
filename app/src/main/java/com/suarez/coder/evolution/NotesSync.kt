package com.suarez.coder.evolution

import com.suarez.coder.tools.GitHub

/**
 * Folds the scoped-rule summary into `.coder/NOTES.md` for a commit the user already asked for.
 * It never creates a commit by itself and never merges.
 */
object NotesSync {
    const val PATH = ".coder/NOTES.md"

    fun attach(existing: String?, repo: String, files: List<GitHub.FileChange>, rules: ScopedRuleStore, enabled: Boolean): List<GitHub.FileChange> {
        if (!enabled || repo.isBlank()) return files
        if (files.any { it.path == PATH }) return files
        val body = rules.syncNotes(existing.orEmpty(), repo)
        if (body.isBlank() || body == existing.orEmpty()) return files
        return files + GitHub.FileChange(PATH, body, false)
    }

    suspend fun attachFromGitHub(
        github: GitHub,
        rules: ScopedRuleStore?,
        repo: String,
        branch: String?,
        files: List<GitHub.FileChange>,
        enabled: Boolean,
    ): List<GitHub.FileChange> {
        val store = rules ?: return files
        if (!enabled) return files
        val existing = runCatching { github.readFileRaw(repo, PATH, branch) }.getOrNull()
        return attach(existing, repo, files, store, enabled = true)
    }
}
