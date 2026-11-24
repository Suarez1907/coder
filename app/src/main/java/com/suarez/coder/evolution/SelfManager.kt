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
    val contextual = LinearThompson(File(store.dir, "cbandit.json"))
    val decisions = OffPolicy.Store(File(store.dir, "cbandit_log.jsonl"))

    /** A choice: role -> model, and for a contextual choice the context and per-role propensities. */
    data class Pick(val models: Map<String, ModelRef>, val ctx: DoubleArray? = null, val prop: String? = null)

    private fun penalty(route: String, role: String, model: String): Double {
        val ref = ModelBandit.refCost(route)
        val arm = bandit.arms(route, role)[model] ?: ModelBandit.Arm()
        val c = if (arm.n == 0) ref else arm.avgCost
        return settings.costWeight * (c / (c + ref))
    }

    /**
     * role -> model for this route, or null when the user has not given a pool of at least two usable models.
     * With a task context, the contextual bandit chooses (and records how likely the choice was).
     */
    fun pick(route: String, ctx: DoubleArray? = null): Pick? {
        if (!settings.learnedRouting) return null
        val pool = settings.modelPool.mapNotNull { ModelRef.parse(it) }.filter { settings.keyFor(it.provider).isNotBlank() }
        if (pool.size < 2) return null
        if (settings.contextualRouting && ctx != null) {
            val props = ArrayList<String>()
            val models = ModelBandit.rolesFor(route).associateWith { role ->
                val c = contextual.choose("$route|$role", pool.map { it.key }, ctx, penalty = { m -> penalty(route, role, m) })
                props += "$role=${c.propensity}"
                pool.first { it.key == c.model }
            }
            return Pick(models, ctx, props.joinToString(";"))
        }
        return Pick(ModelBandit.rolesFor(route).associateWith { role ->
            val chosen = bandit.choose(route, role, pool.map { it.key }, settings.costWeight)
            pool.first { it.key == chosen }
        })
    }

    /** Estimated utility (success − cost penalty) of [model] in context [x]: the reward model for off-policy evaluation. */
    fun estimate(route: String, role: String, model: String, x: DoubleArray) =
        contextual.predict("$route|$role", model, x) - penalty(route, role, model)

    /**
     * Off-policy evaluation on the logged decisions: what the learned contextual rule and "always model X" would have
     * scored on the same tasks (doubly robust, with 95% intervals), next to what really happened.
     */
    fun policyReport(minLogs: Int = 10): List<String> {
        val logs = decisions.all()
        if (logs.size < minLogs) return emptyList()
        val models = logs.map { it.model }.distinct()
        val rhat = { l: OffPolicy.Log, m: String -> estimate(l.route, l.role, m, l.x) }
        val learned = { l: OffPolicy.Log -> models.maxBy { estimate(l.route, l.role, it, l.x) } }
        val (m, lo, hi) = Stats.bootstrapMean(logs.map { it.reward })
        val actual = "Gerçekte uygulanan: ${String.format(Locale.US, "%.2f", m)} (%95 ${String.format(Locale.US, "%.2f", lo)}–${String.format(Locale.US, "%.2f", hi)}), ${logs.size} karar"
        val rows = listOf(OffPolicy.evaluate("Öğrenen bağlamsal seçim", logs, learned, rhat)) +
            models.map { md -> OffPolicy.evaluate("Hep ${md.substringAfterLast('/')}", logs, { md }, rhat) }
        return listOf(actual) + rows.sortedByDescending { it.dr }.map { OffPolicy.format(it) }
    }

    /** Counts a finished run for every model the manager chose for it. */
    fun learn(t: SessionTrace) {
        val arms = parseArms(t.arm)
        if (arms.isEmpty()) return
        val reward = Outcome.of(t)
        arms.forEach { (route, role, model) -> bandit.record(route, role, model, reward, t.totalCostUsd / arms.size) }
        // Contextual: learn success in this context, and log the decision for off-policy evaluation.
        val x = t.ctx?.toDoubleArray() ?: return
        val props = t.prop.orEmpty().split(';').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1].toDoubleOrNull() ?: 1.0) } }.toMap()
        arms.forEach { (route, role, model) ->
            contextual.record("$route|$role|$model", x, reward)
            val utility = reward - penalty(route, role, model)
            decisions.add(OffPolicy.Log(route, role, model, x, props[role] ?: 1.0, utility, t.at))
        }
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
