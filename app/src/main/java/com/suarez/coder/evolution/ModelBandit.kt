package com.suarez.coder.evolution

import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** How good a finished run was, 0..1, from signals the app already has (no extra API call). */
object Outcome {
    fun of(t: SessionTrace): Double = when {
        t.rating > 0 -> 1.0
        t.rating < 0 -> 0.0
        t.stopped || t.userCorrection -> 0.0
        else -> (1.0 - 0.25 * minOf(t.diffErrorCount, 2) - 0.3 * minOf(t.ciFailureCount, 2) -
            0.15 * minOf(t.escalations.size, 2) - (if (t.upgradedToArchitect) 0.2 else 0.0)).coerceIn(0.0, 1.0)
    }
}

/**
 * Learned model choice per (route, role): Thompson sampling over the user's model pool with a cost penalty.
 * Most of the time it picks the model that has worked best for the money; now and then it tries another one, so a new
 * or cheaper model is discovered when it is good enough. Updated by every real run; no extra API calls.
 */
class ModelBandit(private val file: File, private val random: Random = Random.Default) {
    data class Arm(val a: Double = 1.0, val b: Double = 1.0, val n: Int = 0, val cost: Double = 0.0) {
        val mean get() = a / (a + b)
        val avgCost get() = if (n == 0) 0.0 else cost / n
    }

    companion object {
        /** Typical cost of one run per route: the scale for the cost penalty. */
        fun refCost(route: String) = when {
            route.startsWith("COMPLEX") -> 0.15
            route.startsWith("QUESTION") -> 0.01
            else -> 0.02
        }

        fun rolesFor(route: String) = if (route.startsWith("COMPLEX")) listOf("architect", "editor") else listOf("editor")

        /** Expected value minus the cost penalty; unknown cost counts as the typical cost. */
        fun utility(successProb: Double, arm: Arm, costWeight: Double, refCost: Double): Double {
            val c = if (arm.n == 0) refCost else arm.avgCost
            return successProb - costWeight * (c / (c + refCost))
        }
    }

    @Synchronized private fun load(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    @Synchronized fun arms(route: String, role: String): Map<String, Arm> {
        val o = load().optJSONObject("$route|$role") ?: return emptyMap()
        return o.keys().asSequence().associateWith { m ->
            val x = o.getJSONObject(m)
            Arm(x.optDouble("a", 1.0), x.optDouble("b", 1.0), x.optInt("n"), x.optDouble("c", 0.0))
        }
    }

    /** Picks one model from [candidates] (needs at least two to choose). */
    fun choose(route: String, role: String, candidates: List<String>, costWeight: Double): String? {
        if (candidates.size < 2) return candidates.firstOrNull()
        val arms = arms(route, role)
        val ref = refCost(route)
        return candidates.maxByOrNull { m ->
            val arm = arms[m] ?: Arm()
            utility(sampleBeta(arm.a, arm.b), arm, costWeight, ref)
        }
    }

    /** The model with the best expected utility (no exploration): what the report calls "the current best". */
    fun best(route: String, role: String, costWeight: Double): String? {
        val ref = refCost(route)
        return arms(route, role).filter { it.value.n > 0 }.maxByOrNull { utility(it.value.mean, it.value, costWeight, ref) }?.key
    }

    @Synchronized fun record(route: String, role: String, model: String, reward: Double, cost: Double) =
        update(route, role, model) { it.copy(a = it.a + reward, b = it.b + (1 - reward), n = it.n + 1, cost = it.cost + cost) }

    /** A later signal (thumbs, "olmadı") changes the reward of a run already counted. */
    @Synchronized fun adjust(route: String, role: String, model: String, oldReward: Double, newReward: Double) =
        update(route, role, model) { it.copy(a = (it.a + newReward - oldReward).coerceAtLeast(0.5), b = (it.b + oldReward - newReward).coerceAtLeast(0.5)) }

    private fun update(route: String, role: String, model: String, f: (Arm) -> Arm) {
        val root = load()
        val key = "$route|$role"
        val o = root.optJSONObject(key) ?: JSONObject()
        val cur = o.optJSONObject(model)?.let { Arm(it.optDouble("a", 1.0), it.optDouble("b", 1.0), it.optInt("n"), it.optDouble("c", 0.0)) } ?: Arm()
        val next = f(cur)
        o.put(model, JSONObject().put("a", next.a).put("b", next.b).put("n", next.n).put("c", next.cost))
        root.put(key, o)
        file.writeText(root.toString())
    }

    fun summary(costWeight: Double): String = load().keys().asSequence().sorted().joinToString("\n") { key ->
        val (route, role) = key.split('|').let { it[0] to it.getOrElse(1) { "" } }
        arms(route, role).entries.sortedByDescending { it.value.n }.joinToString("; ", prefix = "$route/$role: ") { (m, a) ->
            "${m.substringAfterLast('/')} %${(a.mean * 100).toInt()} başarı, ${a.n} iş, ort. $" + String.format(Locale.US, "%.3f", a.avgCost)
        }
    }

    // ---- Beta sampling (Marsaglia-Tsang gamma) ----

    fun sampleBeta(a: Double, b: Double): Double {
        val x = sampleGamma(a); val y = sampleGamma(b)
        return if (x + y == 0.0) 0.5 else x / (x + y)
    }

    private fun sampleGamma(k: Double): Double {
        if (k < 1.0) return sampleGamma(k + 1.0) * Math.pow(random.nextDouble().coerceAtLeast(1e-12), 1.0 / k)
        val d = k - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double; var v: Double
            do { x = gaussian(); v = 1.0 + c * x } while (v <= 0)
            v *= v * v
            val u = random.nextDouble()
            if (u < 1 - 0.0331 * x * x * x * x) return d * v
            if (ln(u.coerceAtLeast(1e-300)) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
        }
    }

    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-300); val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

}
