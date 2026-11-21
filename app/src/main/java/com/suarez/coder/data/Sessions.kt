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

/** Chats kept on the device: one JSON file per session plus a small index for the list. */
class SessionStore(private val dir: File, private val legacyChat: File? = null) {
    private val indexFile = File(dir, "index.json")

    init {
        dir.mkdirs()
        migrateLegacy()
    }

    fun newId(): String = System.currentTimeMillis().toString(36) + "-" + UUID.randomUUID().toString().take(4)

    @Synchronized
    fun list(): List<SessionMeta> = readIndex().sortedByDescending { it.updatedAt }

    @Synchronized
    fun load(id: String): List<ChatMessage> =
        runCatching { MessageJson.fromJson(File(dir, "$id.json").readText()) }.getOrDefault(emptyList())

    /** Saves messages and refreshes the index entry. Empty chats are not listed. */
    @Synchronized
    fun save(id: String, messages: List<ChatMessage>, repo: String, costUsd: Double) {
        if (messages.none { it is ChatMessage.User }) return
        File(dir, "$id.json").writeText(MessageJson.toJson(messages))
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
        File(dir, "$id.json").delete()
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
                runCatching { File(dir, "${m.id}.json").readText().lowercase().contains(q) }.getOrDefault(false)
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
