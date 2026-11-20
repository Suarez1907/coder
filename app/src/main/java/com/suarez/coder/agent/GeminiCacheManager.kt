package com.suarez.coder.agent

import com.suarez.coder.cost.Pricing
import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.GeminiClient
import com.suarez.coder.llm.ToolSpec
import com.suarez.coder.llm.Usage
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Decides between Gemini's free implicit caching and paid explicit caching, and manages one explicit cache.
 *
 * Implicit caching gives the same discount automatically when consecutive requests share their prefix, so explicit
 * caching only adds value (a guaranteed hit) at the price of hourly storage. Policy:
 * - explicit caching only for models that support it (Gemini 4 is implicit-only),
 * - only when the stable prefix (system + tools + stable context) is at least [Settings.cacheMinTokens],
 * - in automatic mode only after the measured implicit hit rate for that model stayed low.
 */
class GeminiCacheManager(private val settings: Settings) {
    private data class Entry(val key: String, val model: String, val name: String, var expireAt: Long, val tokens: Long, val prefixCount: Int)

    data class Prepared(val name: String, val tokens: Long, val createdOrExtended: Boolean, val ttlMinutes: Int, val prefixCount: Int)

    private class Stats { var calls = 0; var input = 0L; var cached = 0L }

    private var entry: Entry? = null
    private val skipped = HashSet<String>()
    private val prefixTokens = HashMap<String, Long>()
    private val unsupportedModels = HashSet<String>()
    private val implicitStats = HashMap<String, Stats>()
    var lastStatus: String = "Önbellek yok"
        private set

    companion object {
        const val MIN_CALLS_TO_JUDGE = 3
        const val LOW_HIT_RATE = 0.5
    }

    /** Records usage of calls that did not use an explicit cache, to measure implicit hit rates. */
    fun recordUsage(model: String, usage: Usage, explicitUsed: Boolean) {
        if (explicitUsed || usage.input <= 0) return
        val s = implicitStats.getOrPut(model) { Stats() }
        s.calls++; s.input += usage.input; s.cached += usage.cached
    }

    fun implicitHitRate(model: String): Double? =
        implicitStats[model]?.takeIf { it.calls > 0 && it.input > 0 }?.let { it.cached.toDouble() / it.input }

    private fun keyOf(model: String, system: String, tools: List<ToolSpec>, prefix: List<ChatMessage>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(model.toByteArray()); md.update(system.toByteArray())
        tools.forEach { md.update(it.name.toByteArray()); md.update(it.parameters.toString().toByteArray()) }
        prefix.forEach { m -> md.update((if (m is ChatMessage.User) "u:" + m.text else if (m is ChatMessage.Assistant) "a:" + m.text else "").toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(24)
    }

    fun describe(): String {
        val rates = implicitStats.entries.filter { it.value.calls > 0 }.joinToString(", ") { (m, s) ->
            "$m otomatik isabet %${(s.cached * 100.0 / s.input.coerceAtLeast(1)).roundToInt()}"
        }
        val e = entry
        val base = if (e == null) lastStatus else {
            val min = ((e.expireAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
            "Açık önbellek aktif: ${e.tokens} token, ~$min dk kaldı (${e.model})"
        }
        return if (rates.isBlank()) base else "$base • $rates"
    }

    suspend fun prepare(client: GeminiClient, system: String, tools: List<ToolSpec>, prefix: List<ChatMessage>): Prepared? {
        if (!settings.cacheEnabled) return null
        val model = client.model
        if (!Pricing.supportsExplicitCache(model) || model in unsupportedModels) {
            lastStatus = "$model yalnızca otomatik (implicit) önbellek destekliyor"
            return null
        }
        if (settings.explicitCacheAuto) {
            val s = implicitStats[model]
            val rate = implicitHitRate(model)
            if (s == null || s.calls < MIN_CALLS_TO_JUDGE || rate == null || rate >= LOW_HIT_RATE) {
                if (entry?.model != model) {
                    lastStatus = if (rate == null || (s?.calls ?: 0) < MIN_CALLS_TO_JUDGE) "Otomatik önbellek ölçülüyor"
                    else "Otomatik önbellek iyi çalışıyor (%${(rate * 100).roundToInt()}); açık önbelleğe gerek yok"
                    return null
                }
            }
        }
        val key = keyOf(model, system, tools, prefix)
        if (key in skipped) return null
        val ttlMin = settings.cacheTtlMinutes
        val ttlSec = ttlMin * 60L
        val now = System.currentTimeMillis()
        entry?.let { e ->
            if (e.key == key) {
                if (e.expireAt - now > 90_000) return Prepared(e.name, e.tokens, false, ttlMin, e.prefixCount)
                if (e.expireAt - now > 5_000) {
                    val ext = runCatching { client.extendCache(e.name, ttlSec) }.getOrNull()
                    if (ext != null) {
                        e.expireAt = ext.expireAtMillis
                        lastStatus = "Açık önbellek süresi uzatıldı"
                        return Prepared(e.name, e.tokens, true, ttlMin, e.prefixCount)
                    }
                }
            }
            client.deleteCache(e.name)
            entry = null
        }
        val tokens = prefixTokens[key] ?: runCatching {
            client.countTokens(system, prefix.ifEmpty { listOf(ChatMessage.User(".")) }, tools) ?: 0L
        }.getOrDefault(0L).also { prefixTokens[key] = it }
        if (tokens < settings.cacheMinTokens) {
            skipped += key
            lastStatus = "Sabit bağlam $tokens token < ${settings.cacheMinTokens} eşiği; açık önbellek oluşturulmadı"
            return null
        }
        return try {
            val info = client.createCache(system, tools, ttlSec, prefix)
            val e = Entry(key, model, info.name, info.expireAtMillis, info.tokens.takeIf { it > 0 } ?: tokens, prefix.size)
            entry = e
            lastStatus = "Açık önbellek oluşturuldu (${e.tokens} token)"
            Prepared(info.name, e.tokens, true, ttlMin, e.prefixCount)
        } catch (e: GeminiClient.CacheTooSmallException) {
            skipped += key
            lastStatus = "Bağlam önbellek için çok küçük; otomatik önbellek kullanılıyor"
            null
        } catch (e: Exception) {
            if (e.message?.contains("not supported", true) == true) unsupportedModels += model
            lastStatus = "Açık önbellek kullanılamadı: ${e.message?.take(120)}"
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
            return "Aktif açık önbellek yok."
        }
        GeminiClient(apiKey, e.model).deleteCache(e.name)
        lastStatus = "Önbellek temizlendi"
        return "Açık önbellek silindi (${e.tokens} token, ${e.model})."
    }
}
