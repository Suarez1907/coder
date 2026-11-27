package com.suarez.coder.orchestrator

import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.harness.PreFlightValidator
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.tools.GitHub
import kotlinx.coroutines.CancellationException

/**
 * Runs a contract DAG on the real editor ladder. The architect id captured at the start is never replaced.
 * Nodes write into one staged tree. A single commit is pushed only after preflight and the signature gate pass.
 * A conflict or a repeated failure drops the staged tree and finishes the plan in sequential mode.
 */
class ContractDagRunner(agent: Agent, hooks: OrchestratorHooks) : OrchestratorBase(agent, hooks) {
    suspend fun run(history: MutableList<ChatMessage>, ui: AgentUi, start: OrchestratorPlan): OrchestratorPlan {
        val architectId = agent.architectRef().key
        val orch = ContractDagOrchestrator(architectId, settings.orchMaxParallel.coerceIn(1, 3))
        val nodes = start.tasks.map { t -> DagNode(t.id, t.dependsOn, t.ownedFiles, t.acceptance, t.title, t.description) }
        val contract = orch.lock(nodes)
        if (contract.conflict || contract.architectModelId != architectId) {
            history += ChatMessage.Notice("Kontratlı DAG sıralı moda düştü (dosya çakışması veya döngü). Mimar değişmedi: $architectId")
            ui.update()
            return SequentialOrchestrator(agent, hooks).run(history, ui, start)
        }
        var plan = save(start.copy(state = PlanState.RUNNING, note = "Kontrat kilitli. Mimar: $architectId"), ui)
        val run = orch.execute(nodes, worker = { node, locked -> editNode(ui, plan, node, locked, architectId) }, validate = { tx ->
            val local = tx.edits().flatMap { e -> PreFlightValidator.check(e.path, e.before, e.after).map { "${e.path}: ${it.detail}" } }
            val gate = PreFlightValidator.signatureGate(agent.tools.dependencyGraph, tx)
            local + if (gate.blocked) listOf(gate.repairPrompt()) else emptyList()
        })
        if (!run.committed || run.fellBack) {
            history += ChatMessage.Notice(
                "Kontratlı DAG atomik commit atmadı. Sıralı moda geçildi. Mimar: $architectId" +
                    run.errors.take(3).joinToString("; ").let { if (it.isBlank()) "" else "\n$it" },
            )
            ui.update()
            val reset = plan.copy(
                tasks = plan.tasks.map { if (it.status == TaskStatus.SKIPPED) it else it.copy(status = TaskStatus.PENDING, error = run.errors.firstOrNull()) },
                note = "Sıralı tamamlanma. Mimar kilitli: $architectId",
            )
            return SequentialOrchestrator(agent, hooks).run(history, ui, reset)
        }
        val edits = run.staged.edits()
        if (edits.isNotEmpty()) {
            val changes = edits.map { GitHub.FileChange(it.path, it.after, it.after == null) }
            try {
                agent.tools.beforeWrite?.invoke(plan.repo, plan.branch)
                val res = agent.tools.github.commitFiles(plan.repo, plan.branch, plan.commitMessage, changes, plan.base)
                agent.tools.onRepoChanged()
                history += ChatMessage.Notice("Kontratlı DAG tek commit", res)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                history += ChatMessage.Notice("Atomik commit başarısız, sıralı moda geçildi: ${e.message?.take(200)}")
                ui.update()
                return SequentialOrchestrator(agent, hooks).run(history, ui, plan.copy(tasks = plan.tasks.map {
                    if (it.status == TaskStatus.SKIPPED) it else it.copy(status = TaskStatus.PENDING)
                }))
            }
        }
        if (plan.tasks.any { it.requiresCi }) {
            val (failed, report) = waitCi(plan)
            history += ChatMessage.Notice(if (failed) "CI başarısız" else "CI yeşil", report, isError = failed)
        }
        plan = save(plan.copy(
            tasks = plan.tasks.map { if (it.status == TaskStatus.SKIPPED) it else it.copy(status = TaskStatus.DONE) },
            state = PlanState.DONE,
            note = null,
        ), ui)
        history += ChatMessage.Assistant("Kontratlı DAG tamamlandı: ${plan.tasks.size} alt görev, tek commit.", model = "coder")
        ui.update()
        return plan
    }

    private suspend fun editNode(ui: AgentUi, plan: OrchestratorPlan, node: DagNode, locked: TaskContract, architectId: String): NodeResult {
        if (locked.architectModelId != architectId) return NodeResult.Fail("mimar değişti", conflict = true)
        val task = plan.task(node.id) ?: return NodeResult.Fail("görev yok")
        val local = ArrayList<ChatMessage>()
        val originals = HashMap<String, String?>()
        val state = HashMap<String, String?>()
        val edited = try {
            work(local, ui, plan, task, emptyList(), originals, state, StringBuilder())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return NodeResult.Fail(e.message ?: "alt görev hatası", conflict = e.message?.contains("çakış", true) == true)
        }
        if (!edited.ok) return NodeResult.Fail(edited.text.take(300))
        val (changes, _) = editor.changesFor(task.files.map { it.path to (it.action == "delete") }, originals, state)
        return NodeResult.Ok(changes.map { FileEdit(it.path, originals[it.path], it.content) })
    }
}
