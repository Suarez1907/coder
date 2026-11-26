package com.suarez.coder.decision

import com.suarez.coder.tools.GitHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

enum class Route { SIMPLE_EDIT, COMPLEX_ARCHITECT, QUESTION_ONLY }

data class FilePreview(val path: String, val preview: String?)

data class FileScore(val path: String, val yes: Double, val confidence: Double)

data class RoutingDecision(
    val route: Route,
    /** What the model answered before the confidence rule. */
    val rawRoute: Route?,
    val confidence: Double,
    val upgraded: Boolean,
    val selected: List<FileScore>,
    val totalFiles: Int,
    val manualFiles: Boolean,
    val latencyMs: Long,
    val inputTokens: Long,
    /** Set when Decision 1 could not be used and manual settings apply. */
    val fallbackReason: String? = null,
    val repo: String? = null,
    val ref: String? = null,
    val routeProbabilities: Map<String, Double> = emptyMap(),
    /** Expected difficulty level 0 (trivial) … 3 (very hard), from the same Decision call. */
    val complexity: Double? = null,
    /** Decision model that answered and its reported cost (null = estimate from tokens). */
    val decisionModel: String? = null,
    val costUsd: Double? = null,
    /** Probability the request is specific enough to implement without questions (same Decision call). */
    val clarity: Double? = null,
)

/** Pure decision rules (unit-tested). */
object RouterLogic {
    const val SIMPLE_EDIT_MIN_CONFIDENCE = 0.75
    const val FILE_MIN_CONFIDENCE = 0.60
    const val MAX_SELECTED_FILES = 8
    const val MAX_FILE_QUESTIONS = 300
    const val PREVIEW_LINES = 8
    const val PREVIEW_CHARS = 400

    const val ROUTE_KEY = "route"
    const val COMPLEXITY_KEY = "complexity"
    const val CLARITY_KEY = "clear"
    /** Below this probability of "specific enough", a change request gets 1-3 clarifying questions first. */
    const val CLARITY_MIN = 0.35

    fun clarityQuestion() = DecisionQuestion(
        CLARITY_KEY, "noul",
        "Is the request specific enough that an engineer could implement it in this repository without asking the user anything " +
            "(what to change, where, and how it should behave)? Follow-ups to the previous exchange count as specific when the context makes them clear.",
    )

    fun complexityQuestion() = DecisionQuestion(
        COMPLEXITY_KEY, "score",
        "How difficult is this coding request for an experienced software engineer working in this repository?",
        levels = listOf(
            "Trivial: a one-line, text or style change.",
            "Moderate: a focused change in one or two files with clear logic.",
            "Hard: a feature or fix across several files that needs careful design.",
            "Very hard: architecture changes, complex algorithms or subtle cross-cutting bugs.",
        ),
    )

    fun routeQuestion() = DecisionQuestion(
        ROUTE_KEY, "choice",
        "Classify the user's request to a coding assistant that edits a GitHub repository. Which handling fits best?",
        mapOf(
            Route.SIMPLE_EDIT.name to "A clearly scoped code change: a bug fix in one file, a UI/style/text change, a small refactor, " +
                "debugging a reported error, or a GitHub operation (branch, pull request, merge, workflow).",
            Route.COMPLEX_ARCHITECT.name to "A new feature or structural change across several files that needs architectural design.",
            Route.QUESTION_ONLY.name to "No code change is needed: a question, explanation, research, review, or listing information.",
        ),
    )

    fun fileKey(i: Int) = "f$i"

    fun fileQuestion(i: Int, f: FilePreview) = DecisionQuestion(
        fileKey(i), "noul",
        buildString {
            append("Should the file `").append(f.path).append("` be read or edited to fulfil the user's request?")
            if (!f.preview.isNullOrBlank()) append("\nFirst lines of the file:\n").append(f.preview)
        },
    )

    fun buildInput(request: String, recent: String?, repo: String?, paths: List<String>): String = buildString {
        append("User request to a coding assistant:\n").append(request.trim()).append('\n')
        if (!recent.isNullOrBlank()) append("\nRecent conversation (for context):\n").append(recent.trim()).append('\n')
        if (repo != null && paths.isNotEmpty()) {
            append("\nRepository ").append(repo).append(" contains these files:\n")
            paths.forEach { append(it).append('\n') }
        }
    }

    fun preview(content: String): String =
        content.lineSequence().take(PREVIEW_LINES).joinToString("\n").take(PREVIEW_CHARS)

    /** Orders files so the most likely relevant ones get previews and stay within the question limit. */
    fun rank(request: String, paths: List<String>): List<String> {
        val words = Regex("[\\p{L}\\p{N}_]{3,}").findAll(request.lowercase()).map { it.value }.toSet()
        fun score(p: String): Int {
            val lp = p.lowercase()
            var s = words.count { lp.contains(it) } * 10
            if (Regex("\\.(kt|kts|java|js|jsx|ts|tsx|py|go|rs|swift|dart|c|cpp|h|cs|rb|php|html|css|vue|svelte)$").containsMatchIn(lp)) s += 3
            if (lp.contains("test")) s -= 1
            s -= p.count { it == '/' }
            return s
        }
        return paths.sortedWith(compareByDescending<String> { score(it) }.thenBy { it })
    }

    fun interpret(
        result: DecisionResult,
        files: List<FilePreview>,
        manualFiles: Boolean,
        repo: String?,
        ref: String?,
    ): RoutingDecision {
        val r = result.answers[ROUTE_KEY]
        val raw = r?.choice?.let { c -> Route.entries.firstOrNull { it.name == c } }
        val conf = r?.confidence ?: 0.0
        val (route, upgraded) = when {
            raw == null -> Route.COMPLEX_ARCHITECT to true
            raw == Route.SIMPLE_EDIT && conf < SIMPLE_EDIT_MIN_CONFIDENCE -> Route.COMPLEX_ARCHITECT to true
            else -> raw to false
        }
        val selected = files.mapIndexedNotNull { i, f ->
            val a = result.answers[fileKey(i)] ?: return@mapIndexedNotNull null
            val yes = a.noul ?: return@mapIndexedNotNull null
            if (yes >= 0.5 && a.confidence > FILE_MIN_CONFIDENCE) FileScore(f.path, yes, a.confidence) else null
        }.sortedByDescending { it.yes }.take(MAX_SELECTED_FILES)
        return RoutingDecision(route, raw, conf, upgraded, selected, files.size, manualFiles,
            result.latencyMs, result.inputTokens, null, repo, ref, r?.probabilities ?: emptyMap(),
            result.answers[COMPLEXITY_KEY]?.score, result.model, result.costUsd, result.answers[CLARITY_KEY]?.noul)
    }
}

/**
 * Runs one Decisions API call that both classifies the request and picks relevant files.
 * Never throws: on any failure it returns a decision with [RoutingDecision.fallbackReason] set.
 */
class SmartRouter(
    private val github: GitHub,
    private val listFiles: suspend (repo: String, ref: String?) -> List<GitHub.TreeEntry>,
    private val decide: suspend (input: String, questions: List<DecisionQuestion>) -> DecisionResult,
) {
    private val previewCache = HashMap<String, String?>() // blob sha → preview

    companion object {
        const val PREVIEW_FILES = 80
        const val PREVIEW_BUDGET_MS = 3_000L
    }

    suspend fun route(request: String, recent: String?, repo: String?, ref: String?, manualFiles: Boolean): RoutingDecision {
        return try {
            var files = emptyList<FilePreview>()
            if (repo != null && !manualFiles) {
                val entries = listFiles(repo, ref)
                val byPath = entries.associateBy { it.path }
                val ranked = RouterLogic.rank(request, entries.map { it.path }).take(RouterLogic.MAX_FILE_QUESTIONS)
                prefetchPreviews(repo, ref, ranked.take(PREVIEW_FILES).mapNotNull { byPath[it] })
                files = ranked.map { p -> FilePreview(p, byPath[p]?.sha?.let { previewCache[it] }) }
            }
            val questions = listOf(RouterLogic.routeQuestion(), RouterLogic.complexityQuestion(), RouterLogic.clarityQuestion()) +
                files.mapIndexed { i, f -> RouterLogic.fileQuestion(i, f) }
            val input = RouterLogic.buildInput(request, recent, repo, files.map { it.path })
            RouterLogic.interpret(decide(input, questions), files, manualFiles, repo, ref)
        } catch (e: TimeoutCancellationException) {
            fallback("zaman aşımı", manualFiles)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback(e.message ?: e.javaClass.simpleName, manualFiles)
        }
    }

    private fun fallback(reason: String, manual: Boolean) = RoutingDecision(
        Route.SIMPLE_EDIT, null, 0.0, false, emptyList(), 0, manual, 0, 0, reason,
    )

    /** Loads the first lines of files in parallel; whatever finishes within the budget is used. */
    private suspend fun prefetchPreviews(repo: String, ref: String?, entries: List<GitHub.TreeEntry>) {
        val missing = entries.filter { it.sha.isNotBlank() && !previewCache.containsKey(it.sha) }
        if (missing.isEmpty()) return
        val gate = Semaphore(8)
        withTimeoutOrNull(PREVIEW_BUDGET_MS) {
            coroutineScope {
                missing.map { e ->
                    async {
                        gate.withPermit {
                            val text = runCatching { github.readFileRaw(repo, e.path, ref) }.getOrNull()
                            previewCache[e.sha] = text?.let { RouterLogic.preview(it) }
                        }
                    }
                }.awaitAll()
            }
        }
    }
}
