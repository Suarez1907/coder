package com.suarez.coder.orchestrator

import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.agent.ArchitectEditor
import com.suarez.coder.llm.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Collections

/**
 * Full mode, a deterministic state machine:
 * waves of isolated workers (ready subtasks with disjoint files, at most maxParallelWorkers at once) edit a shared
 * staging area → a reviewer checks the combined diff against the acceptance criteria and opens fix tickets (at most
 * maxReviewRounds rounds) → one atomic commit → CI if a subtask asked for it (failures go back to the reviewer while
 * rounds remain).
 */
class MultiAgentOrchestrator(agent: Agent, hooks: OrchestratorHooks) : OrchestratorBase(agent, hooks) {

    private class WorkerResult(val slot: Int, val task: SubTask, val outcome: ArchitectEditor.Outcome,
                               val local: List<ChatMessage>, val output: String)

    suspend fun run(history: MutableList<ChatMessage>, ui: AgentUi, start: OrchestratorPlan): OrchestratorPlan {
        val maxParallel = settings.orchMaxParallel
        val maxRounds = settings.orchMaxReviewRounds
        var plan = start.copy(state = PlanState.RUNNING, note = null)
        // Staged edits survive restarts; their baseline is re-read from the branch (nothing was committed since).
        val originals: MutableMap<String, String?> = Collections.synchronizedMap(HashMap())
        val state: MutableMap<String, String?> = Collections.synchronizedMap(HashMap(plan.staged))
        for (p in plan.staged.keys) originals[p] = agent.tools.readForEdit(plan.repo, plan.branch, plan.base, p)
        plan = save(plan, ui)
        var ciReport: String? = null

        while (true) {
            plan = runWaves(history, ui, plan, maxParallel, originals, state)
            if (plan.state != PlanState.RUNNING) return plan

            val deleted = plan.tasks.flatMap { t -> t.files.filter { it.action == "delete" }.map { it.path } }.toSet()
            val diff = OrchestratorTools.renderDiff(originals, state, deleted)
            if (diff.isBlank() && ciReport == null) {
                history += ChatMessage.Assistant("ℹ️ Alt görevler değişiklik üretmedi; commit yapılmadı.", model = "coder")
                return save(plan.copy(state = PlanState.DONE, staged = emptyMap()), ui)
            }
            stopReason(history)?.let { return save(plan.copy(state = PlanState.PAUSED, note = it, staged = HashMap(state)), ui) }

            val from = history.size
            val r = try {
                agent.budgetDeferred = true
                review(history, ui, plan, diff.ifBlank { "(son commit'ten sonra değişiklik yok)" }, ciReport)
            } finally {
                agent.budgetDeferred = false
            }
            plan = save(plan.addCost("İnceleyici", costSince(history, from)), ui)
            history += ChatMessage.Notice(
                "🔍 İnceleme (tur ${plan.reviewRound + 1}): " + when {
                    r.approved -> "onaylandı"
                    !r.readable -> "sonuç okunamadı"
                    else -> "${r.issues.size} sorun"
                },
                (r.summary + "\n" + r.issues.joinToString("\n") { "- ${it.path}: ${it.problem}" }).trim().ifBlank { null },
                isError = !r.approved,
            )
            ui.update()
            ciReport = null

            val action = OrchestratorTools.afterReview(r, plan.reviewRound, maxRounds)
            if (action != ReviewAction.COMMIT) {
                if (action == ReviewAction.FIX) {
                    val tickets = OrchestratorTools.ticketsFor(r, plan.reviewRound + 1, plan)
                    plan = save(plan.copy(tasks = plan.tasks + tickets, reviewRound = plan.reviewRound + 1, staged = HashMap(state)), ui)
                    continue
                }
                val why = if (plan.reviewRound >= maxRounds) "İnceleme turu sınırına ($maxRounds) ulaşıldı." else "İnceleyici net bir sonuç vermedi."
                val ok = ui.confirm("İnceleme bitmedi", "$why\n\n${r.summary}\n" + r.issues.joinToString("\n") { "- ${it.path}: ${it.problem}" }.take(3_000) +
                    "\n\nDeğişiklikler yine de commit edilsin mi?", "Commit et", "Durdur")
                if (!ok) {
                    history += ChatMessage.Assistant("⏸ $why Değişiklikler hazırlık alanında bekliyor; özet yukarıda.", model = "coder")
                    return save(plan.copy(state = PlanState.PAUSED, note = "$why Devam Et yeniden inceler.", staged = HashMap(state)), ui)
                }
            }

            // One atomic commit for every subtask of the run.
            val paths = plan.tasks.flatMap { t -> t.files.map { it.path to (it.action == "delete") } }
            val (changes, summary) = editor.changesFor(paths, originals, state)
            if (changes.isNotEmpty()) {
                try {
                    val res = agent.tools.github.commitFiles(plan.repo, plan.branch, plan.commitMessage, changes, plan.base)
                    agent.tools.onRepoChanged()
                    history += ChatMessage.Notice("✅ Tek commit: ${changes.size} dosya", "$res\n$summary")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return save(plan.copy(state = PlanState.PAUSED, note = "Commit başarısız: ${e.message?.take(200)}", staged = HashMap(state)), ui)
                }
                for (c in changes) originals[c.path] = if (c.delete) null else c.content
                plan = save(plan.copy(staged = emptyMap()), ui)
            }

            if (plan.tasks.any { it.requiresCi } && changes.isNotEmpty()) {
                val (failed, report) = waitCi(plan)
                history += ChatMessage.Notice(if (failed) "❌ CI başarısız" else "🟢 CI geçti", report, isError = failed)
                ui.update()
                if (failed) {
                    if (plan.reviewRound >= maxRounds) {
                        return save(plan.copy(state = PlanState.PAUSED, note = "CI başarısız ve inceleme turu sınırı doldu."), ui)
                    }
                    ciReport = report
                    continue
                }
            }
            // Evaluator: the running app against the contract (web: Playwright in GitHub Actions). Failures go back to
            // the reviewer as evidence and become fix tickets, within the same round limit.
            if (settings.evaluatorEnabled && changes.isNotEmpty()) {
                val from = history.size
                val ev = try {
                    agent.budgetDeferred = true
                    runCatching { Evaluator(agent).evaluate(history, ui, plan) }.getOrElse { e ->
                        if (e is CancellationException) throw e
                        Evaluator.Result(emptyList(), emptyList(), "Değerlendirme hatası: ${e.message?.take(200)}")
                    }
                } finally {
                    agent.budgetDeferred = false
                }
                plan = save(plan.addCost("Değerlendirici", costSince(history, from)), ui)
                if (ev != null) {
                    history += ChatMessage.Notice(
                        "🧪 Değerlendirici: " + (if (ev.criteria.isEmpty()) "ölçülemedi" else ev.summary),
                        (ev.report() + "\n" + (ev.outputMarker ?: "")).trim(),
                        isError = ev.criteria.isNotEmpty() && !ev.passed,
                    )
                    ui.update()
                    if (ev.criteria.isNotEmpty() && !ev.passed) {
                        if (plan.reviewRound >= maxRounds) {
                            return save(plan.copy(state = PlanState.PAUSED, note = "Değerlendirici: ${ev.summary}; inceleme turu sınırı doldu."), ui)
                        }
                        ciReport = ev.report()
                        continue
                    }
                }
            }
            history += ChatMessage.Assistant("✅ Çoklu ajan planı tamamlandı: ${plan.tasks.size} alt görev, ${plan.reviewRound} düzeltme turu.\n\n" +
                plan.roleCosts.entries.joinToString("\n") { "${it.key}: ${agent.money(it.value)}" }, model = "coder")
            return save(plan.copy(state = PlanState.DONE, note = null), ui)
        }
    }

    /** Runs every ready subtask in waves; returns the plan RUNNING when the queue is empty, otherwise PAUSED. */
    private suspend fun runWaves(
        history: MutableList<ChatMessage>, ui: AgentUi, start: OrchestratorPlan, maxParallel: Int,
        originals: MutableMap<String, String?>, state: MutableMap<String, String?>,
    ): OrchestratorPlan {
        var plan = start
        val serial = SerialUi(ui)
        while (true) {
            val wave = Scheduler.nextWave(plan, maxParallel)
            if (wave.isEmpty()) {
                val stuck = plan.tasks.filter { it.status == TaskStatus.PENDING }
                if (stuck.isEmpty()) return plan
                return save(plan.copy(state = PlanState.PAUSED, staged = HashMap(state),
                    note = "Bağımlılığı bitmeyen görevler: ${stuck.joinToString { it.id }}"), ui)
            }
            stopReason(history)?.let { return save(plan.copy(state = PlanState.PAUSED, note = it, staged = HashMap(state)), ui) }
            for (t in wave) plan = plan.withTask(t.copy(status = TaskStatus.RUNNING, error = null))
            plan = save(plan, ui)
            history += ChatMessage.Notice("👷 " + (if (wave.size > 1) "${wave.size} işçi paralel: " else "İşçi: ") +
                wave.mapIndexed { i, t -> "#${i + 1} ${t.id} ${t.title}" }.joinToString(" · "))
            ui.update()
            val handoffs = plan.tasks.filter { it.status == TaskStatus.DONE }.mapNotNull { it.handoff }
            val snapshot = plan
            val results = try {
                agent.budgetDeferred = true
                coroutineScope {
                    wave.mapIndexed { slot, t ->
                        async {
                            // Isolated: a fresh history per worker, so nothing of the main chat is sent.
                            val local = ArrayList<ChatMessage>()
                            val out = StringBuilder()
                            val extra = handoffs + listOfNotNull(t.error?.let { "Önceki deneme: ${it.take(400)}" })
                            val o = try {
                                work(local, serial, snapshot, t, extra, originals, state, out)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                ArchitectEditor.Outcome(false, e.message ?: e.javaClass.simpleName)
                            }
                            WorkerResult(slot, t, o, local, out.toString())
                        }
                    }.awaitAll()
                }
            } finally {
                agent.budgetDeferred = false
            }
            for (r in results) {
                history.addAll(r.local)
                val cost = r.local.filterIsInstance<ChatMessage.UsageRecord>().sumOf { agent.costOf(it) }
                val (_, summary) = editor.changesFor(r.task.files.map { it.path to (it.action == "delete") }, originals, state)
                val done = if (r.outcome.ok) r.task.copy(status = TaskStatus.DONE, handoff = OrchestratorTools.handoffFor(r.task, summary))
                    else r.task.copy(status = TaskStatus.FAILED, error = r.outcome.text.take(600))
                plan = plan.withTask(done.copy(costUsd = r.task.costUsd + cost)).addCost("İşçi #${r.slot + 1}", cost)
            }
            plan = save(plan.copy(staged = HashMap(state)), ui)
            val failed = plan.tasks.filter { it.status == TaskStatus.FAILED }
            if (failed.isNotEmpty()) {
                return save(plan.copy(state = PlanState.PAUSED,
                    note = "Başarısız: ${failed.joinToString { it.id }}. Devam Et bunları hatalarıyla yeniden dener; diğer değişiklikler bekliyor."), ui)
            }
        }
    }
}
