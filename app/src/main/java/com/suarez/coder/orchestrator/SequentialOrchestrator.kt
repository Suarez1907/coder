package com.suarez.coder.orchestrator

import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.llm.ChatMessage
import kotlinx.coroutines.async

/**
 * Light mode: subtasks run one after another in dependency order. Each one is edited by the usual editor ladder on a
 * clean context (its description, its files and short hand-offs of the earlier subtasks) and committed on its own,
 * so a paused or killed run resumes at the next subtask.
 */
class SequentialOrchestrator(agent: Agent, hooks: OrchestratorHooks) : OrchestratorBase(agent, hooks) {

    suspend fun run(history: MutableList<ChatMessage>, ui: AgentUi, start: OrchestratorPlan): OrchestratorPlan {
        var plan = save(start.copy(state = PlanState.RUNNING, note = null), ui)
        // Pipelining: the next subtask is edited while the previous one's CI runs; its result is awaited before the
        // next commit (a newer commit could cancel that CI run).
        val scope = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext)
        var pending: Pair<String, kotlinx.coroutines.Deferred<Pair<Boolean, String>>>? = null
        suspend fun settle(): Boolean {
            val (id, d) = pending ?: return true
            pending = null
            val (failed, report) = d.await()
            history += ChatMessage.Notice(if (failed) "❌ $id: CI başarısız" else "🟢 $id: CI", report, isError = failed)
            ui.update()
            if (!failed) return true
            plan.task(id)?.let { pt -> plan = save(plan.withTask(pt.copy(status = TaskStatus.FAILED, error = formatCiError(report))), ui) }
            plan = save(plan.copy(state = PlanState.PAUSED, note = "$id başarısız oldu (CI); Devam Et aynı alt görevi hatasıyla birlikte yeniden dener."), ui)
            return false
        }
        for (id in Scheduler.sequentialOrder(plan.tasks).map { it.id }) {
            val t = plan.task(id) ?: continue
            if (t.status == TaskStatus.DONE || t.status == TaskStatus.SKIPPED) continue
            stopReason(history)?.let { return save(plan.copy(state = PlanState.PAUSED, note = it), ui) }
            val blocked = t.dependsOn.filter { d -> plan.task(d)?.status !in setOf(TaskStatus.DONE, TaskStatus.SKIPPED) }
            if (blocked.isNotEmpty()) {
                return save(plan.copy(state = PlanState.PAUSED, note = "${t.id} için önce şunlar bitmeli: ${blocked.joinToString()}"), ui)
            }
            plan = save(plan.withTask(t.copy(status = TaskStatus.RUNNING, error = null)), ui)
            history += ChatMessage.Notice("▶️ Alt görev ${t.id}: ${t.title}")
            ui.update()
            val from = history.size
            val handoffs = plan.tasks.mapNotNull { it.handoff } + listOfNotNull(t.error?.let { "Önceki deneme başarısız oldu: ${it.take(2_500)}" })
            val originals = HashMap<String, String?>()
            val state = HashMap<String, String?>()
            var result = try {
                agent.budgetDeferred = true
                val out = StringBuilder()
                val o = work(history, ui, plan, t, handoffs, originals, state, out)
                if (!o.ok) t.copy(status = TaskStatus.FAILED, error = o.text.take(600))
                else {
                    // The previous subtask's CI must be green before this commit (and before it could be cancelled).
                    if (!settle()) {
                        plan = save(plan.withTask(t.copy(status = TaskStatus.PENDING)), ui)
                        return plan
                    }
                    commit(history, plan, t, originals, state)
                }
            } finally {
                agent.budgetDeferred = false
            }
            if (result.status == TaskStatus.DONE && t.requiresCi) {
                if (settings.pipelining) pending = t.id to scope.async { waitCi(plan) }
                else {
                    val (failed, report) = waitCi(plan)
                    history += ChatMessage.Notice(if (failed) "❌ ${t.id}: CI başarısız" else "🟢 ${t.id}: CI", report, isError = failed)
                    if (failed) {
                        val ciErr = formatCiError(report)
                        history += ChatMessage.Notice("🔁 ${t.id}: CI hatasıyla yeniden deneniyor", ciErr)
                        ui.update()
                        val retryHandoffs = plan.tasks.mapNotNull { it.handoff } + listOf("Önceki deneme başarısız oldu: ${ciErr.take(2_500)}")
                        val rOrig = HashMap<String, String?>()
                        val rState = HashMap<String, String?>()
                        result = try {
                            agent.budgetDeferred = true
                            val rOut = StringBuilder()
                            val ro = work(history, ui, plan, t, retryHandoffs, rOrig, rState, rOut)
                            if (!ro.ok) t.copy(status = TaskStatus.FAILED, error = ciErr)
                            else {
                                val committed = commit(history, plan, t, rOrig, rState)
                                if (committed.status == TaskStatus.DONE) {
                                    val (rFailed, rReport) = waitCi(plan)
                                    history += ChatMessage.Notice(if (rFailed) "❌ ${t.id}: CI başarısız" else "🟢 ${t.id}: CI", rReport, isError = rFailed)
                                    if (rFailed) committed.copy(status = TaskStatus.FAILED, error = formatCiError(rReport)) else committed
                                } else committed
                            }
                        } finally {
                            agent.budgetDeferred = false
                        }
                    }
                }
            }
            val cost = costSince(history, from)
            plan = save(plan.withTask(result.copy(costUsd = t.costUsd + cost)).addCost("Görev ${t.id}", cost), ui)
            if (result.status == TaskStatus.FAILED) {
                return save(plan.copy(state = PlanState.PAUSED, note = "${t.id} başarısız oldu; Devam Et aynı alt görevi hatasıyla birlikte yeniden dener."), ui)
            }
        }
        if (!settle()) return plan
        history += ChatMessage.Assistant("✅ Sıralı plan tamamlandı: ${plan.tasks.size} alt görev.\n\n" +
            plan.tasks.joinToString("\n") { "${it.status.icon} ${it.id} ${it.title}" }, model = "coder")
        return save(plan.copy(state = PlanState.DONE, note = null), ui)
    }

    private fun formatCiError(report: String): String {
        val filtered = com.suarez.coder.ci.LogFilter.extract(report).ifBlank { report.takeLast(1_500) }
        val firstLine = com.suarez.coder.ci.AutoHealer.signature(filtered)
            .ifBlank { com.suarez.coder.ci.AutoHealer.signature(report) }
            .ifBlank { "bilinmeyen hata" }
        return "CI başarısız: $firstLine\n$filtered".take(2_500)
    }

    private suspend fun commit(history: MutableList<ChatMessage>, plan: OrchestratorPlan, t: SubTask,
                               originals: Map<String, String?>, state: Map<String, String?>): SubTask {
        val (changes, summary) = editor.changesFor(t.files.map { it.path to (it.action == "delete") }, originals, state)
        if (changes.isEmpty()) return t.copy(status = TaskStatus.DONE, handoff = OrchestratorTools.handoffFor(t, "değişiklik gerekmedi"))
        val deletedPaths = t.files.filter { it.action == "delete" }.map { it.path }.toSet()
        val pf = com.suarez.coder.harness.PreFlightValidator.validate(
            changes.map { c ->
                com.suarez.coder.harness.PreFlightValidator.StagedFile(
                    c.path, originals[c.path], c.content, explicitDelete = c.path in deletedPaths
                )
            }
        )
        if (!pf.ok) return t.copy(status = TaskStatus.FAILED, error = "Pre-flight engeli:\n" + pf.report().take(1_500))
        agent.tools.preCommit?.invoke(plan.repo, plan.branch, changes.map { Triple(it.path, originals[it.path], it.content) })
        agent.tools.beforeWrite?.invoke(plan.repo, plan.branch)
        val res = agent.tools.github.commitFiles(plan.repo, plan.branch, "${plan.commitMessage} — ${t.title}", changes, plan.base)
        agent.tools.afterCommit?.invoke(plan.repo, res, changes.map { it.path }, changes.count { it.content != null && originals[it.path] == null })
        agent.tools.onRepoChanged()
        history += ChatMessage.Notice("✅ ${t.id} commit edildi", "$res\n$summary")
        return t.copy(status = TaskStatus.DONE, handoff = OrchestratorTools.handoffFor(t, summary))
    }
}
