package com.suarez.coder.data

import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.MessageJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SessionMeta(
    val id: String,
    val title: String,
    val repo: String,
    val createdAt: Long,
    val updatedAt: Long,
    val costUsd: Double,
)

/**
 * Chats kept on the device: one append-only log per session (`<id>.jsonl`, one message per line) plus a small index.
 * A save that only adds messages appends them (cost independent of the chat's length); anything else (compaction,
 * a message inserted in the middle) rewrites the log atomically. Older `<id>.json` files are still read.
 */
class SessionStore(private val dir: File, private val legacyChat: File? = null) {
    private val indexFile = File(dir, "index.json")

    // Declared before init: migrateLegacy() saves.
    /** What each log holds right now (same message objects), to tell an append from a rewrite. */
    private val written = HashMap<String, List<ChatMessage>>()

    /** Bytes written by the last [save] (for tests and the performance view). */
    @Volatile var lastWriteBytes = 0L
        private set

    init {
        dir.mkdirs()
        migrateLegacy()
    }

    fun newId(): String = System.currentTimeMillis().toString(36) + "-" + UUID.randomUUID().toString().take(4)

    @Synchronized
    fun list(): List<SessionMeta> = readIndex().sortedByDescending { it.updatedAt }

    private fun log(id: String) = File(dir, "$id.jsonl")
    private fun legacy(id: String) = File(dir, "$id.json")

    @Synchronized
    fun load(id: String): List<ChatMessage> {
        val f = log(id)
        val msgs = if (f.exists()) readLog(f) else runCatching { MessageJson.fromJson(legacy(id).readText()) }.getOrDefault(emptyList())
        if (f.exists()) written[id] = msgs
        return msgs
    }

    /** One message per line; a torn last line (the app died mid-write) is skipped. */
    private fun readLog(f: File): List<ChatMessage> = f.readLines().mapNotNull { line ->
        if (line.isBlank()) null else runCatching { MessageJson.decode(JSONObject(line)) }.getOrNull()
    }

    /** Saves messages and refreshes the index entry. Empty chats are not listed. */
    @Synchronized
    fun save(id: String, messages: List<ChatMessage>, repo: String, costUsd: Double) {
        if (messages.none { it is ChatMessage.User }) return
        val f = log(id)
        val before = written[id]
        val appendOnly = before != null && f.exists() && before.size <= messages.size && before.indices.all { before[it] === messages[it] }
        lastWriteBytes = if (appendOnly) {
            val tail = messages.subList(before!!.size, messages.size)
            val text = tail.joinToString("") { MessageJson.encode(it).toString() + "\n" }
            if (text.isNotEmpty()) f.appendText(text)
            text.length.toLong()
        } else {
            val tmp = File(dir, "$id.jsonl.tmp")
            val text = messages.joinToString("") { MessageJson.encode(it).toString() + "\n" }
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            legacy(id).delete()
            text.length.toLong()
        }
        written[id] = messages.toList()
        val now = System.currentTimeMillis()
        val list = readIndex()
        val old = list.firstOrNull { it.id == id }
        val meta = SessionMeta(id, titleOf(messages), old?.repo?.ifBlank { repo } ?: repo, old?.createdAt ?: now, now, costUsd)
        writeIndex(list.filter { it.id != id } + meta)
    }

    /** Deletes the session and the screenshots only it referenced. */
    @Synchronized
    fun delete(id: String) {
        val images = load(id).filterIsInstance<ChatMessage.User>().flatMap { it.images }
        log(id).delete(); legacy(id).delete(); written.remove(id)
        writeIndex(readIndex().filter { it.id != id })
        images.forEach { runCatching { File(it).delete() } }
    }

    /** Sessions whose title, repo or text contains [query] (case-insensitive). */
    @Synchronized
    fun search(query: String): List<SessionMeta> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list()
        return list().filter { m ->
            m.title.lowercase().contains(q) || m.repo.lowercase().contains(q) ||
                runCatching { (log(m.id).takeIf { it.exists() } ?: legacy(m.id)).readText().lowercase().contains(q) }.getOrDefault(false)
        }
    }

    private fun titleOf(messages: List<ChatMessage>): String =
        (messages.firstOrNull { it is ChatMessage.User } as? ChatMessage.User)?.text?.lines()?.firstOrNull { it.isNotBlank() }
            ?.trim()?.take(70) ?: "Sohbet"

    private fun readIndex(): List<SessionMeta> = runCatching {
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SessionMeta(o.getString("id"), o.optString("title"), o.optString("repo"), o.optLong("created"), o.optLong("updated"), o.optDouble("cost", 0.0))
        }
    }.getOrDefault(emptyList())

    private fun writeIndex(list: List<SessionMeta>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("title", m.title).put("repo", m.repo)
                .put("created", m.createdAt).put("updated", m.updatedAt).put("cost", m.costUsd))
        }
        indexFile.writeText(arr.toString())
    }

    /** The single chat.json of earlier versions becomes the first session. */
    private fun migrateLegacy() {
        val f = legacyChat ?: return
        if (!f.exists()) return
        val msgs = runCatching { MessageJson.fromJson(f.readText()) }.getOrDefault(emptyList())
        if (msgs.any { it is ChatMessage.User }) save(newId(), msgs, "", 0.0)
        f.renameTo(File(f.parentFile, "chat.json.migrated"))
    }
}
