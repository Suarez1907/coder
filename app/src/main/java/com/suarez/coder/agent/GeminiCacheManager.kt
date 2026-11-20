package com.suarez.coder.agent

import com.suarez.coder.cost.Pricing
import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.GeminiClient
import com.suarez.coder.llm.ToolSpec
import java.security.MessageDigest

/**
 * Keeps one explicit Gemini context cache for the stable prefix (system prompt + tool schemas + pinned files + repo map).
 * - Only for models that support explicit caching (Gemini 4 models are implicit-only).
 * - Only when the prefix is at least [Settings.cacheMinTokens] (counted once per prefix with the free countTokens endpoint),
 *   so small projects do not pay cache creation and storage.
 * - Extends the TTL shortly before expiry and replaces the cache when the prefix changes.
 */
class GeminiCacheManager(private val settings: Settings) {
    private data class Entry(val key: String, val model: String, val name: String, var expireAt: Long, val tokens: Long)

    data class Prepared(val name: String, val tokens: Long, val createdOrExtended: Boolean, val ttlMinutes: Int)

    private var entry: Entry? = null
    private val skipped = HashSet<String>()
    private val prefixTokens = HashMap<String, Long>()
    private val unsupportedModels = HashSet<String>()
    var lastStatus: String = "Önbellek yok"
        private set

    private fun keyOf(model: String, system: String, tools: List<ToolSpec>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(model.toByteArray()); md.update(system.toByteArray())
        tools.forEach { md.update(it.name.toByteArray()); md.update(it.parameters.toString().toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(24)
    }

    fun describe(): String {
        val e = entry ?: return lastStatus
        val min = ((e.expireAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        return "Önbellek aktif: ${e.tokens} token, ~$min dk kaldı (${e.model})"
    }

    suspend fun prepare(client: GeminiClient, system: String, tools: List<ToolSpec>): Prepared? {
        if (!settings.cacheEnabled) return null
        val model = client.model
        if (!Pricing.supportsExplicitCache(model) || model in unsupportedModels) {
            lastStatus = "$model yalnızca otomatik (implicit) önbellek destekliyor"
            return null
        }
        val key = keyOf(model, system, tools)
        if (key in skipped) return null
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
            client.deleteCache(e.name)
            entry = null
        }
        // Count the stable prefix once (free endpoint) and skip caching below the threshold.
        val tokens = prefixTokens[key] ?: runCatching {
            client.countTokens(system, listOf(ChatMessage.User(".")), tools) ?: 0L
        }.getOrDefault(0L).also { prefixTokens[key] = it }
        if (tokens < settings.cacheMinTokens) {
            skipped += key
            lastStatus = "Sabit bağlam $tokens token < ${settings.cacheMinTokens} eşiği; önbellek oluşturulmadı"
            return null
        }
        return try {
            val info = client.createCache(system, tools, ttlSec)
            entry = Entry(key, model, info.name, info.expireAtMillis, info.tokens.takeIf { it > 0 } ?: tokens)
            lastStatus = "Önbellek oluşturuldu (${entry!!.tokens} token)"
            Prepared(info.name, entry!!.tokens, true, ttlMin)
        } catch (e: GeminiClient.CacheTooSmallException) {
            skipped += key
            lastStatus = "Bağlam önbellek için çok küçük; otomatik önbellek kullanılıyor"
            null
        } catch (e: Exception) {
            if (e.message?.contains("not supported", true) == true) unsupportedModels += model
            lastStatus = "Önbellek kullanılamadı: ${e.message?.take(120)}"
            null
        }
    }

    fun invalidate() { entry = null }

    /** Deletes the active cache on the server so it stops accruing storage cost. */
    suspend fun clear(apiKey: String): String {
        val e = entry
        entry = null
        skipped.clear()
        prefixTokens.clear()
        if (e == null) {
            lastStatus = "Önbellek yok"
            return "Aktif önbellek yok."
        }
        GeminiClient(apiKey, e.model).deleteCache(e.name)
        lastStatus = "Önbellek temizlendi"
        return "Önbellek silindi (${e.tokens} token, ${e.model})."
    }
}
