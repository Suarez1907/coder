package com.suarez.coder.agent

import com.suarez.coder.data.Settings
import com.suarez.coder.llm.GeminiClient
import com.suarez.coder.llm.ToolSpec
import java.security.MessageDigest

/**
 * Keeps one explicit Gemini context cache for the stable prefix (system prompt + tool schemas + pinned files).
 * Creates it on demand, extends its TTL shortly before expiry, replaces it when the prefix changes
 * and stops trying for prefixes that are below the model's minimum cache size.
 */
class GeminiCacheManager(private val settings: Settings) {
    private data class Entry(val key: String, val model: String, val name: String, var expireAt: Long, val tokens: Long)

    data class Prepared(val name: String, val tokens: Long, val createdOrExtended: Boolean, val ttlMinutes: Int)

    private var entry: Entry? = null
    private val tooSmall = HashSet<String>()
    var lastStatus: String = ""
        private set

    private fun keyOf(model: String, system: String, tools: List<ToolSpec>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(model.toByteArray()); md.update(system.toByteArray())
        tools.forEach { md.update(it.name.toByteArray()); md.update(it.parameters.toString().toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(24)
    }

    suspend fun prepare(client: GeminiClient, system: String, tools: List<ToolSpec>): Prepared? {
        if (!settings.cacheEnabled) return null
        val key = keyOf(client.model, system, tools)
        if (key in tooSmall) return null
        val ttlMin = settings.cacheTtlMinutes
        val ttlSec = ttlMin * 60L
        val now = System.currentTimeMillis()
        entry?.let { e ->
            if (e.key == key) {
                if (e.expireAt - now > 90_000) return Prepared(e.name, e.tokens, false, ttlMin)
                if (e.expireAt - now > 5_000) {
                    val ext = runCatching { client.extendCache(e.name, ttlSec) }.getOrNull()
                    if (ext != null) {
                        e.expireAt = ext.expireAtMillis
                        lastStatus = "Önbellek süresi uzatıldı"
                        return Prepared(e.name, e.tokens, true, ttlMin)
                    }
                }
            }
            // Prefix changed or the cache is about to expire: drop the old one.
            client.deleteCache(e.name)
            entry = null
        }
        return try {
            val info = client.createCache(system, tools, ttlSec)
            entry = Entry(key, client.model, info.name, info.expireAtMillis, info.tokens)
            lastStatus = "Önbellek oluşturuldu (${info.tokens} token)"
            Prepared(info.name, info.tokens, true, ttlMin)
        } catch (e: GeminiClient.CacheTooSmallException) {
            tooSmall += key
            lastStatus = "Bağlam önbellek için çok küçük; otomatik (implicit) önbellek kullanılıyor"
            null
        } catch (e: Exception) {
            lastStatus = "Önbellek kullanılamadı: ${e.message}"
            null
        }
    }

    fun invalidate() { entry = null }

    suspend fun clear(client: GeminiClient?) {
        val e = entry ?: return
        entry = null
        client?.deleteCache(e.name)
    }
}
