package com.suarez.coder.cost

import org.json.JSONObject

/** Interactive calls stay on the user's model. Background jobs may take a cheaper route. */
enum class PriorityTier { INTERACTIVE, BACKGROUND_FLEX }

/**
 * Flex / economic routing and cache-TTL pacing. Nothing here changes the locked architect model id.
 * An unsupported provider, or a provider that rejects the extra parameter, stays on the standard endpoint.
 */
object FinOpsArbitrageEngine {
    const val OPENROUTER_TTL_SEC = 5 * 60
    const val ANTHROPIC_TTL_SEC = 5 * 60
    const val GEMINI_IMPLICIT_TTL_SEC = 5 * 60

    data class EconomicRoute(val supported: Boolean, val serviceTier: String?, val priceSort: Boolean)

    data class Pace(val minimizeWaits: Boolean, val preferCompactAndTurn0: Boolean, val interStepDelayMs: Long)

    fun tierOf(component: String): PriorityTier = when (component) {
        "EvolutionWorker", "SkillSynthesizer", "RepoSentinelWorker" -> PriorityTier.BACKGROUND_FLEX
        else -> PriorityTier.INTERACTIVE
    }

    /**
     * Discount parameters for a background call. [supported] is false for providers that have no economic
     * route (DeepSeek today): the caller sends the ordinary body.
     */
    fun route(provider: String, model: String, tier: PriorityTier, enabled: Boolean): EconomicRoute {
        if (!enabled || tier != PriorityTier.BACKGROUND_FLEX) return EconomicRoute(false, null, false)
        return when (provider.lowercase()) {
            "gemini" -> EconomicRoute(true, "flex", false)
            "openrouter" -> EconomicRoute(true, if (supportsOpenAiFlex(model)) "flex" else null, true)
            "anthropic" -> EconomicRoute(true, null, false)
            else -> EconomicRoute(false, null, false)
        }
    }

    /** OpenAI models that accept service_tier=flex. Other OpenRouter models only get the price sort. */
    fun supportsOpenAiFlex(model: String): Boolean {
        val m = model.lowercase()
        return m.contains("gpt-5") || m.contains("gpt-4.1") || m.contains("/o3") || m.contains("/o4") ||
            m.startsWith("o3") || m.startsWith("o4")
    }

    fun apply(body: JSONObject, route: EconomicRoute): JSONObject {
        if (!route.supported) return body
        route.serviceTier?.let { body.put("service_tier", it) }
        if (route.priceSort) body.put("provider", JSONObject().put("sort", "price"))
        return body
    }

    /**
     * Drops Flex / price-sort fields after the provider rejects them. Returns true when the body changed
     * and the caller should retry the standard endpoint. A rejection that is not about those fields is left alone.
     */
    fun stripUnsupported(body: JSONObject, error: String): Boolean {
        if (!Regex("service[_ ]?tier|\\bflex\\b|provider", RegexOption.IGNORE_CASE).containsMatchIn(error)) return false
        val had = body.has("service_tier") || body.has("serviceTier") || body.has("provider")
        body.remove("service_tier")
        body.remove("serviceTier")
        body.remove("provider")
        return had
    }

    fun defaultTtlSec(provider: String, explicit: Boolean, explicitMinutes: Int = 10): Int = when (provider.lowercase()) {
        "anthropic" -> ANTHROPIC_TTL_SEC
        "openrouter" -> OPENROUTER_TTL_SEC
        "gemini" -> if (explicit) explicitMinutes.coerceIn(1, 120) * 60 else GEMINI_IMPLICIT_TTL_SEC
        else -> OPENROUTER_TTL_SEC
    }

    /**
     * Warm cache: do not insert a wait between DAG / tool steps.
     * Cold cache (a real use that has since expired): compact history and prefer Turn-0 instead of paying a cold write.
     * A tracker that has never seen a cache stays on the normal path.
     */
    fun pace(tracker: CacheTtlTracker?, provider: String, now: Long = System.currentTimeMillis()): Pace {
        val view = tracker?.view(provider, now) ?: return Pace(false, false, 0)
        return when (view.state) {
            CacheTtlTracker.State.WARM -> Pace(minimizeWaits = true, preferCompactAndTurn0 = false, interStepDelayMs = 0)
            CacheTtlTracker.State.COLD -> Pace(minimizeWaits = false, preferCompactAndTurn0 = true, interStepDelayMs = 0)
            CacheTtlTracker.State.NEVER -> Pace(false, false, 0)
        }
    }
}

/**
 * Per-provider cache lifetime. Remaining time is what the orchestrator uses to skip pauses while the prefix is hot.
 */
class CacheTtlTracker {
    enum class State { NEVER, WARM, COLD }

    data class View(val state: State, val remainingSec: Int, val ttlSec: Int, val percent: Int)

    private data class Hit(val atMs: Long, val ttlSec: Int)

    private val hits = HashMap<String, Hit>()

    @Synchronized
    fun note(provider: String, ttlSec: Int, now: Long = System.currentTimeMillis()) {
        val key = provider.trim().lowercase()
        if (key.isBlank()) return
        hits[key] = Hit(now, ttlSec.coerceAtLeast(1))
    }

    @Synchronized
    fun view(provider: String, now: Long = System.currentTimeMillis()): View {
        val hit = hits[provider.trim().lowercase()] ?: return View(State.NEVER, 0, 0, 0)
        val elapsed = ((now - hit.atMs) / 1000L).toInt().coerceAtLeast(0)
        val remaining = (hit.ttlSec - elapsed).coerceAtLeast(0)
        val state = if (remaining > WARM_AFTER_SEC) State.WARM else State.COLD
        val percent = (remaining * 100 / hit.ttlSec).coerceIn(0, 100)
        return View(state, remaining, hit.ttlSec, percent)
    }

    fun warmRemainingSec(provider: String, now: Long = System.currentTimeMillis()): Int = view(provider, now).remainingSec

    companion object {
        /** Strictly above this many seconds the cache is still warm. */
        const val WARM_AFTER_SEC = 20

        fun formatClock(sec: Int): String {
            val s = sec.coerceAtLeast(0)
            val h = s / 3600
            val m = (s % 3600) / 60
            val r = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
        }

        /** Shown on the chat meta line only while the cache is warm. */
        fun label(hitPercent: Int?, view: View): String? {
            if (view.state != State.WARM) return null
            val pct = hitPercent ?: view.percent
            return "Önbellek %$pct · Sıcak ${formatClock(view.remainingSec)}"
        }
    }
}

/** Savings the cost dashboard reads off finished traces. */
object FinOpsReport {
    data class Summary(
        val turn0Runs: Int,
        val turn0SavedTurns: Int,
        val turn0SavedTokens: Long,
        val watchdogCuts: Int,
        val watchdogSavedUsd: Double,
        val codeModFiles: Int,
        val flexSavedUsd: Double,
    ) {
        val totalSavedUsd get() = watchdogSavedUsd + flexSavedUsd
    }

    fun summarize(traces: List<com.suarez.coder.evolution.SessionTrace>): Summary {
        var runs = 0
        var turns = 0
        var tokens = 0L
        var cuts = 0
        var watchUsd = 0.0
        var files = 0
        var flex = 0.0
        for (t in traces) {
            if (t.turn0PackUsed) {
                runs++
                val savedTurns = if (t.firstTurnEditSuccess) 6 else 2
                turns += savedTurns
                tokens += (t.turn0CharsPacked / 4L) + savedTurns * 800L
            }
            cuts += t.watchdogCuts
            watchUsd += t.watchdogSavedUsd
            files += t.codeModFilesTouched
            tokens += t.codeModSavedTokens + t.estimatedSavedOutputTokens
            flex += t.flexSavedUsd
        }
        return Summary(runs, turns, tokens, cuts, watchUsd, files, flex)
    }
}
