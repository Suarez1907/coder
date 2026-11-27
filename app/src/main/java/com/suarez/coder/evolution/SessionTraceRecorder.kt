package com.suarez.coder.evolution

import com.suarez.coder.llm.ChatMessage
import java.util.UUID

/**
 * Turns the messages of one finished run into a [SessionTrace]. Pure: reads only what the run already produced
 * (usage records, notices, tool results, route info), so recording costs nothing.
 */
object SessionTraceRecorder {
    private val CORRECTION = Regex(
        "(?i)\\b(hayır|yanlış|olmadı|olmamış|çalışmıyor|bozuldu|bozdun|geri al|revert|hala|hâlâ|tekrar dene|düzelmedi|wrong|broken|doesn'?t work|undo)\\b",
    )

    /** A follow-up message that says the previous result was wrong. */
    fun isCorrection(text: String) = CORRECTION.containsMatchIn(text)

    /**
     * Returns null for evolution runs (they are never evaluated, so they can never start another evolution)
     * and for runs without any coding/tool step.
     */
    fun record(
        run: List<ChatMessage>,
        request: String,
        sessionId: String,
        repo: String,
        isEvolutionSession: Boolean,
        costOf: (ChatMessage.UsageRecord) -> Double,
        now: Long = System.currentTimeMillis(),
        oscillationEscalations: Int = 0,
    ): SessionTrace? {
        val usage = run.filterIsInstance<ChatMessage.UsageRecord>()
        val tools = run.filterIsInstance<ChatMessage.ToolResult>()
        val workRoles = setOf("agent", "editor", "escalation", "architect", "planner", "reviewer")
        val hadWork = tools.isNotEmpty() || usage.any { it.role in workRoles }
        if (!EvolutionGuard.mayRecord(isEvolutionSession, hadWork)) return null

        val notices = run.filterIsInstance<ChatMessage.Notice>()
        val calls = usage.filter { it.role != "cache" && it.role != "decision" }
        val input = calls.sumOf { it.usage.input }
        val cache = if (input > 0) (calls.sumOf { it.usage.cached } * 100 / input).toInt() else null

        val editorProblems = notices.filter { it.isError && it.text.startsWith("✏️") }
        val patchErrors = tools.filter { it.isError && it.name in setOf("github_apply_diff", "github_write_files") }
        val ci = tools.filter { it.name == "ci_wait" }
        val ciFailed = ci.filter { it.content.contains("\nHatalar (") }
        fun marker(label: String) = notices.sumOf { n ->
            Regex("${Regex.escape(label)}:\\s*(\\d+)").find(n.text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        val routes = run.filterIsInstance<ChatMessage.RouteInfo>()
        val stopped = notices.any { n -> n.text.startsWith("Durduruldu") || n.text.startsWith("Bütçe eşiğinde") || n.text.startsWith("Hata:") } ||
            run.any { it is ChatMessage.Assistant && (it.text.startsWith("⚠️") || it.text.startsWith("⏸")) }

        return SessionTrace(
            id = UUID.randomUUID().toString().take(10),
            sessionId = sessionId,
            repo = repo,
            at = now,
            request = request.take(600),
            modelsUsed = calls.map { it.model }.distinct(),
            turnCount = run.count { it is ChatMessage.Assistant },
            cacheHitPercent = cache,
            totalCostUsd = usage.sumOf(costOf),
            diffErrorCount = editorProblems.size + patchErrors.size,
            diffErrorSamples = (editorProblems.map { it.text } + patchErrors.map { it.content.lines().first() }).map { it.take(200) }.take(5),
            ciFailureCount = ciFailed.size,
            ciSignatures = ciFailed.map { signature(it.content) }.take(3),
            escalations = notices.filter { it.text.startsWith("⤴️") }.map { it.text.take(160) },
            routing = routes.lastOrNull()?.let { "${it.route}: ${it.text.take(160)}" },
            upgradedToArchitect = routes.any { it.text.contains("yükseltildi") || it.text.contains("geçersiz kıldı") },
            stopped = stopped,
            ciRuns = ci.size,
            steps = ProcedureMiner.steps(run),
            preflightBlocksCount = marker("ön kontrol"),
            searchReplaceTier2Count = marker("SEARCH kademe 2"),
            searchReplaceTier3Count = marker("SEARCH kademe 3"),
            oscillationEscalationsCount = oscillationEscalations,
            prePushGateWarningsCount = marker("push öncesi"),
            failureWindows = FailureWindows.from(run, request, routes.any { it.text.contains("yükseltildi") || it.text.contains("geçersiz kıldı") }),
        )
    }

    /** First meaningful error lines of a CI report (the same short form the agent already sees). */
    fun signature(ciReport: String): String =
        ciReport.substringAfter("\nHatalar (").lines().drop(1)
            .filter { l -> l.isNotBlank() && !l.startsWith("##") && !l.startsWith("…") }
            .take(4).joinToString(" | ").take(300)
}
