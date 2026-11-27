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
        val choice = EvolutionModel.fromFields(settings.architectModel, settings.evoProvider, settings.evoModel, settings.evoGeminiModel)
        val left = EvolutionModel.budgetLeft(spent, config.dailyBudgetUsd)
        val llm = evolutionCompleter(settings, choice)
        val actions = runCatching {
            engine.scan(
                probe = { repo -> probe(gh, repo) },
                openDraft = if (config.draftFix) { req, files -> openDraft(gh, req, files) } else null,
                model = choice,
                budgetLeft = left,
                diagnose = { log, _, cap -> llm?.complete(DIAGNOSE_SYSTEM, log, cap) },
                proposePatch = { cause, _, cap -> llm?.complete(PATCH_SYSTEM, cause, cap) },
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

    private suspend fun openDraft(gh: GitHub, req: DraftRequest, files: List<Pair<String, String>>): Int? {
        if (!RepoSentinel.allowsPush(req.branch, merge = false)) return null
        if (req.branch.equals(req.base, true) || req.branch.equals("main", true) || req.branch.equals("master", true)) return null
        runCatching { gh.createBranch(req.repo, req.branch, req.base) }.getOrElse { return null }
        val note = "Kök neden: ${req.cause}\n\nBu dal otomatik merge edilmez. İncelemeden birleştirme.\n"
        val changes = files.map { (path, content) -> GitHub.FileChange(path, content, false) } +
            listOf(GitHub.FileChange(".coder/sentinel.md", note, false))
        runCatching {
            gh.commitFiles(req.repo, req.branch, "sentinel: düzeltme", changes, req.base)
        }.getOrElse { return null }
        val opened = runCatching {
            gh.createPullRequest(req.repo, "Bekçi: ${req.repo} CI", req.branch, req.base, note, draft = true)
        }.getOrNull() ?: return null
        return Regex("#(\\d+)").find(opened)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun evolutionCompleter(settings: Settings, choice: EvolutionChoice): EvolutionCompleter? {
        val key = if (choice.provider == EvolutionProvider.GEMINI) settings.geminiKey else settings.openrouterKey
        if (key.isBlank()) return null
        val ref = com.suarez.coder.llm.ModelRef(EvolutionModel.toProvider(choice), choice.modelId)
        val agent = com.suarez.coder.agent.Agent(settings).apply {
            isEvolutionSession = true
            modelOverride = ref
        }
        val silent = object : com.suarez.coder.agent.AgentUi {
            override fun update() {}
            override suspend fun confirm(title: String, body: String, okText: String, cancelText: String) = false
        }
        return EvolutionCompleter { system, user, maxUsd ->
            if (maxUsd <= 0.0) return@EvolutionCompleter null
            val local = ArrayList<com.suarez.coder.llm.ChatMessage>()
            val reply = runCatching {
                agent.callModel(local, listOf(com.suarez.coder.llm.ChatMessage.User(user.take(8_000))), ref, "evolution", system,
                    emptyList(), silent, useCache = false, maxOut = 2_500, thinking = "low")
            }.getOrNull() ?: return@EvolutionCompleter null
            val cost = local.filterIsInstance<com.suarez.coder.llm.ChatMessage.UsageRecord>().sumOf { agent.costOf(it) }
            if (cost > maxUsd) null else EvolutionCompletion(reply.text, cost, choice.modelId, choice.provider)
        }
    }

    companion object {
        val priorityTier: com.suarez.coder.cost.PriorityTier = com.suarez.coder.cost.PriorityTier.BACKGROUND_FLEX
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

        private const val DIAGNOSE_SYSTEM = "You diagnose a CI failure from a filtered log. One short paragraph. No repository names, URLs, or tokens."
        private const val PATCH_SYSTEM = "Reply with one JSON object only: {\"summary\":\"...\",\"files\":[{\"path\":\"...\",\"content\":\"...\"}]}. " +
            "Include the source fix and at least one test file (path contains /test/ or ends with Test.kt). Do not mention merging."
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
        openDraft: (suspend (DraftRequest, List<Pair<String, String>>) -> Int?)? = null,
        model: EvolutionChoice? = null,
        budgetLeft: Double = config.dailyBudgetUsd - config.spentTodayUsd,
        diagnose: suspend (String, EvolutionChoice, Double) -> EvolutionCompletion? = { _, _, _ -> null },
        proposePatch: suspend (String, EvolutionChoice, Double) -> EvolutionCompletion? = { _, _, _ -> null },
    ): List<SentinelAction> {
        if (!RepoSentinel.canRun(config)) return emptyList()
        var left = budgetLeft.coerceAtLeast(0.0)
        val out = ArrayList<SentinelAction>()
        for (repo in config.repos.take(RepoSentinel.MAX_REPOS)) {
            val snap = probe(repo)
            if (!snap.branchRed && snap.failingPr == null) continue
            var cause = snap.cause.ifBlank { "CI kırmızı" }
            val chosen = model
            if (chosen != null && left > 0.0) {
                val reply = runCatching { diagnose(cause, chosen, left) }.getOrNull()
                if (reply != null && reply.costUsd <= left) {
                    left -= reply.costUsd
                    cause = SentinelDiagnosis.merge(snap.cause, reply.text, listOf(repo))
                }
            }
            val branch = "sentinel/fix-$now"
            val patch = if (config.draftFix && openDraft != null && chosen != null && left > 0.0 && RepoSentinel.allowsPush(branch, false)) {
                val reply = runCatching { proposePatch(cause, chosen, left) }.getOrNull()
                if (reply != null && reply.costUsd <= left) {
                    left -= reply.costUsd
                    SentinelPatchParser.parse(reply.text)
                } else null
            } else null
            if (patch != null && openDraft != null) {
                val pr = openDraft(DraftRequest(repo, branch, snap.defaultBranch, cause), patch.files)
                out += SentinelAction.Draft(repo, branch, snap.defaultBranch, pr, cause)
            } else {
                out += SentinelAction.Report(repo, snap.defaultBranch, cause)
            }
        }
        return out
    }
}

object SentinelDiagnosis {
    fun merge(filtered: String, modelText: String?, repos: List<String>): String {
        val base = filtered.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }?.take(180) ?: "CI kırmızı"
        if (modelText.isNullOrBlank()) return base
        val masked = PrivacyFilter.apply(modelText, repos)
        if (masked.rejected || masked.text.isNullOrBlank()) return base
        return masked.text.take(400)
    }
}

data class SentinelPatch(val files: List<Pair<String, String>>, val summary: String)

object SentinelPatchParser {
    fun parse(text: String): SentinelPatch? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null
        val arr = o.optJSONArray("files") ?: return null
        val files = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: return null
            val path = f.optString("path").trim().removePrefix("./")
            val content = f.optString("content")
            if (path.isBlank() || content.isBlank()) return null
            if (path.startsWith("/") || path.contains("..") || path.equals("main", true)) return null
            files += path to content
        }
        if (files.none { isTest(it.first) } || files.none { !isTest(it.first) }) return null
        return SentinelPatch(files, o.optString("summary").ifBlank { "sentinel fix" })
    }

    fun isTest(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return "/test/" in path || name.endsWith("Test.kt") || name.endsWith("Test.java") ||
            name.startsWith("test_") || name.endsWith("_test.py") || name.endsWith("_test.gd")
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
