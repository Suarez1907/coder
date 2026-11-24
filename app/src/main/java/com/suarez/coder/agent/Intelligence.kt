package com.suarez.coder.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.ln

/**
 * Risk of a change failing CI, learned on the phone from the user's own commits (logistic regression, no LLM).
 * The same features are known before the edit (planned files) and after it (changed files).
 */
object RiskFeatures {
    private val BUILD = Regex("(^|/)(build\\.gradle(\\.kts)?|settings\\.gradle(\\.kts)?|libs\\.versions\\.toml|gradle-wrapper\\.properties|package\\.json|pyproject\\.toml|requirements[^/]*\\.txt|AndroidManifest\\.xml|project\\.godot|tsconfig\\.json|vite\\.config\\.[jt]s)$")
    val NAMES = listOf("sabit", "dosya sayısı", "yeni dosya", "derleme dosyası", "Kotlin/Java", "JS/TS", "Python", "kaynak/XML", "zorluk", "repo başarısızlık oranı")

    fun of(paths: List<String>, newFiles: Int, complexity: Double?, repoFailRate: Double?): DoubleArray {
        fun share(re: Regex) = if (paths.isEmpty()) 0.0 else paths.count { re.containsMatchIn(it) }.toDouble() / paths.size
        return doubleArrayOf(
            1.0,
            ln(1.0 + paths.size) / ln(10.0),
            if (newFiles > 0) 1.0 else 0.0,
            if (paths.any { BUILD.containsMatchIn(it) }) 1.0 else 0.0,
            share(Regex("\\.(kt|kts|java)$")),
            share(Regex("\\.(js|jsx|ts|tsx|mjs|vue|svelte)$")),
            share(Regex("\\.py$")),
            share(Regex("\\.(xml|json|ya?ml|gradle)$")),
            (complexity ?: 1.5) / 3.0,
            repoFailRate ?: 0.3,
        )
    }
}

/** One committed change and (later) whether its CI failed. */
data class ChangeRecord(val id: String, val repo: String, val sha: String, val at: Long, val x: DoubleArray, val failed: Boolean? = null) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("repo", repo).put("sha", sha).put("at", at).put("x", JSONArray(x.toList()))
        .put("y", failed ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = ChangeRecord(o.getString("id"), o.getString("repo"), o.getString("sha"), o.getLong("at"),
            o.getJSONArray("x").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }, if (o.isNull("y")) null else o.getBoolean("y"))
    }
}

class ChangeLog(private val file: File) {
    @Synchronized fun all(): List<ChangeRecord> = runCatching {
        file.readLines().mapNotNull { l -> if (l.isBlank()) null else runCatching { ChangeRecord.fromJson(JSONObject(l)) }.getOrNull() }
    }.getOrDefault(emptyList())

    @Synchronized fun add(r: ChangeRecord) {
        file.parentFile?.mkdirs()
        file.appendText(r.toJson().toString() + "\n")
        if (file.length() > 400_000) save(all().takeLast(MAX))
    }

    @Synchronized fun save(list: List<ChangeRecord>) {
        file.parentFile?.mkdirs()
        file.writeText(list.takeLast(MAX).joinToString("") { it.toJson().toString() + "\n" })
    }

    /** Failure rate of a repo's labelled changes (smoothed toward 0.3), or null without history. */
    fun repoFailRate(repo: String): Double? {
        val l = all().filter { it.repo.equals(repo, true) && it.failed != null }
        if (l.isEmpty()) return null
        return (l.count { it.failed == true } + 0.3 * 3) / (l.size + 3)
    }

    companion object { const val MAX = 1_000 }
}

/** Logistic regression with L2, trained by gradient descent; tiny, deterministic, explainable. */
class RiskModel private constructor(val weights: DoubleArray, val samples: Int, val failRate: Double, val accuracy: Double, val auc: Double) {
    fun pFail(x: DoubleArray): Double = sigmoid(weights.indices.sumOf { weights[it] * x.getOrElse(it) { 0.0 } })

    companion object {
        const val MIN_SAMPLES = 25
        const val MIN_PER_CLASS = 4

        fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))

        /** Null until there is enough labelled data (both outcomes seen). */
        fun train(data: List<ChangeRecord>, iters: Int = 800, lr: Double = 0.3, l2: Double = 0.01): RiskModel? {
            val l = data.filter { it.failed != null }
            val pos = l.count { it.failed == true }
            if (l.size < MIN_SAMPLES || pos < MIN_PER_CLASS || l.size - pos < MIN_PER_CLASS) return null
            val d = l.first().x.size
            val w = DoubleArray(d)
            repeat(iters) {
                val g = DoubleArray(d)
                for (r in l) {
                    val err = sigmoid(w.indices.sumOf { w[it] * r.x[it] }) - (if (r.failed == true) 1.0 else 0.0)
                    for (j in 0 until d) g[j] += err * r.x[j]
                }
                for (j in 0 until d) w[j] -= lr * (g[j] / l.size + (if (j == 0) 0.0 else l2 * w[j]))
            }
            val scores = l.map { r -> sigmoid(w.indices.sumOf { w[it] * r.x[it] }) to (r.failed == true) }
            val acc = scores.count { (p, y) -> (p >= 0.5) == y }.toDouble() / l.size
            return RiskModel(w, l.size, pos.toDouble() / l.size, acc, auc(scores))
        }

        /** Area under the ROC curve (probability a failing change scores above a passing one). */
        fun auc(scores: List<Pair<Double, Boolean>>): Double {
            val p = scores.filter { it.second }.map { it.first }; val n = scores.filter { !it.second }.map { it.first }
            if (p.isEmpty() || n.isEmpty()) return 0.5
            var s = 0.0
            for (a in p) for (b in n) s += if (a > b) 1.0 else if (a == b) 0.5 else 0.0
            return s / (p.size * n.size)
        }

        /** Before there is data: a cautious prior from the size and kind of change. */
        fun prior(x: DoubleArray): Double = (0.12 + 0.12 * x[1] + 0.12 * x[2] + 0.2 * x[3] + 0.1 * x[8]).coerceIn(0.05, 0.8)
    }
}

/** Several edits of the same plan: pick the one that passes the checks, prefer what most candidates agree on. */
object Candidates {
    data class Scored(val index: Int, val passes: Boolean, val reviewOk: Boolean?, val votes: Int, val size: Int)

    /** Whitespace-insensitive identity of a candidate's resulting files. */
    fun fingerprint(files: Map<String, String?>): String =
        files.entries.sortedBy { it.key }.joinToString("\u0000") { (k, v) -> k + "=" + (v ?: "<deleted>").replace(Regex("\\s+"), "") }

    fun rank(c: List<Scored>): List<Scored> = c.sortedWith(
        compareByDescending<Scored> { it.passes }.thenByDescending { it.reviewOk ?: true }.thenByDescending { it.votes }.thenBy { it.size }.thenBy { it.index },
    )

    /** Diversity: each candidate samples at a different temperature. */
    fun temperature(i: Int) = listOf(0.2, 0.7, 1.0, 0.45, 0.85)[i % 5]
}

/** How sure the plan is: independent cheap guesses of the files to change, compared with the architect's list. */
object PlanUncertainty {
    const val SYSTEM = "You see a coding request and a repository map. List the repository files that must be created or " +
        "changed to do it (existing paths from the map, or new paths). JSON only: {\"files\":[\"path\", ...]} with at most 8 paths."

    fun parse(reply: String): Set<String>? {
        val s = reply.substringAfter('{', "").substringBeforeLast('}', "")
        if (s.isEmpty()) return null
        val a = runCatching { JSONObject("{$s}").getJSONArray("files") }.getOrNull() ?: return null
        return (0 until a.length()).map { a.optString(it).trim().trimStart('/') }.filter { it.isNotBlank() }.toSet()
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double = if (a.isEmpty() && b.isEmpty()) 1.0 else a.intersect(b).size.toDouble() / a.union(b).size

    /** Mean pairwise agreement of the architect's files and the guesses (1 = all agree). */
    fun agreement(sets: List<Set<String>>): Double {
        if (sets.size < 2) return 1.0
        val pairs = sets.indices.flatMap { i -> (i + 1 until sets.size).map { j -> jaccard(sets[i], sets[j]) } }
        return pairs.average()
    }

    /** Files most guesses name but the plan does not (worth a look before editing). */
    fun missing(plan: Set<String>, guesses: List<Set<String>>): List<String> =
        guesses.flatten().groupingBy { it }.eachCount().filter { (p, n) -> p !in plan && n >= (guesses.size + 1) / 2 }.keys.sorted()
}
