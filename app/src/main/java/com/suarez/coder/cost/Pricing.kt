package com.suarez.coder.cost

import com.suarez.coder.llm.Usage
import java.util.Locale

/** Prices in USD per 1M tokens. */
data class Price(val input: Double, val output: Double, val cachedInput: Double, val storagePerHour: Double = 0.0)

object Pricing {
    /** Approximate list prices; the user can override them in Settings. */
    fun defaultFor(model: String): Price {
        val m = model.lowercase()
        return when {
            m.contains("decision") -> Price(0.10, 0.0, 0.10) // Gemini Decision 1: no output tokens billed
            m.contains("deepseek") -> Price(0.28, 0.42, 0.028)
            m.contains("flash-lite") -> Price(0.10, 0.40, 0.01, 1.0)
            m.contains("flash") -> Price(0.30, 2.50, 0.03, 1.0)
            m.contains("pro") && Regex("gemini-[3-9]").containsMatchIn(m) -> Price(2.00, 12.00, 0.20, 4.50)
            m.contains("pro") -> Price(1.25, 10.00, 0.125, 4.50)
            else -> Price(1.25, 10.00, 0.125, 4.50)
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

    fun cost(u: Usage, p: Price): Double {
        val fresh = (u.input - u.cached).coerceAtLeast(0)
        return (fresh * p.input + u.cached * p.cachedInput + (u.output + u.thoughts) * p.output) / 1_000_000.0
    }

    fun inputCost(tokens: Long, p: Price) = tokens * p.input / 1_000_000.0

    fun storageCost(tokens: Long, minutes: Double, p: Price) = tokens * p.storagePerHour / 1_000_000.0 * (minutes / 60.0)

    fun formatUsd(v: Double): String = when {
        v == 0.0 -> "$0"
        v < 0.01 -> String.format(Locale.US, "$%.4f", v)
        else -> String.format(Locale.US, "$%.3f", v)
    }

    fun formatTry(v: Double): String = String.format(Locale.US, "₺%.2f", v)

    fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.2fM", n / 1e6)
        n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1e3)
        else -> n.toString()
    }
}
