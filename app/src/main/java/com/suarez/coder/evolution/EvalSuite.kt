package com.suarez.coder.evolution

import android.content.Context
import com.suarez.coder.data.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.sqrt

/** Statistics for small evaluation sets: honest intervals instead of bare percentages. */
object Stats {
    /** Wilson score interval for k successes out of n (95%). */
    fun wilson(k: Int, n: Int, z: Double = 1.96): Pair<Double, Double> {
        if (n == 0) return 0.0 to 1.0
        val p = k.toDouble() / n
        val d = 1 + z * z / n
        val c = (p + z * z / (2 * n)) / d
        val h = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / d
        return (c - h).coerceAtLeast(0.0) to (c + h).coerceAtMost(1.0)
    }

    /** Mean with a percentile bootstrap 95% interval (seeded, so the same data always gives the same answer). */
    fun bootstrapMean(values: List<Double>, iters: Int = 2_000, seed: Long = 7): Triple<Double, Double, Double> {
        if (values.isEmpty()) return Triple(0.0, 0.0, 0.0)
        val mean = values.average()
        if (values.size == 1) return Triple(mean, mean, mean)
        val r = java.util.Random(seed)
        val means = DoubleArray(iters) { values.indices.sumOf { values[r.nextInt(values.size)] } / values.size }.sorted()
        return Triple(mean, means[(iters * 0.025).toInt()], means[(iters * 0.975).toInt().coerceAtMost(iters - 1)])
    }

    private fun logChoose(n: Int, k: Int): Double = lnFact(n) - lnFact(k) - lnFact(n - k)
    private fun lnFact(n: Int): Double = (2..n).sumOf { ln(it.toDouble()) }

    /**
     * Exact McNemar test (two-sided binomial on the discordant pairs): [b] cases only the first run passed, [c] only
     * the second. Returns the p-value; 1.0 when there is nothing to compare.
     */
    fun mcnemarP(b: Int, c: Int): Double {
        val n = b + c
        if (n == 0) return 1.0
        val k = minOf(b, c)
        val tail = (0..k).sumOf { exp(logChoose(n, it) - n * ln(2.0)) }
        return (2 * tail).coerceAtMost(1.0)
    }
}

/** One evaluation of the fixed task set with one app version and configuration. */
data class EvalRun(val at: Long, val version: String, val config: String, val results: List<Result>) {
    /** pass: null when CI could not start (not counted). */
    data class Result(val caseId: String, val pass: Boolean?, val cost: Double, val ms: Long)

    val measured get() = results.filter { it.pass != null }
    val passed get() = measured.count { it.pass == true }

    fun summary(): String {
        val n = measured.size
        val (lo, hi) = Stats.wilson(passed, n)
        val (c, clo, chi) = Stats.bootstrapMean(measured.map { it.cost })
        val mins = measured.map { it.ms / 60_000.0 }.average().takeIf { !it.isNaN() } ?: 0.0
        return "$version · $passed/$n geçti (%95 aralık %${pct(lo)}–%${pct(hi)}) · görev başı ${usd(c)} (${usd(clo)}–${usd(chi)}) · ~${String.format(Locale.US, "%.1f", mins)} dk"
    }

    fun toJson(): JSONObject = JSONObject().put("at", at).put("v", version).put("cfg", config).put("r", JSONArray().also { a ->
        results.forEach { a.put(JSONObject().put("id", it.caseId).put("p", it.pass ?: JSONObject.NULL).put("c", it.cost).put("ms", it.ms)) }
    })

    companion object {
        fun pct(v: Double) = String.format(Locale.US, "%.0f", v * 100)
        fun usd(v: Double) = "$" + String.format(Locale.US, "%.3f", v)
        fun fromJson(o: JSONObject) = EvalRun(o.getLong("at"), o.optString("v"), o.optString("cfg"),
            o.getJSONArray("r").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { r ->
                Result(r.getString("id"), if (r.isNull("p")) null else r.getBoolean("p"), r.optDouble("c"), r.optLong("ms"))
            } } })

        /**
         * Paired comparison on the cases both runs measured: McNemar on pass/fail, bootstrap on the cost difference.
         * Says plainly when the set is too small to call a difference.
         */
        fun compare(old: EvalRun, new: EvalRun): String {
            val a = old.measured.associateBy { it.caseId }
            val pairs = new.measured.mapNotNull { r -> a[r.caseId]?.let { it to r } }
            if (pairs.size < 2) return "Karşılaştırma için ortak ölçülmüş görev yok."
            val b = pairs.count { (x, y) -> x.pass == true && y.pass != true }   // got worse
            val c = pairs.count { (x, y) -> x.pass != true && y.pass == true }   // got better
            val p = Stats.mcnemarP(b, c)
            val (d, lo, hi) = Stats.bootstrapMean(pairs.map { (x, y) -> y.cost - x.cost })
            val verdict = when {
                p < 0.05 && c > b -> "başarı ANLAMLI biçimde arttı"
                p < 0.05 && b > c -> "başarı ANLAMLI biçimde düştü"
                else -> "başarı farkı anlamlı değil"
            }
            val costNote = when {
                hi < 0 -> "maliyet anlamlı düştü"
                lo > 0 -> "maliyet anlamlı arttı"
                else -> "maliyet farkı belirsiz"
            }
            return "${old.version} → ${new.version}: ${pairs.size} ortak görev; $c iyileşti, $b kötüleşti, $verdict (p=${String.format(Locale.US, "%.2f", p)}); " +
                "görev başı maliyet farkı ${usd(d)} (${usd(lo)}…${usd(hi)}), $costNote." +
                if (pairs.size < 8) " Not: set küçük; yalnızca büyük farklar anlamlı çıkar." else ""
        }
    }
}

/** The frozen task set and the history of its runs. */
class EvalStore(private val dir: File) {
    private val suiteFile get() = File(dir, "eval_suite.json")
    private val runsFile get() = File(dir, "eval_runs.json")

    @Synchronized fun suite(): List<ExamCase> = runCatching {
        val a = JSONArray(suiteFile.readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> ExamCase(o.getString("id"), o.getString("repo"), o.getString("base"), o.getString("req"), o.optString("route")) } }
    }.getOrDefault(emptyList())

    @Synchronized fun saveSuite(cases: List<ExamCase>) {
        dir.mkdirs()
        suiteFile.writeText(JSONArray().also { a -> cases.forEach { a.put(JSONObject().put("id", it.id).put("repo", it.repo).put("base", it.baseSha).put("req", it.request).put("route", it.route)) } }.toString())
    }

    @Synchronized fun runs(): List<EvalRun> = runCatching {
        val a = JSONArray(runsFile.readText()); (0 until a.length()).map { EvalRun.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized fun add(r: EvalRun) {
        dir.mkdirs()
        runsFile.writeText(JSONArray().also { a -> (runs() + r).takeLast(40).forEach { a.put(it.toJson()) } }.toString())
    }

    companion object { const val SIZE = 6 }
}

/**
 * Runs the frozen set with the current version and configuration, inside the weekly exam budget. The same tasks every
 * time, so versions and settings can be compared fairly.
 */
class EvalSuite(private val context: Context, private val settings: Settings, private val store: EvolutionStore, private val onStatus: (String) -> Unit = {}) {
    val evals = EvalStore(store.dir)

    fun version(): String = runCatching {
        val i = context.packageManager.getPackageInfo(context.packageName, 0)
        "${i.versionName} (${i.longVersionCode})"
    }.getOrDefault("?")

    fun config() = "uyg=${settings.editorModel.substringAfterLast('/')}, mimar=${settings.architectModel.substringAfterLast('/')}" +
        (settings.escalationChain.lines().filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { ", sıra=" + it.joinToString(">") { m -> m.substringAfterLast('/') } } ?: "")

    /** Builds the set once from successful tasks (newest, varied); [refresh] rebuilds it. */
    fun ensureSuite(refresh: Boolean = false): List<ExamCase> {
        val cur = evals.suite()
        if (cur.isNotEmpty() && !refresh) return cur
        val unusable = store.seenRepos().filter { it.startsWith("case:") }.map { it.removePrefix("case:") }.toSet()
        val picked = ExamCases.pick(ExamCases.from(store.traces(), unusable), EvalStore.SIZE)
        if (picked.isNotEmpty()) evals.saveSuite(picked)
        return picked
    }

    suspend fun run(budgetLeft: () -> Double): String {
        val cases = ensureSuite()
        if (cases.size < 2) return "Değerlendirme seti için en az 2 başarılı, CI'lı görev gerekli."
        val runner = ExamRunner(context, settings, budgetLeft, onStatus)
        val base = ExamRunner.Variant(rules = store.policyRules(), tag = "eval")
        val results = ArrayList<EvalRun.Result>()
        for (c in cases) {
            if (budgetLeft() <= 0.01) break
            val t0 = System.currentTimeMillis()
            val s = runCatching { runner.score(c, base, realConfig = true) }.getOrNull()
            results += EvalRun.Result(c.id, s?.let { it.passed > 0 }, runner.lastCost, System.currentTimeMillis() - t0)
            if (runner.lastCost > 0) store.addLog(EvoTier.EXAM, "Değerlendirme: ${c.request.take(50)}", runner.lastCost)
        }
        if (results.isEmpty()) return "Değerlendirme çalışmadı (bütçe)."
        val prev = evals.runs().lastOrNull()
        val run = EvalRun(System.currentTimeMillis(), version(), config(), results)
        evals.add(run)
        val msg = "Değerlendirme: " + run.summary() + (prev?.let { "\n" + EvalRun.compare(it, run) } ?: "")
        store.addLog(EvoTier.EXAM, msg.take(300))
        return msg
    }
}
