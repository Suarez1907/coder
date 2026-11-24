package com.suarez.coder.queue

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.agent.QueuedTask
import com.suarez.coder.agent.StopException
import com.suarez.coder.agent.TaskQueueStore
import com.suarez.coder.ci.TaskNotifier
import com.suarez.coder.cost.Ledger
import com.suarez.coder.cost.LedgerEntry
import com.suarez.coder.data.SessionStore
import com.suarez.coder.data.Settings
import com.suarez.coder.evolution.EvolutionEngine
import com.suarez.coder.evolution.WeeklyWorker
import com.suarez.coder.llm.ChatMessage
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs queued tasks unattended (by default only while charging and on Wi-Fi): each on its own coder/q-* branch,
 * ending with a pull request. Queue runs never merge, never call write APIs directly and stop at their budget.
 */
class QueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = Settings(ctx)
        if (!settings.queueEnabled) return Result.success()
        // Cheap hours only (e.g. a provider's off-peak discount): wait for the window.
        val now = java.time.LocalTime.now()
        val wait = QueueWindow.minutesUntil(settings.queueWindowStart, settings.queueWindowEnd, now.hour, now.minute)
        if (wait > 0) { scheduleIn(ctx, wait.toLong()); return Result.success() }
        val store = store(ctx)
        store.recoverStale()
        runCatching { setForeground(WeeklyWorker.foreground(ctx, "Coder iş kuyruğu")) }
        if (settings.issueWatch && settings.githubToken.isNotBlank()) runCatching { IssueWatcher(Agent(settings).tools.github, store).collect(repos(settings)) }
        val results = ArrayList<String>()
        var n = 0
        while (!isStopped && n < MAX_PER_RUN) {
            val t = store.next() ?: break
            n++
            runCatching { setForeground(WeeklyWorker.foreground(ctx, "Kuyruk: ${t.text.lineSequence().first().take(60)}")) }
            store.update(t.id) { it.copy(state = QueuedTask.State.RUNNING, at = System.currentTimeMillis()) }
            val done = runCatching { runTask(ctx, settings, t) }.getOrElse { e -> t.copy(state = QueuedTask.State.FAILED, note = e.message?.take(200) ?: "Hata") }
            store.update(t.id) { done }
            results += "${done.state.label}: ${t.text.lineSequence().first().take(50)}" + (done.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        }
        if (results.isNotEmpty()) TaskNotifier.done(ctx, "İş kuyruğu", "${results.size} görev", results.joinToString("\n"))
        return Result.success()
    }

    private suspend fun runTask(ctx: Context, settings: Settings, t: QueuedTask): QueuedTask {
        val agent = Agent(settings, ctx.filesDir)
        val evo = EvolutionEngine.storeFor(ctx)
        agent.unattended = true
        agent.lessonsFor = { repo -> if (settings.evoEnabled && settings.evoMemory) evo.lessons(repo) else null }
        agent.tools.repoOverride = t.repo
        agent.context.repoOverride = t.repo
        // Writes only to this task's branches; pull requests are allowed, merges and raw write calls are not (Agent.unattended).
        agent.tools.github.writeGuard = { _, branch -> if (branch != null && !branch.startsWith(t.branchPrefix)) "Kuyruk görevi yalnızca ${t.branchPrefix}* dallarına yazabilir." else null }
        var spent = 0.0
        agent.onUsage = { spent += it }
        agent.beforeCall = { if (spent >= settings.queueTaskBudget) throw StopException("Görev bütçesi (${"%.2f".format(settings.queueTaskBudget)} $) doldu.") }
        val sessionId = "q-" + t.id
        val history = mutableListOf<ChatMessage>(ChatMessage.User(TaskQueueStore.prompt(t)))
        val ui = object : AgentUi {
            override fun update() {}
            // Tool permissions and commits are allowed (the write guard limits them); "keep going?" questions stop the run.
            override suspend fun confirm(title: String, body: String, okText: String, cancelText: String) =
                title.startsWith("İzin ver") || title.startsWith("Commit onayı")
        }
        val outcome = try {
            withTimeout(MAX_TASK_MS) { agent.run(history, ui) }
            null
        } catch (e: StopException) {
            e.message ?: "Durduruldu"
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            "Zaman aşımı (${MAX_TASK_MS / 60_000} dk)"
        }
        outcome?.let { history += ChatMessage.Notice(it, isError = true) }
        val sessions = SessionStore(File(ctx.filesDir, "chats"))
        val cost = history.filterIsInstance<ChatMessage.UsageRecord>().sumOf { agent.costOf(it) }
        sessions.save(sessionId, history, t.repo, cost)
        Ledger(File(ctx.filesDir, "ledger.jsonl")).append(history.filterIsInstance<ChatMessage.UsageRecord>().map { r ->
            LedgerEntry(r.at, sessionId, t.repo, r.role, r.model, r.usage.input, r.usage.cached, r.usage.output + r.usage.thoughts, agent.costOf(r))
        })
        val text = history.joinToString("\n") { m -> when (m) { is ChatMessage.Assistant -> m.text; is ChatMessage.ToolResult -> m.content; else -> "" } }
        val pr = Regex("https://github\\.com/[\\w.-]+/[\\w.-]+/pull/\\d+").findAll(text).lastOrNull()?.value
        return t.copy(state = if (pr != null) QueuedTask.State.DONE else QueuedTask.State.FAILED, sessionId = sessionId,
            note = pr ?: outcome ?: "PR açılmadı; sohbete bakın.")
    }

    companion object {
        private const val NAME = "coder-queue"
        private const val PERIODIC = "coder-queue-periodic"
        const val MAX_PER_RUN = 5
        const val MAX_TASK_MS = 90 * 60_000L

        fun store(ctx: Context) = TaskQueueStore(File(ctx.filesDir, "queue.json"))

        fun repos(s: Settings) = (s.profiles().map { it.repo } + s.defaultRepo).map { it.trim().removePrefix("https://github.com/").trim('/') }
            .filter { it.contains('/') }.distinct().take(8)

        private fun constraints(s: Settings, now: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (s.queueRequiresWifi && !now) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(s.queueRequiresCharging && !now)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Runs the queue when the conditions hold ([now]: right away, only a network is needed). */
        fun schedule(ctx: Context, now: Boolean = false) {
            val s = Settings(ctx)
            val req = OneTimeWorkRequestBuilder<QueueWorker>().setConstraints(constraints(s, now)).build()
            runCatching { WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, if (now) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req) }
            schedulePeriodic(ctx)
        }

        private fun scheduleIn(ctx: Context, minutes: Long) {
            val s = Settings(ctx)
            val req = OneTimeWorkRequestBuilder<QueueWorker>().setConstraints(constraints(s, false)).setInitialDelay(minutes, TimeUnit.MINUTES).build()
            runCatching { WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req) }
        }

        /** Issue watch: look for new "coder" issues every 6 hours. */
        fun schedulePeriodic(ctx: Context) {
            val s = Settings(ctx)
            val wm = runCatching { WorkManager.getInstance(ctx) }.getOrNull() ?: return
            if (!s.issueWatch || !s.queueEnabled) { wm.cancelUniqueWork(PERIODIC); return }
            val req = PeriodicWorkRequestBuilder<QueueWorker>(6, TimeUnit.HOURS).setConstraints(constraints(s, false)).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** The hours the queue may run in. */
object QueueWindow {
    /** Minutes until the window opens; 0 when inside it or when no window is set. [end] is exclusive and may wrap midnight. */
    fun minutesUntil(start: Int, end: Int, hour: Int, minute: Int): Int {
        if (start !in 0..23 || end !in 0..23 || start == end) return 0
        val inside = if (start < end) hour in start until end else hour >= start || hour < end
        if (inside) return 0
        val nowMin = hour * 60 + minute
        val startMin = start * 60
        return ((startMin - nowMin) + 24 * 60) % (24 * 60)
    }
}

/** Open issues labelled "coder" become queued tasks (once each). */
class IssueWatcher(private val gh: com.suarez.coder.tools.GitHub, private val store: TaskQueueStore) {
    companion object {
        fun task(repo: String, issue: JSONObject): QueuedTask? {
            if (issue.has("pull_request")) return null
            val n = issue.optInt("number").takeIf { it > 0 } ?: return null
            val body = issue.optString("body").takeIf { it != "null" }.orEmpty().take(4_000)
            return QueuedTask("i${System.currentTimeMillis()}$n", repo,
                "GitHub issue #$n: ${issue.optString("title")}\n\n$body\n\nPR açıklamasına \"Closes #$n\" yaz.", source = "issue:$repo#$n")
        }
    }

    suspend fun collect(repos: List<String>): Int {
        val known = store.all().map { it.source }.toSet()
        var added = 0
        for (repo in repos) {
            val body = runCatching { gh.raw("GET", "/repos/$repo/issues?labels=${TaskQueueStore.ISSUE_LABEL}&state=open&per_page=20", null) }.getOrNull() ?: continue
            if (!body.startsWith("HTTP 200")) continue
            val a = runCatching { JSONArray(body.substringAfter('\n')) }.getOrNull() ?: continue
            for (i in 0 until a.length()) {
                val t = task(repo, a.getJSONObject(i)) ?: continue
                if (t.source in known) continue
                store.add(t); added++
            }
        }
        return added
    }
}
