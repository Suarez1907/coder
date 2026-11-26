package com.suarez.coder.orchestrator

import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.agent.ArchitectEditor
import com.suarez.coder.agent.PlanFile
import com.suarez.coder.diff.DiffLines
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.llm.ToolSpec
import com.suarez.coder.tools.Args
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** What the orchestrators need from the app: saving the plan card and the user's pause button. */
interface OrchestratorHooks {
    fun save(plan: OrchestratorPlan)
    fun pauseRequested(): Boolean
    fun clearPause()
}

/** Parallel workers must not show two confirm dialogs at once: they queue here. */
class SerialUi(private val ui: AgentUi) : AgentUi {
    private val lock = Mutex()
    override fun update() = ui.update()
    override fun status(text: String?) = ui.status(text)
    override suspend fun confirm(title: String, body: String, okText: String, cancelText: String): Boolean =
        lock.withLock { ui.confirm(title, body, okText, cancelText) }
}

data class ReviewIssue(val taskId: String?, val path: String, val problem: String)
data class Review(val approved: Boolean, val issues: List<ReviewIssue>, val summary: String, val readable: Boolean = true)

enum class ReviewAction { COMMIT, FIX, ASK_USER }

object OrchestratorTools {
    private fun s(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun arr(items: JSONObject, d: String) = JSONObject().put("type", "array").put("items", items).put("description", d)

    val submitTaskPlan = ToolSpec(
        "submit_task_plan",
        "Görevi alt görevlere bölünmüş planı gönderir. Her alt görev kendi dosyalarına sahiptir (files); aynı dosyayı " +
            "iki görev paylaşmamalı, paylaşırsa bunlar sırayla çalışır. Bağımlılıkları depends_on ile belirt.",
        JSONObject().put("type", "object").put("properties", JSONObject()
            .put("repo", s("sahip/isim"))
            .put("branch", s("Değişikliklerin gideceği dal, ör. feat/skor-tablosu"))
            .put("base_branch", s("Yeni dal için taban (boş = varsayılan dal)"))
            .put("commit_message", s("Kısa commit mesajı"))
            .put("tasks", arr(JSONObject().put("type", "object").put("properties", JSONObject()
                .put("id", s("t1, t2 …"))
                .put("title", s("Kısa başlık"))
                .put("description", s("Ne yapılacak ve neden; Uygulayıcı yalnızca bunu ve dosyaları görür"))
                .put("files", arr(JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("path", s("Dosya yolu")).put("action", s("modify | create | delete"))
                    .put("instructions", s("Bu dosyada yapılacak değişiklik, adım adım"))), "Bu görevin sahip olduğu dosyalar"))
                .put("acceptance_criteria", arr(s("Ölçüt"), "Bitti sayılması için"))
                .put("depends_on", arr(s("id"), "Önce bitmesi gereken görevler"))
                .put("requires_ci", JSONObject().put("type", "boolean").put("description", "Commit sonrası CI beklensin mi"))),
                "2-6 alt görev"))).put("required", JSONArray(listOf("repo", "branch", "tasks"))),
    )

    val submitReview = ToolSpec(
        "submit_review",
        "İnceleme sonucunu gönderir. Ölçütler karşılanıyorsa approved=true ve issues boş.",
        JSONObject().put("type", "object").put("properties", JSONObject()
            .put("approved", JSONObject().put("type", "boolean"))
            .put("summary", s("Bir iki cümle"))
            .put("issues", arr(JSONObject().put("type", "object").put("properties", JSONObject()
                .put("task_id", s("İlgili alt görev id")).put("path", s("Düzeltilecek dosya")).put("problem", s("Sorun ve beklenen düzeltme"))),
                "Yalnızca gerçek hatalar ve eksik ölçütler"))).put("required", JSONArray(listOf("approved"))),
    )

    fun parseReview(call: ToolCall?, fallbackText: String): Review {
        if (call == null) return Review(false, emptyList(), fallbackText.take(800), readable = false)
        val a = call.args
        val issues = (Args.elements(a, "issues") ?: emptyList()).mapNotNull { e ->
            val o = e as? JSONObject ?: (e as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@mapNotNull null
            val path = o.optString("path").ifBlank { o.optString("file") }.trim().removePrefix("./").removePrefix("/")
            val problem = o.optString("problem").ifBlank { o.optString("description") }.trim()
            if (!Args.looksLikePath(path) || problem.isBlank()) null
            else ReviewIssue(o.optString("task_id").trim().ifBlank { null }, path, problem)
        }
        val approved = a.optBoolean("approved", issues.isEmpty()) && issues.isEmpty()
        return Review(approved, issues, a.optString("summary").trim())
    }

    /** What follows a review: commit, another fix round, or (cap reached / unclear answer) ask the user. */
    fun afterReview(r: Review, round: Int, maxRounds: Int): ReviewAction = when {
        r.approved -> ReviewAction.COMMIT
        r.readable && r.issues.isNotEmpty() && round < maxRounds -> ReviewAction.FIX
        else -> ReviewAction.ASK_USER
    }

    /** Review findings become fix tickets: one per file, so their owners stay disjoint. */
    fun ticketsFor(review: Review, round: Int, plan: OrchestratorPlan): List<SubTask> =
        review.issues.groupBy { it.path }.entries.mapIndexed { i, (path, list) ->
            val owner = list.firstNotNullOfOrNull { it.taskId }?.let { plan.task(it) }
            SubTask(
                id = "r$round-${i + 1}",
                title = "Düzeltme: ${path.substringAfterLast('/')}",
                description = "İnceleyici şu sorunları buldu; yalnızca bunları düzelt:\n" + list.joinToString("\n") { "- ${it.problem}" },
                files = listOf(TaskFile(path, "modify", list.joinToString("\n") { it.problem })),
                acceptance = owner?.acceptance.orEmpty(),
                requiresCi = owner?.requiresCi ?: false,
                fix = true,
            )
        }

    /** Compact unified-style diff of the staged changes, for the reviewer. */
    fun renderDiff(originals: Map<String, String?>, state: Map<String, String?>, deleted: Set<String>, maxChars: Int = 40_000): String {
        val sb = StringBuilder()
        for (path in (state.keys + deleted).distinct().sorted()) {
            if (sb.length >= maxChars) { sb.append("\n…(diff kısaltıldı)\n"); break }
            if (path in deleted) { sb.append("--- ").append(path).append(" (silindi)\n"); continue }
            val before = originals[path] ?: ""
            val after = state[path] ?: continue
            if (before == after) continue
            sb.append("=== ").append(path).append(if (originals[path] == null) " (yeni)" else "").append('\n')
            val a = before.split("\n"); val b = after.split("\n")
            var pre = 0
            while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
            var suf = 0
            while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
            val lines = DiffLines.block(a.subList(pre, a.size - suf).joinToString("\n"), b.subList(pre, b.size - suf).joinToString("\n"))
            val ctx = 2
            val keep = BooleanArray(lines.size)
            lines.forEachIndexed { i, l -> if (l.kind != ' ') for (k in (i - ctx).coerceAtLeast(0)..(i + ctx).coerceAtMost(lines.size - 1)) keep[k] = true }
            if (pre > 0) sb.append("@@ satır ${pre + 1}\n")
            var gap = false
            val fileStart = sb.length
            lines.forEachIndexed { i, l ->
                if (!keep[i]) { if (!gap) { sb.append("…\n"); gap = true }; return@forEachIndexed }
                gap = false
                sb.append(l.kind).append(' ').append(l.text).append('\n')
                if (sb.length - fileStart > 8_000) { sb.append("…(dosya diff'i kısaltıldı)\n"); return@forEachIndexed }
            }
        }
        return sb.toString().take(maxChars + 200)
    }

    /** A failed normal plan as a one-subtask paused card; "Tekrar Dene" re-runs only the executor. */
    fun repairPlan(repo: String, branch: String, base: String?, message: String, planText: String,
                   files: List<com.suarez.coder.agent.PlanFile>, error: String): OrchestratorPlan =
        OrchestratorPlan(
            mode = OrchestratorMode.LIGHT_SEQUENTIAL, request = planText.take(2_000), repo = repo, branch = branch, base = base,
            commitMessage = message,
            tasks = listOf(SubTask("fix", "Planı uygula", planText, files.map { TaskFile(it.path, it.action, it.instructions) },
                status = TaskStatus.FAILED, error = error.take(1_500))),
            state = PlanState.PAUSED, repair = true,
            note = "Uygulayıcı denemelerini bitirdi. Mimar otomatik çağrılmadı.",
        )

    fun handoffFor(task: SubTask, summary: String): String =
        ("${task.title}: " + summary.lines().filter { it.isNotBlank() }.joinToString("; ").ifBlank { "değişiklik yok" }).take(600)
}

/** Shared pieces of both orchestrators. */
abstract class OrchestratorBase(protected val agent: Agent, protected val hooks: OrchestratorHooks) {
    protected val editor = ArchitectEditor(agent)
    protected val settings get() = agent.settings

    protected fun costSince(list: List<ChatMessage>, from: Int) =
        list.drop(from).filterIsInstance<ChatMessage.UsageRecord>().sumOf { agent.costOf(it) }

    /** Last saved plan, so an interrupted run can still be stored as paused. */
    var current: OrchestratorPlan? = null
        private set

    protected fun save(p: OrchestratorPlan, ui: AgentUi): OrchestratorPlan { current = p; hooks.save(p); ui.update(); return p }

    /** Stops at a step boundary: the pause button or the budget (no dialog in the middle of a subtask). */
    protected fun stopReason(history: List<ChatMessage>): String? = when {
        hooks.pauseRequested() -> "Kullanıcı duraklattı."
        agent.budgetExceeded(history) -> "Bütçe eşiği aşıldı (${agent.money(agent.sessionCost(history))}). Devam Et eşiği bir kez daha genişletir."
        else -> null
    }

    protected fun planFiles(t: SubTask) = t.files.map { PlanFile(it.path, it.action, it.instructions.ifBlank { t.description }) }

    protected fun notesFor(t: SubTask, handoffs: List<String>) = buildString {
        if (t.description.isNotBlank()) append(t.description).append('\n')
        if (t.acceptance.isNotEmpty()) append("Kabul ölçütleri:\n").append(t.acceptance.joinToString("\n") { "- $it" }).append('\n')
        if (handoffs.isNotEmpty()) append("Önceki alt görevler (özet):\n").append(handoffs.joinToString("\n") { "- $it" })
    }.trim()

    /** Runs the editor ladder for one subtask on an isolated history; nothing from the chat is sent. */
    protected suspend fun work(
        local: MutableList<ChatMessage>, ui: AgentUi, plan: OrchestratorPlan, t: SubTask, handoffs: List<String>,
        originals: MutableMap<String, String?>, state: MutableMap<String, String?>, outputs: StringBuilder,
    ): ArchitectEditor.Outcome {
        val files = planFiles(t)
        val text = editor.planTextFor("${plan.commitMessage} — ${t.title}", notesFor(t, handoffs), files)
        return editor.editFiles(local, ui, text, files, plan.repo, plan.branch, plan.base, originals, state, outputs)
    }

    /** Waits for CI on the branch; returns (failed, report). */
    protected suspend fun waitCi(plan: OrchestratorPlan): Pair<Boolean, String> {
        val r = runCatching {
            agent.tools.execute(ToolCall("orch_ci", "ci_wait", JSONObject().put("repo", plan.repo).put("ref", plan.branch)),
                readOnly = false, allowOps = true)
        }.getOrElse { "CI okunamadı: ${it.message}" }
        return r.contains("\nHatalar (") to r
    }

    protected suspend fun review(history: MutableList<ChatMessage>, ui: AgentUi, plan: OrchestratorPlan, diff: String, ci: String?): Review {
        val prompt = buildString {
            append("KULLANICI İSTEĞİ:\n").append(plan.request.take(3_000)).append("\n\nALT GÖREVLER VE KABUL ÖLÇÜTLERİ:\n")
            plan.tasks.filter { !it.fix }.forEach { t ->
                append("- ").append(t.id).append(": ").append(t.title).append(" [").append(t.ownedFiles.joinToString()).append("]\n")
                t.acceptance.forEach { append("    • ").append(it).append('\n') }
            }
            append("\nBİRLEŞİK DIFF:\n").append(diff)
            if (ci != null) append("\n\nCI SONUCU:\n").append(ci.take(8_000))
        }
        // After planning the architect is not woken again: review runs on an executor-class model.
        val reply = agent.callModel(history, listOf(ChatMessage.User(prompt)), agent.escalationRef() ?: agent.editorRef(), "reviewer", REVIEWER_SYSTEM,
            listOf(OrchestratorTools.submitReview), ui, useCache = false, maxOut = settings.maxOutArchitect, thinking = "low")
        return OrchestratorTools.parseReview(reply.toolCalls.firstOrNull { it.name == "submit_review" }, reply.text)
    }

    companion object {
        const val REVIEWER_SYSTEM = "You are a strict code REVIEWER. Check the combined diff (and CI result, if given) against the user's " +
            "request and each subtask's acceptance criteria. Report only real bugs, compile errors, missing criteria or broken behaviour, " +
            "each tied to one file path. Do not ask for style changes. Always answer by calling submit_review."
    }
}

/** Planning step shared by both modes: the architect splits the request into subtasks. */
class OrchestratorPlanner(private val agent: Agent) {
    companion object {
        fun system(base: String, mode: OrchestratorMode) = base + "\n\nORCHESTRATOR MODE (" + mode.label + "): do NOT call submit_plan. " +
            "Split the request into 2-6 small subtasks and call submit_task_plan once. Each subtask owns its files; never give the " +
            "same file to two subtasks unless one depends_on the other. Give every file precise step-by-step instructions, because " +
            "a cheaper editor implements each subtask seeing only its description, its files and short hand-offs. " +
            "Add acceptance_criteria a reviewer can check from the diff. Set requires_ci only when CI must pass before continuing."
    }

    /** Bounded like the normal architect: at most two calls, read-only, same prefix; may delegate to the executor. */
    suspend fun plan(history: MutableList<ChatMessage>, ui: AgentUi, mode: OrchestratorMode): OrchestratorPlan? {
        val request = (history.lastOrNull { it is ChatMessage.User } as? ChatMessage.User)?.text.orEmpty()
        val start = history.size
        val specs = agent.tools.singleShotSpecs.filter { it.name != "submit_plan" } + OrchestratorTools.submitTaskPlan
        val outcome = agent.architectTurns(history, ui, "planner", system(agent.architectSystem(agent.architectHasMap), mode), specs,
            "submit_task_plan") { call -> PlanJson.parse(call.args, agent.settings.defaultRepo, mode, request).second }
        return when (outcome) {
            is com.suarez.coder.agent.ArchOutcome.Submitted -> {
                val plan = PlanJson.parse(outcome.call.args, agent.settings.defaultRepo, mode, request).first!!
                history += ChatMessage.ToolResult(outcome.call.id, outcome.call.name, "Plan alındı: ${plan.tasks.size} alt görev.")
                val cost = history.drop(start).filterIsInstance<ChatMessage.UsageRecord>().sumOf { agent.costOf(it) }
                plan.addCost("Planlayıcı", cost)
            }
            is com.suarez.coder.agent.ArchOutcome.Delegated -> { agent.runDelegated(history, ui, outcome.call, outcome.instructions); null }
            is com.suarez.coder.agent.ArchOutcome.Answered -> null
            is com.suarez.coder.agent.ArchOutcome.Unusable -> {
                history += ChatMessage.Assistant("⚠️ Planlayıcı ${Agent.MAX_ARCHITECT_CALLS} çağrıda kullanılabilir bir görev listesi vermedi; değişiklik yapılmadı.\n\n${outcome.why.take(400)}", model = "coder")
                null
            }
        }
    }
}
