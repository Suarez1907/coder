package com.suarez.coder.evolution

import com.suarez.coder.data.Provider

/** OpenRouter or the Gemini API directly. Never an alias for the architect selection. */
enum class EvolutionProvider { OPENROUTER, GEMINI }

data class EvolutionChoice(
    val provider: EvolutionProvider,
    val modelId: String,
    /** Echo of the architect id that was passed in. This value is not a place to write it back. */
    val architectModelId: String,
)

data class EvolutionCompletion(
    val text: String,
    val costUsd: Double,
    val modelId: String,
    val provider: EvolutionProvider,
)

/** One evolution-model call. Tests supply a fake; the app uses the existing Gemini or OpenRouter client. */
fun interface EvolutionCompleter {
    suspend fun complete(system: String, user: String, maxUsd: Double): EvolutionCompletion?
}

/**
 * Picks the evolution model. [select] returns the architect id unchanged and never reads a settings object,
 * so a provider switch cannot write the architect selection.
 */
object EvolutionModel {
    const val DEFAULT_OPENROUTER = "nvidia/nemotron-3-ultra"
    const val DEFAULT_GEMINI = "gemini-2.5-flash"

    fun providerOf(raw: String?): EvolutionProvider =
        if (raw.equals("GEMINI", true) || raw.equals("gemini", true)) EvolutionProvider.GEMINI else EvolutionProvider.OPENROUTER

    fun normalize(provider: EvolutionProvider, raw: String?): String {
        val id = raw?.trim()?.removePrefix("openrouter/")?.removePrefix("gemini/")?.trim().orEmpty()
        return id.ifBlank { if (provider == EvolutionProvider.GEMINI) DEFAULT_GEMINI else DEFAULT_OPENROUTER }
    }

    fun select(architectModelId: String, provider: EvolutionProvider, modelId: String?): EvolutionChoice =
        EvolutionChoice(provider, normalize(provider, modelId), architectModelId)

    fun fromFields(architectModelId: String, providerRaw: String?, openRouterModel: String?, geminiModel: String?): EvolutionChoice {
        val provider = providerOf(providerRaw)
        val raw = if (provider == EvolutionProvider.GEMINI) geminiModel else openRouterModel
        return select(architectModelId, provider, raw)
    }

    fun toProvider(choice: EvolutionChoice): Provider =
        if (choice.provider == EvolutionProvider.GEMINI) Provider.GEMINI else Provider.OPENROUTER

    fun budgetLeft(spent: Double, cap: Double) = (cap - spent).coerceAtLeast(0.0)

    fun canSpend(spent: Double, cap: Double) = cap > 0.0 && spent < cap - 1e-9
}
