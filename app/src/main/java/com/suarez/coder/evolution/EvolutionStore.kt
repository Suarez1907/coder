package com.suarez.coder.evolution

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** All evolution state on the device (no Room/DataStore: small JSON files like the rest of the app). */
class EvolutionStore(val dir: File) {
    init { dir.mkdirs() }

    private val tracesFile = File(dir, "traces.json")
    private val logFile = File(dir, "log.json")
    private val pendingFile = File(dir, "pending_pr.json")
    private val rejectedFile = File(dir, "rejected.json")
    private val lessonsFile = File(dir, "lessons.json")
    private val metaFile = File(dir, "meta.json")
    val routing = AdaptiveRoutingStore(File(dir, "routing.json"), File(dir, "stats.json"))

    private fun write(f: File, text: String) {
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    private fun readArray(f: File): JSONArray = runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())

    // ---- Traces ----

    @Synchronized fun traces(): List<SessionTrace> = readArray(tracesFile).let { a ->
        (0 until a.length()).mapNotNull { runCatching { SessionTrace.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }

    @Synchronized fun addTrace(t: SessionTrace) = saveTraces((traces() + t).takeLast(MAX_TRACES))

    @Synchronized fun updateTrace(id: String, f: (SessionTrace) -> SessionTrace) = saveTraces(traces().map { if (it.id == id) f(it) else it })

    private fun saveTraces(list: List<SessionTrace>) = write(tracesFile, JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString())

    fun unprocessed() = traces().filter { !it.processed }

    // ---- Log & spend ----

    @Synchronized fun log(): List<EvolutionLogEntry> = readArray(logFile).let { a ->
        (0 until a.length()).mapNotNull { runCatching { EvolutionLogEntry.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }

    @Synchronized fun addLog(tier: EvoTier, summary: String, costUsd: Double = 0.0, undo: String? = null): EvolutionLogEntry {
        val e = EvolutionLogEntry(UUID.randomUUID().toString().take(8), System.currentTimeMillis(), tier, summary, costUsd, undo)
        saveLog((log() + e).takeLast(MAX_LOG))
        return e
    }

    @Synchronized fun markUndone(id: String) = saveLog(log().map { if (it.id == id) it.copy(undone = true) else it })

    private fun saveLog(list: List<EvolutionLogEntry>) = write(logFile, JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString())

    fun spentSince(from: Long) = log().filter { it.at >= from }.sumOf { it.costUsd }
    fun spentToday(now: Long = System.currentTimeMillis()) = spentSince(EvolutionGuard.dayStart(now))
    fun totalSpent() = log().sumOf { it.costUsd }

    // ---- Self-PR ----

    @Synchronized fun pending(): PendingSelfPr? = runCatching { PendingSelfPr.fromJson(JSONObject(pendingFile.readText())) }.getOrNull()

    @Synchronized fun savePending(p: PendingSelfPr?) {
        if (p == null) pendingFile.delete() else write(pendingFile, p.toJson().toString())
    }

    @Synchronized fun rejectedTopics(): Set<String> = readArray(rejectedFile).let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }

    @Synchronized fun reject(topic: String) = write(rejectedFile, JSONArray((rejectedTopics() + EvolutionGuard.normalizeTopic(topic)).toList()).toString())

    // ---- Lessons (local project memory, per repo) ----

    @Synchronized fun lessons(repo: String): String = runCatching { JSONObject(lessonsFile.readText()).optString(repo.lowercase()) }.getOrDefault("")

    @Synchronized fun saveLessons(repo: String, text: String) {
        val o = runCatching { JSONObject(lessonsFile.readText()) }.getOrDefault(JSONObject())
        o.put(repo.lowercase(), text)
        write(lessonsFile, o.toString())
    }

    fun lessonCount() = runCatching { JSONObject(lessonsFile.readText()) }.getOrNull()?.let { o ->
        o.keys().asSequence().sumOf { k -> o.optString(k).lines().count { it.trimStart().startsWith("- ") } }
    } ?: 0

    // ---- Experiments, adopted rules, exam baselines ----

    private val experimentsFile = File(dir, "experiments.json")
    private val policyFile = File(dir, "policy.json")
    private val baselineFile = File(dir, "baselines.json")
    private val seenFile = File(dir, "scout_seen.json")

    @Synchronized fun experiments(): List<Experiment> = readArray(experimentsFile).let { a ->
        (0 until a.length()).mapNotNull { runCatching { Experiment.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }

    @Synchronized fun saveExperiment(e: Experiment) {
        val list = experiments().filter { it.id != e.id } + e
        write(experimentsFile, JSONArray().also { a -> list.takeLast(100).forEach { a.put(it.toJson()) } }.toString())
    }

    /** Queues an experiment unless the same change is already queued, running or decided. */
    @Synchronized fun queue(e: Experiment): Boolean {
        if (experiments().any { it.key == e.key }) return false
        saveExperiment(e)
        return true
    }

    private val promptFile get() = File(dir, "prompt_overrides.json")

    /** Prompt sections adopted after winning an exam (PromptSections id -> text). */
    @Synchronized fun promptOverrides(): Map<String, String> = runCatching {
        val o = JSONObject(promptFile.readText()); o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    @Synchronized fun setPromptOverride(section: String, text: String?) {
        val o = runCatching { JSONObject(promptFile.readText()) }.getOrDefault(JSONObject())
        if (text == null) o.remove(section) else o.put(section, text)
        write(promptFile, o.toString())
    }

    @Synchronized fun policyRules(): List<String> = readArray(policyFile).let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }

    @Synchronized fun setPolicyRules(rules: List<String>) =
        write(policyFile, JSONArray(rules.map { it.trim() }.filter { it.isNotBlank() }.distinct().takeLast(MAX_RULES)).toString())

    /** Exam result of a case under a configuration (reused while the configuration is unchanged). */
    @Synchronized fun baseline(config: String, case: String): ExamScore? =
        runCatching { JSONObject(baselineFile.readText()).optJSONObject("$config|$case") }.getOrNull()?.let { ExamScore.fromJson(it) }

    @Synchronized fun saveBaseline(config: String, case: String, s: ExamScore) {
        val o = runCatching { JSONObject(baselineFile.readText()) }.getOrDefault(JSONObject())
        o.put("$config|$case", s.toJson())
        write(baselineFile, o.toString())
    }

    @Synchronized fun seenRepos(): Set<String> = readArray(seenFile).let { a -> (0 until a.length()).map { a.optString(it).lowercase() }.toSet() }

    @Synchronized fun markSeen(repos: Collection<String>) = write(seenFile, JSONArray((seenRepos() + repos.map { it.lowercase() }).toList().takeLast(500)).toString())

    /** Spend of one tier since [from] (the weekly exam budget). */
    fun spentSince(from: Long, tier: EvoTier) = log().filter { it.at >= from && it.tier == tier }.sumOf { it.costUsd }

    // ---- Meta ----

    @Synchronized private fun meta(): JSONObject = runCatching { JSONObject(metaFile.readText()) }.getOrDefault(JSONObject())

    var lastRunAt: Long
        get() = meta().optLong("lastRun", 0L)
        set(v) = synchronized(this) { write(metaFile, meta().put("lastRun", v).toString()) }

    fun counter(key: String) = meta().optInt(key, 0)

    @Synchronized fun bump(key: String) = write(metaFile, meta().let { it.put(key, it.optInt(key, 0) + 1) }.toString())

    companion object {
        const val MAX_TRACES = 50
        const val MAX_LOG = 300
        const val MAX_RULES = 12
    }
}
