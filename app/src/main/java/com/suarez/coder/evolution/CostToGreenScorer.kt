package com.suarez.coder.evolution

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class TaskDomain { UI_COMPOSE, KOTLIN_CORE, CI_GRADLE_YAML, GAME_3D_SCRIPT, GENERAL }

/**
 * Sliding-window cost-to-green statistics for the implementer only. The architect model is never an input here.
 */
class CostToGreenScorer(private val file: File) {
    data class Sample(
        val success: Boolean,
        val firstPassCi: Boolean,
        val firstPassCostUsd: Double,
        val healCostUsd: Double,
        val preflightBlocks: Int,
        val oscillated: Boolean,
        val at: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("ok", success).put("ci1", firstPassCi).put("first", firstPassCostUsd).put("heal", healCostUsd)
            .put("pre", preflightBlocks).put("osc", oscillated).put("at", at)

        companion object {
            fun fromJson(o: JSONObject) = Sample(
                o.optBoolean("ok"), o.optBoolean("ci1"), o.optDouble("first", 0.0), o.optDouble("heal", 0.0),
                o.optInt("pre"), o.optBoolean("osc"), o.optLong("at"),
            )
        }
    }

    data class Score(
        val modelId: String,
        val domain: TaskDomain,
        val n: Int,
        val firstPassCiRate: Double,
        val overallSuccessRate: Double,
        val avgFirstPassCostUsd: Double,
        val avgHealAndEscalationCostUsd: Double,
        val avgPreflightBlocks: Double,
        val oscillationRate: Double,
        val costToGreenUsd: Double,
        val reliabilityPenalty: Double,
        val efficiencyScore: Double,
        val cooldownUntil: Long,
    ) {
        val coolingDown get() = cooldownUntil > 0
    }

    @Synchronized fun record(modelId: String, domain: TaskDomain, sample: Sample, baselineModelId: String, now: Long = System.currentTimeMillis()) {
        val id = modelId.trim()
        if (id.isBlank()) return
        val root = load()
        val key = key(id, domain)
        val row = root.optJSONObject(key) ?: JSONObject()
        val samples = (samplesOf(row.optJSONArray("samples")) + sample.copy(at = if (sample.at > 0) sample.at else now)).takeLast(WINDOW)
        val arr = JSONArray()
        samples.forEach { arr.put(it.toJson()) }
        row.put("samples", arr)
        val score = compute(id, domain, samples, row.optLong("cooldown"))
        val base = if (!id.equals(baselineModelId.trim(), true)) scoreOf(root, baselineModelId.trim(), domain) else null
        val cooldown = when {
            id.equals(baselineModelId.trim(), true) -> row.optLong("cooldown")
            samples.size >= 3 && score.overallSuccessRate < MIN_SUCCESS -> now + COOLDOWN_MS
            samples.size >= 3 && base != null && base.n > 0 && score.costToGreenUsd >= base.costToGreenUsd * CTG_MULTIPLE -> now + COOLDOWN_MS
            else -> row.optLong("cooldown")
        }
        row.put("cooldown", cooldown)
        root.put(key, row)
        save(root)
    }

    @Synchronized fun score(modelId: String, domain: TaskDomain, now: Long = System.currentTimeMillis()): Score {
        val root = load()
        val raw = scoreOf(root, modelId.trim(), domain)
        val until = raw.cooldownUntil
        return if (until in 1..now) raw.copy(cooldownUntil = 0) else raw
    }

    @Synchronized fun scores(domain: TaskDomain?, now: Long = System.currentTimeMillis()): List<Score> {
        val root = load()
        return root.keys().asSequence().mapNotNull { k ->
            val id = k.substringBefore('|')
            val d = runCatching { TaskDomain.valueOf(k.substringAfter('|')) }.getOrNull() ?: return@mapNotNull null
            if (domain != null && d != domain) return@mapNotNull null
            val s = scoreOf(root, id, d)
            if (s.cooldownUntil in 1..now) s.copy(cooldownUntil = 0) else s
        }.sortedByDescending { selectionScore(it) }.toList()
    }

    @Synchronized fun reset() {
        if (file.isFile) file.delete()
    }

    /** Slight exploration bonus for a model that has been tried fewer than 3 times. */
    fun selectionScore(s: Score): Double {
        if (s.n == 0) return 0.15
        val bonus = if (s.n < 3) EXPLORE_BONUS else 1.0
        return s.efficiencyScore * bonus
    }

    private fun scoreOf(root: JSONObject, modelId: String, domain: TaskDomain): Score {
        val row = root.optJSONObject(key(modelId, domain)) ?: JSONObject()
        return compute(modelId, domain, samplesOf(row.optJSONArray("samples")), row.optLong("cooldown"))
    }

    private fun samplesOf(arr: JSONArray?) = if (arr == null) emptyList() else (0 until arr.length()).map { Sample.fromJson(arr.getJSONObject(it)) }

    private fun compute(modelId: String, domain: TaskDomain, samples: List<Sample>, cooldownUntil: Long): Score {
        val n = samples.size
        if (n == 0) return Score(modelId, domain, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, cooldownUntil)
        val firstPassCiRate = samples.count { it.firstPassCi }.toDouble() / n
        val overallSuccessRate = samples.count { it.success }.toDouble() / n
        val avgFirst = samples.sumOf { it.firstPassCostUsd } / n
        val avgHeal = samples.sumOf { it.healCostUsd } / n
        val avgPre = samples.sumOf { it.preflightBlocks }.toDouble() / n
        val oscillationRate = samples.count { it.oscillated }.toDouble() / n
        val costToGreen = avgFirst + (1.0 - firstPassCiRate) * avgHeal
        val reliability = 1.0 - (oscillationRate * 0.35 + kotlin.math.min(avgPre * 0.05, 0.25))
        val efficiency = (overallSuccessRate * kotlin.math.max(reliability, 0.2)) / kotlin.math.max(costToGreen, 0.0005)
        return Score(modelId, domain, n, firstPassCiRate, overallSuccessRate, avgFirst, avgHeal, avgPre, oscillationRate,
            costToGreen, reliability, efficiency, cooldownUntil)
    }

    private fun load(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private fun save(o: JSONObject) {
        file.parentFile?.mkdirs()
        file.writeText(o.toString())
    }

    private fun key(modelId: String, domain: TaskDomain) = "$modelId|${domain.name}"

    companion object {
        const val WINDOW = 20
        const val COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000
        const val CTG_MULTIPLE = 2.5
        const val MIN_SUCCESS = 0.40
        const val EXPLORE_BONUS = 1.12

        fun classify(paths: List<String>, intent: String): TaskDomain {
            val named = Regex("[\\w./-]+\\.[A-Za-z0-9]+").findAll(intent).map { it.value }.toList()
            val all = (paths + named).map { it.lowercase().substringBefore('?') }
            val blob = (intent + " " + all.joinToString(" ")).lowercase()
            val game = all.any { it.endsWith(".gd") } || (all.any { it.endsWith(".py") } && (blob.contains("blender") || all.any { "blender" in it }))
            if (game) return TaskDomain.GAME_3D_SCRIPT
            if (all.any { it.endsWith(".gradle.kts") || it.endsWith(".gradle") || it.endsWith(".yml") || it.endsWith(".yaml") }) return TaskDomain.CI_GRADLE_YAML
            if (all.any { it.endsWith(".kt") || it.endsWith(".kts") } && (all.any { "/ui/" in it || "compose" in it } || blob.contains("compose") || blob.contains("ekran"))) {
                return TaskDomain.UI_COMPOSE
            }
            if (all.any { it.endsWith(".kt") || it.endsWith(".kts") }) return TaskDomain.KOTLIN_CORE
            return TaskDomain.GENERAL
        }

        /** One finished run, seen from the implementer arm only. */
        fun sampleFrom(t: SessionTrace): Sample {
            val firstPass = t.ciFailureCount == 0 && t.escalations.isEmpty() && t.diffErrorCount == 0 && !t.stopped
            val heal = if (firstPass) 0.0 else t.totalCostUsd * 0.6
            val first = (t.totalCostUsd - heal).coerceAtLeast(0.0)
            return Sample(
                success = t.success,
                firstPassCi = t.ciRuns == 0 && firstPass || t.ciRuns > 0 && t.ciFailureCount == 0,
                firstPassCostUsd = first,
                healCostUsd = heal,
                preflightBlocks = t.preflightBlocksCount,
                oscillated = t.oscillationEscalationsCount > 0 || t.escalations.size >= 2,
                at = t.at,
            )
        }
    }
}
