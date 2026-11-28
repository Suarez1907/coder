package com.suarez.coder.decision

import com.suarez.coder.agent.ReadOnlyMismatchRecovery

/**
 * Pure access decision for one request. The architect model id is copied through unchanged: task routing never
 * swaps the user's architect. A write-intent task that was classified as a question still starts READ_WRITE.
 */
object RouterAccessPolicy {
    data class Outcome(val route: Route, val phase: String, val architectModel: String, val implementerModel: String)

    /** Tool names that unambiguously require write access. */
    private val WRITE_TOOL_NAMES = listOf(
        "github_apply_diff", "github_write_files", "github_create_branch", "github_create_pull_request",
        "github_merge_pull_request", "github_run_workflow", "code_mod_rename_symbol", "code_mod_update_imports",
        "code_mod_move_file", "codemod", "apply_blueprint",
    )

    /** Verbs that request an actual repository change. */
    private val WRITE_VERBS = listOf(
        "ekle", "düzelt", "değiştir", "güncelle", "oluştur", "sil", "kaldır", "yaz", "taşı",
        "uygula", "commit", "push", "implement", "fix", "add", "update", "change", "remove", "create", "delete", "refactor",
    )

    /** Explicit read-only wording wins over a bare verb in the same sentence. */
    private val READ_ONLY_PHRASES = listOf(
        "sadece incele", "yalnızca incele", "sadece gözden geçir", "yalnızca gözden geçir",
        "sadece analiz", "yalnızca analiz", "sadece oku", "yalnızca oku", "just inspect", "only inspect", "only review",
    )

    /** Negated write forms, e.g. "değiştirme" or "commit etme". */
    private val NEGATED_WRITE = Regex(
        "(?i)\\b(değiştir(?:me|meden|meden)|düzelt(?:me|meden|meden)|ekle(?:me|meden|meden)|güncelle(?:me|meden|meden)|" +
            "oluştur(?:ma|madan|madan)|yaz(?:ma|madan|madan)|commit(?: etm|iyor)|push(?: etm|iyor)|" +
            "implement(?: etm|ing)|fix(?: etm|ing))\\b",
    )

    /** True when the request explicitly asks for a repository mutation and does not negate it. */
    fun hasWriteIntent(request: String): Boolean {
        val text = request.lowercase()
        if (READ_ONLY_PHRASES.any { text.contains(it) }) return false
        val negated = NEGATED_WRITE.containsMatchIn(text)
        if (negated) return false
        return WRITE_TOOL_NAMES.any { text.contains(it) } || WRITE_VERBS.any { verb ->
            Regex("(?i)(?<![\\p{L}])${Regex.escape(verb)}(?![\\p{L}])").containsMatchIn(text)
        }
    }

    fun decide(request: String, architectModel: String, implementerModel: String, forcedRoute: Route? = null): Outcome {
        val route = forcedRoute ?: LocalRouter.classify(request, pinnedFiles = 0)?.route ?: Route.COMPLEX_ARCHITECT
        val readOnly = !hasWriteIntent(request) && ReadOnlyMismatchRecovery.readOnlyForRoute(route, request)
        return Outcome(route, if (readOnly) "READ_ONLY" else "READ_WRITE", architectModel, implementerModel)
    }
}
