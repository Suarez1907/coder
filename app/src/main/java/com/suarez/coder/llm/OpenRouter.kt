package com.suarez.coder.llm

import com.suarez.coder.cost.Price
import org.json.JSONObject

/** One entry of OpenRouter's model list: price per 1M tokens and capabilities. */
data class OpenRouterModel(
    val id: String,
    val price: Price,
    val vision: Boolean,
    val tools: Boolean,
    /** Accepts reasoning.effort; unknown models get no reasoning field (avoids 400 errors). */
    val reasoning: Boolean = false,
    /** Accepts response_format (JSON mode). */
    val json: Boolean = false,
)

/** Parses OpenRouter's GET /models and stores the bits the app needs. */
object OpenRouterCatalog {
    const val BASE_URL = "https://openrouter.ai/api/v1"

    /** OpenRouter quotes USD per token as strings ("0.000003"); converts to USD per 1M tokens. */
    private fun perMillion(o: JSONObject?, key: String): Double? =
        o?.optString(key)?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { it * 1_000_000.0 }

    fun parse(body: String): List<OpenRouterModel> {
        val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val pr = o.optJSONObject("pricing")
            val input = perMillion(pr, "prompt") ?: 0.0
            val output = perMillion(pr, "completion") ?: 0.0
            val cached = perMillion(pr, "input_cache_read") ?: input
            val modalities = o.optJSONObject("architecture")?.optJSONArray("input_modalities")
            val vision = modalities != null && (0 until modalities.length()).any { modalities.optString(it) == "image" }
            val params = o.optJSONArray("supported_parameters")
            val paramSet = params?.let { p -> (0 until p.length()).map { p.optString(it) }.toSet() }
            val tools = paramSet == null || "tools" in paramSet
            val reasoning = paramSet != null && ("reasoning" in paramSet || "include_reasoning" in paramSet)
            val json = paramSet != null && ("response_format" in paramSet || "structured_outputs" in paramSet)
            OpenRouterModel(id, Price(input, output, cached), vision, tools, reasoning, json)
        }
    }

    fun toMeta(models: List<OpenRouterModel>): String = JSONObject().also { root ->
        models.forEach { m ->
            root.put(m.id, JSONObject().put("i", m.price.input).put("o", m.price.output).put("c", m.price.cachedInput).put("v", m.vision).put("r", m.reasoning).put("j", m.json))
        }
    }.toString()

    fun fromMeta(json: String): Map<String, OpenRouterModel> = runCatching {
        val root = JSONObject(json)
        root.keys().asSequence().associateWith { id ->
            val o = root.getJSONObject(id)
            OpenRouterModel(id, Price(o.optDouble("i", 0.0), o.optDouble("o", 0.0), o.optDouble("c", 0.0)), o.optBoolean("v"), true, o.optBoolean("r"), o.optBoolean("j"))
        }
    }.getOrDefault(emptyMap())

    /** Maps the app's thinking levels to OpenRouter's reasoning.effort (minimal → low). */
    fun effortFor(level: String?, minimal: Boolean): String? = when {
        minimal -> "low"
        level.isNullOrBlank() -> null
        level == "minimal" -> "low"
        level in setOf("low", "medium", "high") -> level
        else -> null
    }

    /** Anthropic models on OpenRouter cache only with explicit breakpoints; others cache automatically. */
    fun needsCacheControl(model: String) = model.startsWith("anthropic/")
}
