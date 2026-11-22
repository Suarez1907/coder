package com.suarez.coder.evolution

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** All evolution state on the device (no Room/DataStore: small JSON files like the rest of the app). */
class EvolutionStore(private val dir: File) {
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

    // ---- Meta ----

    @Synchronized private fun meta(): JSONObject = runCatching { JSONObject(metaFile.readText()) }.getOrDefault(JSONObject())

    var lastRunAt: Long
        get() = meta().optLong("lastRun", 0L)
        set(v) = synchronized(this) { write(metaFile, meta().put("lastRun", v).toString()) }

    fun counter(key: String) = meta().optInt(key, 0)

    @Synchronized fun bump(key: String) = write(metaFile, meta().let { it.put(key, it.optInt(key, 0) + 1) }.toString())

    companion object {
        const val MAX_TRACES = 50
        const val MAX_LOG = 200
    }
}
