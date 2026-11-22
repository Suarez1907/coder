package com.suarez.coder.orchestrator

import com.suarez.coder.tools.Args
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TaskStatus(val icon: String, val label: String) {
    PENDING("○", "Bekliyor"), RUNNING("◐", "Çalışıyor"), DONE("✓", "Tamamlandı"), FAILED("✗", "Hata"), SKIPPED("–", "Atlandı");
}

enum class PlanState(val label: String) {
    AWAITING_APPROVAL("Onay bekliyor"), RUNNING("Çalışıyor"), PAUSED("Duraklatıldı"),
    DONE("Tamamlandı"), FAILED("Hata"), CANCELLED("İptal edildi");

    val finished get() = this == DONE || this == FAILED || this == CANCELLED
}

data class TaskFile(val path: String, val action: String, val instructions: String)

data class SubTask(
    val id: String,
    val title: String,
    val description: String,
    val files: List<TaskFile>,
    val acceptance: List<String> = emptyList(),
    val requiresCi: Boolean = false,
    val dependsOn: List<String> = emptyList(),
    val status: TaskStatus = TaskStatus.PENDING,
    /** Files changed and decisions, handed to the next subtask instead of the whole conversation. */
    val handoff: String? = null,
    val error: String? = null,
    val costUsd: Double = 0.0,
    /** Review-round fix tickets are marked so the card can tell them apart. */
    val fix: Boolean = false,
) {
    /** Files this subtask may change; overlapping owners never run in parallel. */
    val ownedFiles: List<String> get() = files.map { it.path }
}

data class OrchestratorPlan(
    val id: String = UUID.randomUUID().toString().take(8),
    val mode: OrchestratorMode,
    val request: String,
    val repo: String,
    val branch: String,
    val base: String?,
    val commitMessage: String,
    val tasks: List<SubTask>,
    val state: PlanState = PlanState.AWAITING_APPROVAL,
    val reviewRound: Int = 0,
    /** Per agent (Planner, Worker #1, Reviewer…) cost in USD. */
    val roleCosts: Map<String, Double> = emptyMap(),
    val note: String? = null,
    /** Full mode: edited file contents not committed yet (null = delete), so a restart does not lose work. */
    val staged: Map<String, String?> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** A normal (non-orchestrator) plan the executor could not apply, waiting for the user's decision. */
    val repair: Boolean = false,
) {
    fun task(id: String) = tasks.firstOrNull { it.id == id }
    fun withTask(t: SubTask) = copy(tasks = tasks.map { if (it.id == t.id) t else it })
    fun addCost(agent: String, usd: Double) =
        if (usd <= 0) this else copy(roleCosts = roleCosts + (agent to ((roleCosts[agent] ?: 0.0) + usd)))
    val totalCost get() = roleCosts.values.sum()
    val open get() = tasks.filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING || it.status == TaskStatus.FAILED }
}

object PlanJson {
    const val FORMAT = "submit_task_plan biçimi: {\"repo\":\"sahip/isim\",\"branch\":\"feat/x\",\"commit_message\":\"...\"," +
        "\"tasks\":[{\"id\":\"t1\",\"title\":\"...\",\"description\":\"...\",\"files\":[{\"path\":\"a/B.kt\",\"action\":\"modify\",\"instructions\":\"...\"}]," +
        "\"acceptance_criteria\":[\"...\"],\"depends_on\":[],\"requires_ci\":false}]}"

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it)?.toString()?.trim()?.ifBlank { null } }

    private fun stringsOf(o: JSONObject, vararg keys: String): List<String> {
        for (k in keys) {
            val v = o.opt(k) ?: continue
            return when (v) {
                is JSONArray -> strings(v)
                is String -> v.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotBlank() }
                else -> emptyList()
            }
        }
        return emptyList()
    }

    /** Reads the planner's submit_task_plan leniently; returns the plan or what to fix. */
    fun parse(a: JSONObject, defaultRepo: String, mode: OrchestratorMode, request: String): Pair<OrchestratorPlan?, String?> {
        val repo = a.optString("repo").ifBlank { defaultRepo }.trim().removePrefix("https://github.com/").trim('/').removeSuffix(".git")
        val branch = a.optString("branch").trim()
        if (repo.isBlank()) return null to "Planda 'repo' yok. $FORMAT"
        if (branch.isBlank()) return null to "Planda 'branch' yok. $FORMAT"
        val els = Args.elements(a, "tasks") ?: return null to "Planda 'tasks' yok. $FORMAT"
        val tasks = ArrayList<SubTask>()
        for ((i, e) in els.withIndex()) {
            val o = when (e) {
                is JSONObject -> e
                is String -> runCatching { JSONObject(e) }.getOrNull()
                else -> null
            } ?: return null to "${i + 1}. görev bir nesne değil. $FORMAT"
            val id = o.optString("id").trim().ifBlank { "t${i + 1}" }
            val files = (Args.elements(o, "files") ?: emptyList()).mapNotNull { f ->
                when (f) {
                    is JSONObject -> {
                        val path = f.optString("path").ifBlank { f.optString("file") }.trim().removePrefix("./").removePrefix("/")
                        val action = f.optString("action", "modify").lowercase().let { if (it in setOf("modify", "create", "delete")) it else "modify" }
                        TaskFile(path, action, f.optString("instructions").ifBlank { f.optString("changes") })
                    }
                    is String -> TaskFile(f.trim().removePrefix("./").removePrefix("/"), "modify", "")
                    else -> null
                }
            }.filter { Args.looksLikePath(it.path) }.distinctBy { it.path }
            if (files.isEmpty()) return null to "'$id' görevinde geçerli dosya yok. $FORMAT"
            tasks += SubTask(
                id = id,
                title = o.optString("title").trim().ifBlank { "Görev ${i + 1}" },
                description = o.optString("description").trim(),
                files = files,
                acceptance = stringsOf(o, "acceptance_criteria", "acceptanceCriteria", "acceptance"),
                requiresCi = o.optBoolean("requires_ci", o.optBoolean("requiresCi", false)),
                dependsOn = stringsOf(o, "depends_on", "dependsOn"),
            )
        }
        if (tasks.isEmpty()) return null to "Görev listesi boş. $FORMAT"
        if (tasks.map { it.id }.toSet().size != tasks.size) return null to "Görev id'leri benzersiz olmalı. $FORMAT"
        val ids = tasks.map { it.id }.toSet()
        // Unknown dependencies are dropped rather than blocking the queue forever.
        val clean = tasks.map { t -> t.copy(dependsOn = t.dependsOn.filter { it in ids && it != t.id }) }
        Scheduler.cycle(clean)?.let { return null to "Bağımlılıklarda döngü var: $it. $FORMAT" }
        return OrchestratorPlan(mode = mode, request = request, repo = repo, branch = branch,
            base = a.optString("base_branch").trim().takeIf { it.isNotBlank() && it != "null" },
            commitMessage = a.optString("commit_message").ifBlank { "Coder: değişiklikler" }, tasks = clean) to null
    }

    fun toJson(p: OrchestratorPlan): String = JSONObject()
        .put("id", p.id).put("mode", p.mode.name).put("request", p.request).put("repo", p.repo).put("branch", p.branch)
        .put("base", p.base ?: JSONObject.NULL).put("msg", p.commitMessage).put("state", p.state.name).put("round", p.reviewRound)
        .put("note", p.note ?: JSONObject.NULL).put("updated", p.updatedAt).put("repair", p.repair)
        .put("costs", JSONObject().also { j -> p.roleCosts.forEach { (k, v) -> j.put(k, v) } })
        .put("staged", JSONObject().also { j -> p.staged.forEach { (k, v) -> j.put(k, v ?: JSONObject.NULL) } })
        .put("tasks", JSONArray().also { arr ->
            p.tasks.forEach { t ->
                arr.put(JSONObject().put("id", t.id).put("title", t.title).put("desc", t.description)
                    .put("files", JSONArray().also { fa -> t.files.forEach { f -> fa.put(JSONObject().put("path", f.path).put("action", f.action).put("instr", f.instructions)) } })
                    .put("acc", JSONArray(t.acceptance)).put("ci", t.requiresCi).put("deps", JSONArray(t.dependsOn))
                    .put("status", t.status.name).put("handoff", t.handoff ?: JSONObject.NULL).put("error", t.error ?: JSONObject.NULL)
                    .put("cost", t.costUsd).put("fix", t.fix))
            }
        }).toString()

    private fun JSONObject.optNullable(k: String) = if (has(k) && !isNull(k)) optString(k) else null

    fun fromJson(s: String): OrchestratorPlan? = runCatching {
        val o = JSONObject(s)
        val tasks = o.getJSONArray("tasks").let { arr ->
            (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                val fa = t.optJSONArray("files") ?: JSONArray()
                SubTask(
                    id = t.getString("id"), title = t.optString("title"), description = t.optString("desc"),
                    files = (0 until fa.length()).map { fa.getJSONObject(it) }.map { TaskFile(it.optString("path"), it.optString("action", "modify"), it.optString("instr")) },
                    acceptance = strings(t.optJSONArray("acc")), requiresCi = t.optBoolean("ci"), dependsOn = strings(t.optJSONArray("deps")),
                    status = runCatching { TaskStatus.valueOf(t.optString("status")) }.getOrDefault(TaskStatus.PENDING),
                    handoff = t.optNullable("handoff"), error = t.optNullable("error"), costUsd = t.optDouble("cost", 0.0), fix = t.optBoolean("fix"),
                )
            }
        }
        val costs = o.optJSONObject("costs")?.let { c -> c.keys().asSequence().associateWith { c.optDouble(it, 0.0) } } ?: emptyMap()
        val staged = o.optJSONObject("staged")?.let { c -> c.keys().asSequence().associateWith { k -> c.optNullable(k) } } ?: emptyMap()
        OrchestratorPlan(
            id = o.getString("id"), mode = OrchestratorMode.of(o.optString("mode")), request = o.optString("request"),
            repo = o.getString("repo"), branch = o.getString("branch"), base = o.optNullable("base"), commitMessage = o.optString("msg"),
            tasks = tasks, state = runCatching { PlanState.valueOf(o.optString("state")) }.getOrDefault(PlanState.PAUSED),
            reviewRound = o.optInt("round"), roleCosts = costs, note = o.optNullable("note"), staged = staged, updatedAt = o.optLong("updated"), repair = o.optBoolean("repair"),
        )
    }.getOrNull()
}

/** Pure ordering rules (unit-tested). */
object Scheduler {
    /** Subtasks whose dependencies are all done (or skipped). */
    fun ready(p: OrchestratorPlan): List<SubTask> {
        val finished = p.tasks.filter { it.status == TaskStatus.DONE || it.status == TaskStatus.SKIPPED }.map { it.id }.toSet()
        return p.tasks.filter { it.status == TaskStatus.PENDING && it.dependsOn.all { d -> d in finished } }
    }

    /**
     * Next wave: ready subtasks taken in plan order, at most [maxParallel], skipping any whose owned files overlap a
     * file already claimed in this wave. Overlapping subtasks therefore always run one after another.
     */
    fun nextWave(p: OrchestratorPlan, maxParallel: Int): List<SubTask> {
        val claimed = HashSet<String>()
        val wave = ArrayList<SubTask>()
        for (t in ready(p)) {
            if (wave.size >= maxParallel.coerceAtLeast(1)) break
            if (t.ownedFiles.any { it in claimed }) continue
            wave += t
            claimed += t.ownedFiles
        }
        return wave
    }

    fun overlaps(a: SubTask, b: SubTask) = a.ownedFiles.any { it in b.ownedFiles }

    /** Sequential order for the light mode: dependencies first, otherwise plan order. */
    fun sequentialOrder(tasks: List<SubTask>): List<SubTask> {
        val done = HashSet<String>()
        val out = ArrayList<SubTask>()
        val left = tasks.toMutableList()
        while (left.isNotEmpty()) {
            val next = left.firstOrNull { t -> t.dependsOn.all { it in done } } ?: left.first()
            out += next; done += next.id; left.remove(next)
        }
        return out
    }

    /** A dependency cycle as "a → b → a", or null. */
    fun cycle(tasks: List<SubTask>): String? {
        val byId = tasks.associateBy { it.id }
        val state = HashMap<String, Int>() // 1 = visiting, 2 = done
        fun visit(id: String, path: List<String>): String? {
            when (state[id]) { 1 -> return (path + id).joinToString(" → "); 2 -> return null }
            state[id] = 1
            for (d in byId[id]?.dependsOn.orEmpty()) visit(d, path + id)?.let { return it }
            state[id] = 2
            return null
        }
        for (t in tasks) visit(t.id, emptyList())?.let { return it }
        return null
    }
}
