package com.suarez.coder.evolution

import android.content.Context
import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ModelRef
import org.json.JSONArray
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

/**
 * The "manager": picks models from the user's pool per task type (learned), keeps the adopted working rules, and
 * turns finished runs into learning signals. Everything here is local and costs no API calls.
 */
class SelfManager(private val settings: Settings, val store: EvolutionStore) {
    val bandit = ModelBandit(File(store.dir, "bandit.json"))

    /** role -> model for this route, or empty when the user has not given a pool of at least two usable models. */
    fun pick(route: String): Map<String, ModelRef> {
        if (!settings.learnedRouting) return emptyMap()
        val pool = settings.modelPool.mapNotNull { ModelRef.parse(it) }.filter { settings.keyFor(it.provider).isNotBlank() }
        if (pool.size < 2) return emptyMap()
        return ModelBandit.rolesFor(route).associateWith { role ->
            val chosen = bandit.choose(route, role, pool.map { it.key }, settings.costWeight)
            pool.first { it.key == chosen }
        }
    }

    /** Counts a finished run for every model the manager chose for it. */
    fun learn(t: SessionTrace) {
        val arms = parseArms(t.arm)
        if (arms.isEmpty()) return
        val reward = Outcome.of(t)
        arms.forEach { (route, role, model) -> bandit.record(route, role, model, reward, t.totalCostUsd / arms.size) }
    }

    /** The user's thumbs or a correction changed how good a run was. */
    fun relearn(before: SessionTrace, after: SessionTrace) {
        val old = Outcome.of(before); val new = Outcome.of(after)
        if (old == new) return
        parseArms(after.arm).forEach { (route, role, model) -> bandit.adjust(route, role, model, old, new) }
    }

    fun policyText(): String = store.policyRules().joinToString("\n") { "- $it" }

    companion object {
        fun parseArms(arm: String?): List<Triple<String, String, String>> = arm.orEmpty().split(';').mapNotNull { p ->
            val x = p.split('|')
            if (x.size == 3 && x.all { it.isNotBlank() }) Triple(x[0], x[1], x[2]) else null
        }
    }
}

/** Records uncaught crashes to files; the next start reports them (a learning signal and the rollback trigger). */
object CrashRecorder {
    private fun dir(context: Context) = File(context.filesDir, "crashes").apply { mkdirs() }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode }.getOrDefault(0L)
                File(dir(app), "crash-${System.currentTimeMillis()}-v$version.txt").writeText("${thread.name}\n$sw".take(20_000))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    data class Crash(val at: Long, val version: Long, val text: String) {
        val headline get() = text.lines().drop(1).firstOrNull { it.isNotBlank() }?.trim()?.take(200).orEmpty()
    }

    /** Crashes recorded so far (and marks them as seen). */
    fun collect(context: Context): List<Crash> = dir(context).listFiles().orEmpty().filter { it.name.endsWith(".txt") }.mapNotNull { f ->
        val m = Regex("crash-(\\d+)-v(\\d+)\\.txt").find(f.name) ?: return@mapNotNull null
        val c = Crash(m.groupValues[1].toLong(), m.groupValues[2].toLong(), f.readText())
        f.renameTo(File(f.parentFile, f.name.removeSuffix(".txt") + ".seen"))
        c
    }

    fun recent(context: Context, sinceMs: Long): Int = dir(context).listFiles().orEmpty()
        .mapNotNull { Regex("crash-(\\d+)-").find(it.name)?.groupValues?.get(1)?.toLongOrNull() }.count { it >= sinceMs }
}

/** What the manager did in the last 7 days, in numbers. */
data class WeeklyReport(
    val tasks: Int,
    val successRate: Double?,
    val avgCost: Double?,
    val totalCost: Double,
    val thumbsUp: Int,
    val thumbsDown: Int,
    val evolutionCost: Double,
    val examCost: Double,
    val adopted: List<String>,
    val rejected: List<String>,
    val crashes: Int,
    val bestModels: String,
) {
    companion object {
        const val WEEK_MS = 7L * 24 * 3_600_000

        fun compute(traces: List<SessionTrace>, log: List<EvolutionLogEntry>, experiments: List<Experiment>,
                    crashes: Int, bestModels: String, now: Long = System.currentTimeMillis()): WeeklyReport {
            val since = now - WEEK_MS
            val week = traces.filter { it.at >= since }
            val wlog = log.filter { it.at >= since }
            val exps = experiments.filter { it.decidedAt >= since }
            return WeeklyReport(
                tasks = week.size,
                successRate = week.takeIf { it.isNotEmpty() }?.map { Outcome.of(it) }?.average(),
                avgCost = week.takeIf { it.isNotEmpty() }?.map { it.totalCostUsd }?.average(),
                totalCost = week.sumOf { it.totalCostUsd },
                thumbsUp = week.count { it.rating > 0 },
                thumbsDown = week.count { it.rating < 0 },
                evolutionCost = wlog.filter { it.tier != EvoTier.EXAM }.sumOf { it.costUsd },
                examCost = wlog.filter { it.tier == EvoTier.EXAM }.sumOf { it.costUsd },
                adopted = exps.filter { it.state == Experiment.State.ADOPTED }.map { it.title },
                rejected = exps.filter { it.state == Experiment.State.REJECTED }.map { it.title },
                crashes = crashes,
                bestModels = bestModels,
            )
        }
    }

    fun lines(): List<String> = buildList {
        add("$tasks görev" + (successRate?.let { " · başarı %${(it * 100).toInt()}" } ?: "") +
            (avgCost?.let { " · görev başı $" + String.format(Locale.US, "%.3f", it) } ?: ""))
        if (thumbsUp + thumbsDown > 0) add("Değerlendirmelerin: $thumbsUp iyi, $thumbsDown kötü")
        add("Harcama: görevler $" + String.format(Locale.US, "%.3f", totalCost) + " · öz-gelişim $" +
            String.format(Locale.US, "%.3f", evolutionCost) + " · sınav $" + String.format(Locale.US, "%.3f", examCost))
        if (adopted.isNotEmpty()) add("Kabul edilen: " + adopted.joinToString("; "))
        if (rejected.isNotEmpty()) add("Reddedilen: " + rejected.joinToString("; "))
        if (crashes > 0) add("Çökme: $crashes")
        if (bestModels.isNotBlank()) add("Şu an en iyi: $bestModels")
    }
}

internal fun jsonStrings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
