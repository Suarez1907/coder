package com.suarez.coder.orchestrator

import java.io.File

/** One plan per chat session, saved after every step so a killed app resumes at the same subtask. */
class OrchestratorStore(private val dir: File) {
    init { dir.mkdirs() }

    private fun file(session: String) = File(dir, "${session.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    @Synchronized
    fun save(session: String, plan: OrchestratorPlan) {
        val f = file(session)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(PlanJson.toJson(plan.copy(updatedAt = System.currentTimeMillis())))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    @Synchronized
    fun load(session: String): OrchestratorPlan? = file(session).takeIf { it.isFile }?.let { PlanJson.fromJson(it.readText()) }

    @Synchronized
    fun delete(session: String) { file(session).delete() }

    /**
     * After a restart nothing is running any more: a plan saved as RUNNING becomes PAUSED and its half-done subtask
     * goes back to the queue (light mode commits only finished subtasks; full mode keeps staged edits).
     */
    fun recover(session: String): OrchestratorPlan? {
        val p = load(session) ?: return null
        if (p.state != PlanState.RUNNING) return p
        val fixed = p.copy(
            state = PlanState.PAUSED,
            note = "Uygulama kapandı; kaldığı alt görevden devam edebilirsiniz.",
            tasks = p.tasks.map { if (it.status == TaskStatus.RUNNING) it.copy(status = TaskStatus.PENDING) else it },
        )
        save(session, fixed)
        return fixed
    }
}
