package com.suarez.coder.evolution

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.suarez.coder.ci.LogFilter
import com.suarez.coder.ci.TaskNotifier
import com.suarez.coder.data.Settings
import com.suarez.coder.tools.GitHub
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background watcher. Off unless the user turns it on. It never pushes to or merges the default branch:
 * a fix, when requested, is a draft pull request on `sentinel/fix-<time>`.
 */
class RepoSentinelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = Settings(ctx)
        val spent = SentinelSpend.read(File(ctx.filesDir, "evolution"))
        val config = RepoSentinel.configOf(settings.sentinelEnabled, settings.sentinelHours, settings.sentinelRepos, settings.sentinelBudget, settings.sentinelDraft, spent)
        if (!RepoSentinel.canRun(config)) return Result.success()
        val gh = GitHub { settings.githubToken }
        val engine = RepoSentinelEngine(config)
        val actions = runCatching {
            engine.scan(
                probe = { repo -> probe(gh, repo) },
                openDraft = if (config.draftFix) { req -> openDraft(gh, req) } else null,
            )
        }.getOrDefault(emptyList())
        val reports = actions.map { action ->
            val pr = (action as? SentinelAction.Draft)?.pr
            val text = if (pr != null) RepoSentinel.notification(action.repo, action.base, pr) else "${action.repo} `${action.base}` CI kırmızı — ${action.cause}"
            if (pr != null || action is SentinelAction.Report) TaskNotifier.done(ctx, "Bekçi", action.repo, text)
            SentinelReport(System.currentTimeMillis(), action.repo, text, pr)
        }
        if (reports.isNotEmpty()) SentinelReports.append(File(ctx.filesDir, "evolution"), reports)
        return Result.success()
    }

    private suspend fun probe(gh: GitHub, repo: String): CiSnapshot {
        val base = runCatching { gh.defaultBranch(repo) }.getOrDefault("main")
        val runs = runCatching { JSONObject(bodyOf(gh.raw("GET", "/repos/$repo/actions/runs?per_page=5&branch=$base", null))) }.getOrNull()
        val arr = runs?.optJSONArray("workflow_runs")
        var red = false
        var logHint = ""
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("head_branch") != base && o.optString("head_branch").isNotBlank()) continue
                val conclusion = o.optString("conclusion")
                if (conclusion == "failure" || conclusion == "timed_out") {
                    red = true
                    val id = o.optLong("id")
                    logHint = runCatching { bodyOf(gh.raw("GET", "/repos/$repo/actions/runs/$id/logs", null)) }.getOrDefault("")
                    break
                }
                if (conclusion == "success") break
            }
        }
        val pulls = runCatching { JSONArray(bodyOf(gh.raw("GET", "/repos/$repo/pulls?state=open&per_page=10", null))) }.getOrNull()
        var failingPr: Int? = null
        if (pulls != null) {
            for (i in 0 until pulls.length()) {
                val pr = pulls.optJSONObject(i) ?: continue
                val head = pr.optJSONObject("head")?.optString("sha").orEmpty()
                val number = pr.optInt("number")
                if (head.isBlank() || number <= 0) continue
                val check = runCatching { JSONObject(bodyOf(gh.raw("GET", "/repos/$repo/commits/$head/status", null))) }.getOrNull()
                if (check?.optString("state") == "failure") { failingPr = number; break }
            }
        }
        val cause = if (logHint.isBlank()) "CI kırmızı" else LogFilter.extract(logHint).lineSequence().firstOrNull { it.isNotBlank() }?.take(180) ?: "CI kırmızı"
        return CiSnapshot(base, red, failingPr, cause)
    }

    private fun bodyOf(raw: String): String = raw.substringAfter('\n')

    private suspend fun openDraft(gh: GitHub, req: DraftRequest): Int? {
        if (!RepoSentinel.allowsPush(req.branch, merge = false)) return null
        if (req.branch.equals(req.base, true)) return null
        runCatching { gh.createBranch(req.repo, req.branch, req.base) }.getOrElse { return null }
        val body = "Kök neden: ${req.cause}\n\nBu dal otomatik merge edilmez. İncelemeden birleştirme.\n"
        runCatching {
            gh.commitFiles(req.repo, req.branch, "sentinel: teşhis", listOf(GitHub.FileChange(".coder/sentinel.md", body, false)), req.base)
        }.getOrElse { return null }
        val opened = runCatching { gh.createPullRequest(req.repo, "Bekçi: ${req.repo} CI", req.branch, req.base, body, draft = true) }.getOrNull()
            ?: return null
        return Regex("#(\\d+)").find(opened)?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        private const val NAME = "coder-sentinel"

        fun schedule(context: Context, hours: Int) {
            val h = RepoSentinel.normalizePeriod(hours).toLong()
            val req = PeriodicWorkRequestBuilder<RepoSentinelWorker>(h, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req) }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
        }
    }
}

data class SentinelConfig(
    val enabled: Boolean = false,
    val periodHours: Int = 12,
    val repos: List<String> = emptyList(),
    val dailyBudgetUsd: Double = 0.10,
    val draftFix: Boolean = false,
    val spentTodayUsd: Double = 0.0,
)

data class CiSnapshot(val defaultBranch: String, val branchRed: Boolean, val failingPr: Int?, val cause: String)

data class DraftRequest(val repo: String, val branch: String, val base: String, val cause: String)

sealed class SentinelAction {
    abstract val repo: String
    abstract val base: String
    abstract val cause: String
    data class Report(override val repo: String, override val base: String, override val cause: String) : SentinelAction()
    data class Draft(override val repo: String, val branch: String, override val base: String, val pr: Int?, override val cause: String) : SentinelAction()
}

data class SentinelReport(val at: Long, val repo: String, val text: String, val pr: Int?)

object RepoSentinel {
    const val MAX_REPOS = 5

    fun normalizePeriod(hours: Int): Int = when (hours) { 6, 24 -> hours; else -> 12 }

    fun configOf(enabled: Boolean, hours: Int, reposText: String, budget: Double, draft: Boolean, spent: Double) = SentinelConfig(
        enabled = enabled,
        periodHours = normalizePeriod(hours),
        repos = reposText.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_REPOS),
        dailyBudgetUsd = budget,
        draftFix = draft,
        spentTodayUsd = spent,
    )

    fun canRun(config: SentinelConfig): Boolean =
        config.enabled && config.repos.isNotEmpty() && config.spentTodayUsd < config.dailyBudgetUsd

    /** Push is allowed only onto an isolated sentinel branch, and never as a merge. */
    fun allowsPush(branch: String, merge: Boolean): Boolean {
        if (merge) return false
        val name = branch.trim().removePrefix("refs/heads/")
        if (name.equals("main", true) || name.equals("master", true)) return false
        return name.startsWith("sentinel/fix-")
    }

    fun notification(repo: String, base: String, pr: Int): String =
        "$repo `$base` CI kırıldı — kök neden tespit edildi, taslak düzeltme PR #$pr hazır. İnceleyip onaylar mısın?"
}

class RepoSentinelEngine(private val config: SentinelConfig) {
    suspend fun scan(
        now: Long = System.currentTimeMillis(),
        probe: suspend (String) -> CiSnapshot,
        openDraft: (suspend (DraftRequest) -> Int?)? = null,
    ): List<SentinelAction> {
        if (!RepoSentinel.canRun(config)) return emptyList()
        val out = ArrayList<SentinelAction>()
        for (repo in config.repos.take(RepoSentinel.MAX_REPOS)) {
            val snap = probe(repo)
            if (!snap.branchRed && snap.failingPr == null) continue
            val cause = snap.cause.ifBlank { "CI kırmızı" }
            if (config.draftFix && openDraft != null) {
                val branch = "sentinel/fix-$now"
                if (!RepoSentinel.allowsPush(branch, merge = false)) continue
                val pr = openDraft(DraftRequest(repo, branch, snap.defaultBranch, cause))
                out += SentinelAction.Draft(repo, branch, snap.defaultBranch, pr, cause)
            } else {
                out += SentinelAction.Report(repo, snap.defaultBranch, cause)
            }
        }
        return out
    }
}

object SentinelSpend {
    fun read(dir: File): Double = runCatching {
        val o = JSONObject(File(dir, "sentinel_spend.json").readText())
        val day = java.time.LocalDate.now().toString()
        if (o.optString("day") != day) 0.0 else o.optDouble("usd", 0.0)
    }.getOrDefault(0.0)
}

object SentinelReports {
    fun read(dir: File): List<SentinelReport> = runCatching {
        val a = JSONArray(File(dir, "sentinel_reports.json").readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { SentinelReport(it.optLong("at"), it.optString("repo"), it.optString("text"), it.optInt("pr").takeIf { n -> n > 0 }) }
    }.getOrDefault(emptyList())

    fun append(dir: File, reports: List<SentinelReport>) {
        dir.mkdirs()
        val all = (read(dir) + reports).takeLast(20)
        File(dir, "sentinel_reports.json").writeText(JSONArray().also { a ->
            all.forEach { a.put(JSONObject().put("at", it.at).put("repo", it.repo).put("text", it.text).put("pr", it.pr ?: 0)) }
        }.toString())
    }
}
