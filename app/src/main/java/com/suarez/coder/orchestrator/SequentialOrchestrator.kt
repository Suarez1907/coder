package com.suarez.coder.orchestrator

import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.llm.ChatMessage

/**
 * Light mode: subtasks run one after another in dependency order. Each one is edited by the usual editor ladder on a
 * clean context (its description, its files and short hand-offs of the earlier subtasks) and committed on its own,
 * so a paused or killed run resumes at the next subtask.
 */
class SequentialOrchestrator(agent: Agent, hooks: OrchestratorHooks) : OrchestratorBase(agent, hooks) {

    suspend fun run(history: MutableList<ChatMessage>, ui: AgentUi, start: OrchestratorPlan): OrchestratorPlan {
        var plan = save(start.copy(state = PlanState.RUNNING, note = null), ui)
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
            val handoffs = plan.tasks.mapNotNull { it.handoff } + listOfNotNull(t.error?.let { "Önceki deneme başarısız oldu: ${it.take(400)}" })
            val originals = HashMap<String, String?>()
            val state = HashMap<String, String?>()
            var result = try {
                agent.budgetDeferred = true
                val out = StringBuilder()
                val o = work(history, ui, plan, t, handoffs, originals, state, out)
                if (!o.ok) t.copy(status = TaskStatus.FAILED, error = o.text.take(600))
                else commit(history, plan, t, originals, state)
            } finally {
                agent.budgetDeferred = false
            }
            if (result.status == TaskStatus.DONE && t.requiresCi) {
                val (failed, report) = waitCi(plan)
                history += ChatMessage.Notice(if (failed) "❌ ${t.id}: CI başarısız" else "🟢 ${t.id}: CI", report, isError = failed)
                if (failed) result = result.copy(status = TaskStatus.FAILED, error = "CI başarısız:\n" + report.takeLast(1_500))
            }
            val cost = costSince(history, from)
            plan = save(plan.withTask(result.copy(costUsd = t.costUsd + cost)).addCost("Görev ${t.id}", cost), ui)
            if (result.status == TaskStatus.FAILED) {
                return save(plan.copy(state = PlanState.PAUSED, note = "${t.id} başarısız oldu; Devam Et aynı alt görevi hatasıyla birlikte yeniden dener."), ui)
            }
        }
        history += ChatMessage.Assistant("✅ Sıralı plan tamamlandı: ${plan.tasks.size} alt görev.\n\n" +
            plan.tasks.joinToString("\n") { "${it.status.icon} ${it.id} ${it.title}" }, model = "coder")
        return save(plan.copy(state = PlanState.DONE, note = null), ui)
    }

    private suspend fun commit(history: MutableList<ChatMessage>, plan: OrchestratorPlan, t: SubTask,
                               originals: Map<String, String?>, state: Map<String, String?>): SubTask {
        val (changes, summary) = editor.changesFor(t.files.map { it.path to (it.action == "delete") }, originals, state)
        if (changes.isEmpty()) return t.copy(status = TaskStatus.DONE, handoff = OrchestratorTools.handoffFor(t, "değişiklik gerekmedi"))
        return try {
            val res = agent.tools.github.commitFiles(plan.repo, plan.branch, "${plan.commitMessage} — ${t.title}", changes, plan.base)
            agent.tools.onRepoChanged()
            history += ChatMessage.Notice("✅ ${t.id} commit edildi", "$res\n$summary")
            t.copy(status = TaskStatus.DONE, handoff = OrchestratorTools.handoffFor(t, summary))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            t.copy(status = TaskStatus.FAILED, error = "Commit başarısız: ${e.message}")
        }
    }
}
