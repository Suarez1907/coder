package com.suarez.coder.cost

import com.suarez.coder.llm.Usage
import java.util.Locale

/** Prices in USD per 1M tokens. */
data class Price(val input: Double, val output: Double, val cachedInput: Double, val storagePerHour: Double = 0.0)

object Pricing {
    /**
     * Standard paid-tier prices (prompts ≤200k) from the Gemini API pricing page, read on 2026-11-20.
     * Gemini 4 models support only implicit caching. DeepSeek values are estimates; override them in Settings.
     */
    private val TABLE: Map<String, Price> = linkedMapOf(
        "gemini-decision-1" to Price(0.10, 0.0, 0.10),
        // OpenRouter decision models: input only (usage.cost is used when the API reports it).
        "typesafe/jev" to Price(0.042, 0.0, 0.042),
        "gemini-4-neon" to Price(3.00, 15.00, 0.15),
        "gemini-4-hydrogen" to Price(1.50, 7.50, 0.075),
        "gemini-4-carbon" to Price(2.00, 10.00, 0.10),
        "gemini-4-argon" to Price(2.00, 10.00, 0.10),
        "gemini-3.8-flash" to Price(0.75, 3.75, 0.075, 0.50),
        "gemini-3.6-flash" to Price(0.75, 3.75, 0.075, 0.50),
        "gemini-3.5-flash-lite" to Price(0.30, 2.50, 0.03, 1.00),
        "gemini-3.1-pro-preview" to Price(2.00, 12.00, 0.20, 4.50),
        "gemini-3.1-flash-lite" to Price(0.25, 1.50, 0.025, 1.00),
        "gemini-3-flash-preview" to Price(0.50, 3.00, 0.05, 1.00),
        "gemini-2.5-pro" to Price(1.25, 10.00, 0.125, 4.50),
        "gemini-2.5-flash-lite" to Price(0.10, 0.40, 0.01, 1.00),
        "gemini-2.5-flash" to Price(0.30, 2.50, 0.03, 1.00),
    )

    /** A published future price (from the provider's announcement), applied from [effectiveMs]. */
    data class ScheduledPrice(val model: String, val effectiveMs: Long, val price: Price, val source: String)

    private fun utc(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Gemini 3.8 Flash's introductory price ends on 2027-01-01 (reported by several pricing trackers in November 2026). */
    val SCHEDULED: List<ScheduledPrice> = listOf(
        ScheduledPrice("gemini-3.8-flash", utc(2027, 1, 1), Price(1.50, 7.50, 0.15, 0.50), "fiyat takip siteleri, Kasım 2026"),
    )

    /** The next scheduled change for a model that has not taken effect yet. */
    fun upcoming(model: String, now: Long = System.currentTimeMillis()): ScheduledPrice? {
        val m = model.lowercase().substringAfterLast('/')
        return SCHEDULED.filter { m.startsWith(it.model) && it.effectiveMs > now }.minByOrNull { it.effectiveMs }
    }

    /** Models where only implicit caching exists (explicit cachedContents is rejected). */
    fun supportsExplicitCache(model: String): Boolean {
        val m = model.lowercase()
        return !(m.startsWith("gemini-4-") || m.contains("decision") || !m.startsWith("gemini"))
    }

    /** Models whose thinking can be switched (nearly) off, so a 10-token answer is not eaten by thinking. */
    fun canNearlyDisableThinking(model: String): Boolean {
        val m = model.lowercase()
        if (m.startsWith("deepseek")) return !m.contains("reason") && !m.contains("think")
        return m.startsWith("gemini-2.5-flash") || m.startsWith("gemini-3.6-flash") ||
            m.startsWith("gemini-3.5-flash-lite") || m.startsWith("gemini-3.1-flash-lite") || m.startsWith("gemini-3-flash")
    }

    /** True when the price comes from the published table (not a guess). */
    fun isKnown(model: String) = lookupTable(model) != null

    private fun lookupTable(model: String, now: Long = System.currentTimeMillis()): Price? {
        val m = model.lowercase().removePrefix("models/").removePrefix("~")
        SCHEDULED.filter { m.startsWith(it.model) && it.effectiveMs <= now }.maxByOrNull { it.effectiveMs }?.let { return it.price }
        TABLE[m]?.let { return it }
        // Versioned or preview ids, e.g. gemini-2.5-flash-preview-09-2025 → longest matching prefix.
        return TABLE.entries.filter { m.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
    }

    fun defaultFor(model: String): Price {
        lookupTable(model)?.let { return it }
        val m = model.lowercase()
        return when {
            m.contains("decision") -> Price(0.10, 0.0, 0.10)
            // DeepSeek V4 standard rates as reported in November 2026 (V4-Pro / V4-Flash); override in Settings if they differ.
            m.contains("deepseek") && (m.contains("reason") || m.contains("pro")) -> Price(0.435, 0.87, 0.0036)
            m.contains("deepseek") -> Price(0.14, 0.28, 0.014)
            m.contains("flash-lite") -> Price(0.30, 2.50, 0.03, 1.0)
            m.contains("flash") -> Price(0.75, 3.75, 0.075, 0.50)
            m.contains("pro") -> Price(2.00, 12.00, 0.20, 4.50)
            else -> Price(2.00, 10.00, 0.10)
        }
    }

    /** Lines like `gemini-2.5-pro = 1.25, 10, 0.125, 4.5` (input, output, cached input, storage/hour). */
    fun parseOverrides(text: String): Map<String, Price> {
        val out = LinkedHashMap<String, Price>()
        for (line in text.lines()) {
            val t = line.substringBefore('#').trim()
            if (!t.contains('=')) continue
            val key = t.substringBefore('=').trim().lowercase()
            val nums = t.substringAfter('=').split(',').mapNotNull { it.trim().replace(',', '.').toDoubleOrNull() }
            if (key.isEmpty() || nums.size < 2) continue
            out[key] = Price(nums[0], nums[1], nums.getOrElse(2) { nums[0] / 4 }, nums.getOrElse(3) { 0.0 })
        }
        return out
    }

    fun priceFor(model: String, overrides: Map<String, Price>): Price {
        val m = model.lowercase()
        overrides[m]?.let { return it }
        overrides.entries.filter { m.contains(it.key) }.maxByOrNull { it.key.length }?.let { return it.value }
        return defaultFor(model)
    }

    /** Price of a typical tiny call, used to pick the cheapest router model. */
    fun blended(p: Price) = p.input * 0.9 + p.output * 0.1

    fun cost(u: Usage, p: Price): Double {
        val fresh = (u.input - u.cached).coerceAtLeast(0)
        return (fresh * p.input + u.cached * p.cachedInput + (u.output + u.thoughts) * p.output) / 1_000_000.0
    }

    fun inputCost(tokens: Long, p: Price) = tokens * p.input / 1_000_000.0

    fun storageCost(tokens: Long, minutes: Double, p: Price) = tokens * p.storagePerHour / 1_000_000.0 * (minutes / 60.0)

    fun formatUsd(v: Double): String = when {
        v == 0.0 -> "$0"
        v < 0.01 -> String.format(Locale.US, "$%.5f", v)
        else -> String.format(Locale.US, "$%.3f", v)
    }

    fun formatTry(v: Double): String = String.format(Locale.US, "₺%.2f", v)

    data class StreamCut(
        val model: String,
        val outputTokens: Long,
        val savedOutputTokens: Long,
        val usd: Double,
        val savedUsd: Double,
        val at: Long,
    )

    private val streamCuts = ArrayList<StreamCut>()

    /** Tokens already generated when a stream is cut, plus the output the cut avoided. */
    @Synchronized
    fun recordStreamCut(model: String, outputTokens: Long, savedOutputTokens: Long, price: Price, at: Long = System.currentTimeMillis()): StreamCut {
        val usd = outputTokens.coerceAtLeast(0) * price.output / 1_000_000.0
        val savedUsd = savedOutputTokens.coerceAtLeast(0) * price.output / 1_000_000.0
        val row = StreamCut(model, outputTokens.coerceAtLeast(0), savedOutputTokens.coerceAtLeast(0), usd, savedUsd, at)
        streamCuts += row
        return row
    }

    @Synchronized fun streamCuts(): List<StreamCut> = streamCuts.toList()

    @Synchronized fun clearStreamCuts() { streamCuts.clear() }

    /** Output still inside the cap after a cut. A loop would have spent it. */
    fun savedOutputTokens(consumed: Long, maxOut: Int?): Long {
        val cap = (maxOut ?: 8_192).toLong()
        return (cap - consumed.coerceAtLeast(0)).coerceAtLeast(256)
    }

    fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.2fM", n / 1e6)
        n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1e3)
        else -> n.toString()
    }
}
