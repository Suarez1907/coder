package com.suarez.coder.orchestrator

import android.content.Context
import com.suarez.coder.evolution.CostToGreenScorer
import com.suarez.coder.evolution.TaskDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speculative Swarm Tournament Engine (Phase 5B).
 *
 * Runs 2..3 Implementer candidates in parallel on the same Turn-0 context pack
 * with a strict budget ceiling and early-stop cancellation: as soon as the first
 * candidate passes all local gates with 0 warnings, all other active streams are
 * cancelled via Job.cancel().
 *
 * RED LINE 1 (Architect Lock): The locked Architect model is NEVER added to the
 * tournament pool and is NEVER modified.
 */
class SpeculativeSwarmEngine(
    private val statsStore: SwarmStatsStore? = null,
    private val costToGreenScorer: CostToGreenScorer? = null,
) {
    data class SwarmArm(
        val model: String,
        val hypothesisSeed: String = "",
        val estimatedCostUsd: Double = 0.005,
    )

    data class CandidateOutput(
        val arm: SwarmArm,
        val diffLinesCount: Int,
        val preFlightWarnings: Int,
        val watchdogWarnings: Int,
        val securityIssues: Int,
        val crossFileSignatureErrors: Int,
        val testsPassed: Boolean,
        val actualCostUsd: Double,
        val durationMs: Long,
        val payload: String = "",
    ) {
        val totalWarnings: Int
            get() = preFlightWarnings + watchdogWarnings + securityIssues + crossFileSignatureErrors

        val passedAllLocalGates: Boolean
            get() = totalWarnings == 0 && testsPassed
    }

    data class TournamentResult(
        val winner: CandidateOutput?,
        val launchedCandidates: Int,
        val cancelledCandidates: Int,
        val savedEstimatedUsd: Double,
        val totalCostUsd: Double,
        val durationMs: Long,
        val summaryLine: String,
        val allOutputs: List<CandidateOutput>,
    )

    fun shouldTrigger(mode: String, ciFailures: Int, complexity: Double?): Boolean = when (mode) {
        "off" -> false
        "after_ci_fail" -> ciFailures >= 1
        "hard_tasks" -> ciFailures >= 1 || (complexity ?: 0.0) >= 0.65
        else -> false
    }

    /**
     * Validates that the locked Architect model is never present in [candidates].
     * Throws [IllegalArgumentException] if the Architect model is detected.
     */
    fun validateCandidates(candidates: List<SwarmArm>, lockedArchitectModel: String) {
        val locked = lockedArchitectModel.trim()
        if (locked.isNotBlank() && candidates.any { it.model.trim().equals(locked, ignoreCase = true) }) {
            throw IllegalArgumentException(
                "Architect Lock violation: locked architect model '$locked' cannot participate in SpeculativeSwarm.",
            )
        }
    }

    /**
     * Selects 2..3 Implementer arms, strictly excluding [lockedArchitectModel].
     * If the pool has only 1 eligible model, generates distinct short hypothesis seeds.
     */
    fun selectArms(
        scorer: CostToGreenScorer?,
        domain: TaskDomain,
        pool: List<String>,
        lockedArchitectModel: String,
        maxCandidates: Int = 2,
    ): List<SwarmArm> {
        val limit = maxCandidates.coerceIn(2, 3)
        val locked = lockedArchitectModel.trim()
        val eligible = pool
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(locked, ignoreCase = true) }
            .distinct()

        if (eligible.isEmpty()) return emptyList()

        val ranked = if (scorer != null) {
            eligible.sortedByDescending { model ->
                scorer.score(model, domain).efficiencyScore
            }
        } else {
            eligible
        }

        val arms = if (ranked.size >= 2) {
            ranked.take(limit).mapIndexed { idx, model ->
                SwarmArm(model = model, hypothesisSeed = "arm_$idx")
            }
        } else {
            val single = ranked.first()
            val seeds = listOf(
                "minimal_diff",
                "defensive_guard",
                "refactor_local",
            )
            (0 until limit).map { idx ->
                SwarmArm(model = single, hypothesisSeed = seeds[idx % seeds.size])
            }
        }

        validateCandidates(arms, lockedArchitectModel)
        return arms
    }

    /**
     * Runs the tournament in parallel using a SupervisorJob.
     * Enforces [budgetCeilingUsd] before launching each arm, and cancels all remaining
     * active jobs immediately when the first zero-warning candidate passes all local gates.
     */
    suspend fun runTournament(
        arms: List<SwarmArm>,
        lockedArchitectModel: String,
        budgetCeilingUsd: Double = 0.02,
        domain: TaskDomain = TaskDomain.GENERAL,
        nowMs: () -> Long = { System.currentTimeMillis() },
        executeArm: suspend (SwarmArm) -> CandidateOutput,
    ): TournamentResult = supervisorScope {
        validateCandidates(arms, lockedArchitectModel)

        val startMs = nowMs()
        val budget = budgetCeilingUsd.coerceAtLeast(0.0)
        val launchedArms = mutableListOf<SwarmArm>()
        var committedEstimate = 0.0
        for (arm in arms) {
            if (committedEstimate + arm.estimatedCostUsd <= budget + 1e-9) {
                launchedArms.add(arm)
                committedEstimate += arm.estimatedCostUsd
            }
        }

        if (launchedArms.isEmpty()) {
            val summary = "Sürü Turnuvası: 0 aday -> bütçe tavanı aşıldı"
            return@supervisorScope TournamentResult(
                winner = null,
                launchedCandidates = 0,
                cancelledCandidates = 0,
                savedEstimatedUsd = 0.0,
                totalCostUsd = 0.0,
                durationMs = 0L,
                summaryLine = summary,
                allOutputs = emptyList(),
            )
        }

        val swarmJob = SupervisorJob(coroutineContext[Job])
        val swarmScope = CoroutineScope(coroutineContext + swarmJob)
        val resultChannel = Channel<CandidateOutput>(launchedArms.size)
        val earlyStopped = AtomicBoolean(false)
        val completedCount = AtomicInteger(0)
        val cancelledCount = AtomicInteger(0)
        val jobs = mutableListOf<Job>()

        for (arm in launchedArms) {
            val job = swarmScope.launch {
                try {
                    val out = executeArm(arm)
                    resultChannel.trySend(out)
                } catch (ce: CancellationException) {
                    cancelledCount.incrementAndGet()
                    throw ce
                } catch (_: Throwable) {
                    // Candidate failed; counted as non-winning.
                } finally {
                    if (completedCount.incrementAndGet() == launchedArms.size) {
                        resultChannel.close()
                    }
                }
            }
            jobs.add(job)
        }

        val collected = mutableListOf<CandidateOutput>()
        for (out in resultChannel) {
            collected.add(out)
            if (out.passedAllLocalGates && earlyStopped.compareAndSet(false, true)) {
                // Early-stop: cancel all other active streams immediately.
                jobs.forEach { j ->
                    if (j.isActive) j.cancel(CancellationException("Early stop: winner found"))
                }
                swarmJob.cancel()
                resultChannel.close()
                break
            }
        }

        // Ensure all child jobs finish cancelling.
        jobs.forEach { runCatching { it.join() } }

        val winner = pickWinner(collected)
        val totalDurationMs = (nowMs() - startMs).coerceAtLeast(0L)
        val totalCost = collected.sumOf { it.actualCostUsd }
        val actualCancelled = (launchedArms.size - collected.size).coerceAtLeast(cancelledCount.get())
        val savedUsd = (launchedArms.sumOf { it.estimatedCostUsd } - totalCost).coerceAtLeast(0.0)

        if (winner != null) {
            costToGreenScorer?.record(
                modelId = winner.arm.model,
                domain = domain,
                sample = CostToGreenScorer.Sample(
                    success = true,
                    firstPassCi = true,
                    firstPassCostUsd = winner.actualCostUsd,
                    healCostUsd = 0.0,
                    preflightBlocks = 0,
                    oscillated = false,
                ),
                baselineModelId = lockedArchitectModel,
            )
        }

        val durationSec = String.format("%.1f", totalDurationMs / 1000.0)
        val summary = if (winner != null) {
            val earlyTag = if (actualCancelled > 0) " erken durdurma" else ""
            "Sürü Turnuvası: ${launchedArms.size} aday -> ${winner.arm.model} kazandı (${durationSec}s$earlyTag)"
        } else {
            "Sürü Turnuvası: ${launchedArms.size} aday -> kazanan yok (${durationSec}s)"
        }

        statsStore?.recordTournament(
            launched = launchedArms.size,
            cancelled = actualCancelled,
            savedUsd = savedUsd,
            winnerModel = winner?.arm?.model,
        )

        TournamentResult(
            winner = winner,
            launchedCandidates = launchedArms.size,
            cancelledCandidates = actualCancelled,
            savedEstimatedUsd = savedUsd,
            totalCostUsd = totalCost,
            durationMs = totalDurationMs,
            summaryLine = summary,
            allOutputs = collected,
        )
    }

    /**
     * Tie-breaker when multiple candidates pass all local gates:
     * 1. Smallest clean diff line count
     * 2. Zero PreFlight/Watchdog warnings
     * 3. Lowest actual cost
     */
    fun pickWinner(outputs: List<CandidateOutput>): CandidateOutput? {
        val passing = outputs.filter { it.passedAllLocalGates }
        if (passing.isEmpty()) return null
        return passing.minWithOrNull(
            compareBy<CandidateOutput> { it.diffLinesCount }
                .thenBy { it.totalWarnings }
                .thenBy { it.actualCostUsd },
        )
    }
}

/** Persistent store for Speculative Swarm tournament metrics. */
class SwarmStatsStore(context: Context) {
    private val prefs = context.getSharedPreferences("swarm_stats", Context.MODE_PRIVATE)

    val totalTournaments: Int get() = prefs.getInt("total_tournaments", 0)
    val totalCancelled: Int get() = prefs.getInt("total_cancelled", 0)
    val totalSavedUsd: Double get() = prefs.getString("total_saved_usd", "0.0")?.toDoubleOrNull() ?: 0.0
    val lastWinnerModel: String get() = prefs.getString("last_winner", "") ?: ""

    fun recordTournament(launched: Int, cancelled: Int, savedUsd: Double, winnerModel: String?) {
        prefs.edit()
            .putInt("total_tournaments", totalTournaments + 1)
            .putInt("total_cancelled", totalCancelled + cancelled)
            .putString("total_saved_usd", (totalSavedUsd + savedUsd).toString())
            .putString("last_winner", winnerModel ?: "")
            .apply()
    }
}
