package com.suarez.coder.decision

import com.suarez.coder.cost.Pricing
import com.suarez.coder.harness.PreFlightValidator
import kotlinx.coroutines.withTimeout

class PrePushGate {
    enum class Verdict {
        APPROVE,
        WARN_SCOPE_DRIFT,
        REQUIRE_ARCHITECT_REVIEW,
    }

    data class GateResult(
        val verdict: Verdict,
        val reason: String = "",
        val triggered: Boolean = false,
        val timedOutOrFailed: Boolean = false,
        val costUsd: Double = 0.0,
        val inputTokens: Long = 0,
        val model: String? = null,
    )

    companion object {
        const val MIN_FILES = 3
        const val MIN_CHANGED_LINES = 120
        const val MAX_INPUT_CHARS = 2_500
        const val TIMEOUT_MS = 3_000L
    }

    private var warnedOnce = false
    private var architectReviewedOnce = false

    fun isCriticalPath(path: String): Boolean {
        val p = path.trimStart('/')
        return p == "build.gradle.kts" ||
            p.endsWith("/build.gradle.kts") ||
            p.startsWith(".github/workflows/") ||
            p == "AndroidManifest.xml" ||
            p.endsWith("/AndroidManifest.xml") ||
            p.startsWith("evolution/") ||
            p.contains("/evolution/") ||
            p.startsWith("harness/") ||
            p.contains("/harness/")
    }

    fun shouldTrigger(
        files: List<PreFlightValidator.StagedFile>,
        addedLines: Int,
        removedLines: Int,
    ): Boolean {
        if (files.isEmpty()) return false
        return files.size >= MIN_FILES ||
            (addedLines + removedLines) >= MIN_CHANGED_LINES ||
            files.any { isCriticalPath(it.path) }
    }

    fun summarizeTask(task: String): String {
        val clean = task.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return ""
        val sentences = clean.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ")
        return if (sentences.length <= 400) sentences else sentences.take(397) + "..."
    }

    fun buildCompactInput(
        task: String,
        files: List<PreFlightValidator.StagedFile>,
        addedLines: Int,
        removedLines: Int,
    ): String {
        val sb = StringBuilder()
        sb.append("Task: ").append(summarizeTask(task)).append('\n')
        sb.append("Stats: files=").append(files.size)
            .append(", +").append(addedLines)
            .append(", -").append(removedLines).append('\n')
        sb.append("Files:\n")
        for (f in files) {
            val line = "- ${f.path} (${(f.after ?: "").length} chars)\n"
            if (sb.length + line.length > MAX_INPUT_CHARS) break
            sb.append(line)
        }
        val out = sb.toString()
        return if (out.length <= MAX_INPUT_CHARS) out else out.take(MAX_INPUT_CHARS)
    }

    private fun gateQuestion(): DecisionQuestion = DecisionQuestion(
        key = "pre_push_gate",
        type = "choice",
        instructions = "Review staged changes for scope drift or architectural risk.",
        criteria = mapOf(
            Verdict.APPROVE.name to "Changes match task scope and carry low architectural risk",
            Verdict.WARN_SCOPE_DRIFT.name to "Noticeable scope drift from task",
            Verdict.REQUIRE_ARCHITECT_REVIEW.name to "High architectural risk requiring architect review",
        ),
    )

    suspend fun check(
        task: String,
        files: List<PreFlightValidator.StagedFile>,
        addedLines: Int,
        removedLines: Int,
        decide: suspend (String, List<DecisionQuestion>) -> DecisionResult,
    ): GateResult {
        if (!shouldTrigger(files, addedLines, removedLines)) {
            return GateResult(verdict = Verdict.APPROVE, triggered = false)
        }

        val input = buildCompactInput(task, files, addedLines, removedLines)
        return try {
            val result = withTimeout(TIMEOUT_MS) {
                decide(input, listOf(gateQuestion()))
            }
            val rawChoice = result.answers["pre_push_gate"]?.choice?.trim().orEmpty()
            var verdict = runCatching { Verdict.valueOf(rawChoice) }.getOrDefault(Verdict.APPROVE)
            if (verdict == Verdict.WARN_SCOPE_DRIFT) {
                if (warnedOnce) verdict = Verdict.APPROVE else warnedOnce = true
            } else if (verdict == Verdict.REQUIRE_ARCHITECT_REVIEW) {
                if (architectReviewedOnce) verdict = Verdict.APPROVE else architectReviewedOnce = true
            }
            val modelName = result.model.orEmpty()
            val cost = result.costUsd ?: if (modelName.isNotBlank()) {
                Pricing.auditCost(inputTokens = result.inputTokens, model = modelName)
            } else {
                0.0
            }
            GateResult(
                verdict = verdict,
                reason = rawChoice,
                triggered = true,
                timedOutOrFailed = false,
                costUsd = cost,
                inputTokens = result.inputTokens,
                model = result.model,
            )
        } catch (_: Exception) {
            GateResult(
                verdict = Verdict.APPROVE,
                reason = "fail-open",
                triggered = true,
                timedOutOrFailed = true,
            )
        }
    }
}
