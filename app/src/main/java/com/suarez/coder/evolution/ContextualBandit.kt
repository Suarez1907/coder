package com.suarez.coder.evolution

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/** The task context a model choice is made in (all values roughly 0..1). */
object RouteContext {
    const val DIM = 7
    val NAMES = listOf("sabit", "zorluk", "dosya sayısı", "Kotlin/Java", "JS/TS", "Python", "diğer dil")

    /** [langShares]: share of the repo's source files per language (kotlin/java, js/ts, python, other). */
    fun features(complexity: Double?, files: Int, langShares: DoubleArray): DoubleArray = doubleArrayOf(
        1.0, (complexity ?: 1.5) / 3.0, kotlin.math.ln(1.0 + files) / kotlin.math.ln(10.0),
        langShares.getOrElse(0) { 0.0 }, langShares.getOrElse(1) { 0.0 }, langShares.getOrElse(2) { 0.0 }, langShares.getOrElse(3) { 0.0 },
    )

    fun langShares(paths: List<String>): DoubleArray {
        val src = paths.filter { Regex("\\.(kt|kts|java|js|jsx|ts|tsx|mjs|py|gd|cs|go|rs|swift|dart|c|cpp|h|rb|php|lua)$").containsMatchIn(it) }
        if (src.isEmpty()) return doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        fun s(re: String) = src.count { Regex(re).containsMatchIn(it) }.toDouble() / src.size
        val k = s("\\.(kt|kts|java)$"); val j = s("\\.(js|jsx|ts|tsx|mjs)$"); val p = s("\\.py$")
        return doubleArrayOf(k, j, p, (1 - k - j - p).coerceAtLeast(0.0))
    }
}

/** Small dense linear algebra for the bandit (d ≈ 7). */
internal object Lin {
    fun cholesky(a: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0..i) {
            var s = a[i][j]
            for (k in 0 until j) s -= l[i][k] * l[j][k]
            l[i][j] = if (i == j) sqrt(s.coerceAtLeast(1e-12)) else s / l[j][j]
        }
        return l
    }

    /** Solves A x = b with A = L Lᵀ. */
    fun solve(l: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val y = DoubleArray(n)
        for (i in 0 until n) { var s = b[i]; for (k in 0 until i) s -= l[i][k] * y[k]; y[i] = s / l[i][i] }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) { var s = y[i]; for (k in i + 1 until n) s -= l[k][i] * x[k]; x[i] = s / l[i][i] }
        return x
    }

    /** Solves Lᵀ x = z (for sampling from N(0, A⁻¹)). */
    fun solveUpper(l: Array<DoubleArray>, z: DoubleArray): DoubleArray {
        val n = z.size
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) { var s = z[i]; for (k in i + 1 until n) s -= l[k][i] * x[k]; x[i] = s / l[i][i] }
        return x
    }

    fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b.getOrElse(it) { 0.0 } }
}

/**
 * Contextual model choice: linear Thompson sampling per (route, role, model). The success of a model is modelled as a
 * linear function of the task context, so "cheap model for small Kotlin fixes, strong model for big TypeScript
 * changes" can be learned. Every choice records its probability (propensity), which makes off-policy evaluation
 * possible: a new choice rule can be judged on past data without running it.
 */
class LinearThompson(private val file: File, private val dim: Int = RouteContext.DIM, private val v: Double = 0.35,
                     private val random: java.util.Random = java.util.Random()) {
    class Arm(val a: Array<DoubleArray>, val b: DoubleArray, val n: Int) {
        val chol by lazy { Lin.cholesky(a) }
        val mean by lazy { Lin.solve(chol, b) }
    }

    data class Choice(val model: String, val propensity: Double)

    @Synchronized private fun load(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    @Synchronized fun arm(key: String): Arm {
        val o = load().optJSONObject(key) ?: return Arm(Array(dim) { i -> DoubleArray(dim) { j -> if (i == j) 1.0 else 0.0 } }, DoubleArray(dim), 0)
        val a = o.getJSONArray("A"); val b = o.getJSONArray("b")
        return Arm(Array(dim) { i -> DoubleArray(dim) { j -> a.getDouble(i * dim + j) } }, DoubleArray(dim) { b.getDouble(it) }, o.optInt("n"))
    }

    @Synchronized fun record(key: String, x: DoubleArray, reward: Double) {
        val cur = arm(key)
        for (i in 0 until dim) { for (j in 0 until dim) cur.a[i][j] += x[i] * x[j]; cur.b[i] += reward * x[i] }
        val root = load()
        root.put(key, JSONObject().put("A", JSONArray(cur.a.flatMap { it.toList() })).put("b", JSONArray(cur.b.toList())).put("n", cur.n + 1))
        file.parentFile?.mkdirs(); file.writeText(root.toString())
    }

    private fun sample(arm: Arm): DoubleArray {
        val z = DoubleArray(dim) { random.nextGaussian() }
        val d = Lin.solveUpper(arm.chol, z)
        return DoubleArray(dim) { arm.mean[it] + v * d[it] }
    }

    /**
     * Thompson choice with its propensity, estimated from [draws] posterior samples. [penalty] is the cost part of
     * the utility (success − penalty).
     */
    fun choose(prefix: String, candidates: List<String>, x: DoubleArray, penalty: (String) -> Double, draws: Int = 200): Choice {
        val arms = candidates.associateWith { arm("$prefix|$it") }
        val wins = HashMap<String, Int>()
        var first: String? = null
        repeat(draws) { k ->
            val best = candidates.maxBy { m -> Lin.dot(sample(arms.getValue(m)), x) - penalty(m) }
            if (k == 0) first = best
            wins.merge(best, 1, Int::plus)
        }
        val chosen = first!!
        return Choice(chosen, ((wins[chosen] ?: 1).toDouble() / draws).coerceAtLeast(1.0 / draws))
    }

    /** Expected success of [model] in context [x] (posterior mean, no exploration). */
    fun predict(prefix: String, model: String, x: DoubleArray) = Lin.dot(arm("$prefix|$model").mean, x)
}

/** Logged decisions and off-policy evaluation of other choice rules on them. */
object OffPolicy {
    data class Log(val route: String, val role: String, val model: String, val x: DoubleArray, val propensity: Double, val reward: Double, val at: Long)
    data class Row(val policy: String, val ips: Double, val snips: Double, val dr: Double, val lo: Double, val hi: Double, val matched: Int, val ess: Double)

    fun toJson(l: Log): JSONObject = JSONObject().put("route", l.route).put("role", l.role).put("m", l.model).put("x", JSONArray(l.x.toList()))
        .put("p", l.propensity).put("r", l.reward).put("at", l.at)

    fun fromJson(o: JSONObject) = Log(o.optString("route"), o.optString("role"), o.optString("m"),
        o.getJSONArray("x").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }, o.optDouble("p", 1.0), o.optDouble("r"), o.optLong("at"))

    class Store(private val file: File) {
        @Synchronized fun add(l: Log) {
            file.parentFile?.mkdirs(); file.appendText(toJson(l).toString() + "\n")
            if (file.length() > 600_000) file.writeText(all().takeLast(2_000).joinToString("") { toJson(it).toString() + "\n" })
        }
        @Synchronized fun all(): List<Log> = runCatching {
            file.readLines().mapNotNull { if (it.isBlank()) null else runCatching { fromJson(JSONObject(it)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    /**
     * Value of a deterministic [policy] on logged data: IPS, self-normalized IPS and doubly robust (with [rhat] as
     * the reward model), with a bootstrap 95% interval of the DR estimate and the effective sample size.
     */
    fun evaluate(name: String, logs: List<Log>, policy: (Log) -> String, rhat: (Log, String) -> Double, seed: Long = 11): Row {
        if (logs.isEmpty()) return Row(name, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0)
        val w = logs.map { l -> if (policy(l) == l.model) 1.0 / l.propensity.coerceAtLeast(1e-3) else 0.0 }
        val ips = logs.indices.sumOf { w[it] * logs[it].reward } / logs.size
        val sw = w.sum()
        val snips = if (sw > 0) logs.indices.sumOf { w[it] * logs[it].reward } / sw else 0.0
        val drTerms = logs.indices.map { i -> val l = logs[i]; rhat(l, policy(l)) + w[i] * (l.reward - rhat(l, l.model)) }
        val dr = drTerms.average()
        val (_, lo, hi) = Stats.bootstrapMean(drTerms, seed = seed)
        val ess = if (w.sumOf { it * it } > 0) sw * sw / w.sumOf { it * it } else 0.0
        return Row(name, ips, snips, dr, lo, hi, w.count { it > 0 }, ess)
    }

    fun format(r: Row) = "${r.policy}: ${String.format(Locale.US, "%.2f", r.dr)} (%95 ${String.format(Locale.US, "%.2f", r.lo)}–${String.format(Locale.US, "%.2f", r.hi)}), " +
        "${r.matched} eşleşen karar, etkin örneklem ${String.format(Locale.US, "%.1f", r.ess)}"
}
