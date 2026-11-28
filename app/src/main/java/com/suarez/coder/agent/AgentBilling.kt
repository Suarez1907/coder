package com.suarez.coder.agent

import com.suarez.coder.context.HistoryPruner
import com.suarez.coder.cost.Price
import com.suarez.coder.cost.Pricing
import com.suarez.coder.data.Provider
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.GeminiClient
import com.suarez.coder.llm.LlmClient
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.OpenAiCompatClient
import com.suarez.coder.llm.OpenRouterCatalog
import com.suarez.coder.llm.OpenRouterModel
import com.suarez.coder.llm.Usage
import kotlin.math.roundToInt

/** Model clients, pricing, usage accounting, escalation/router selection, and token previews for [Agent]. */
internal class AgentBilling(private val agent: Agent) {
    private val settings get() = agent.settings

    private var orMetaRaw: String? = null
    private var orMeta: Map<String, OpenRouterModel> = emptyMap()

    fun client(ref: ModelRef): LlmClient = when (ref.provider) {
        Provider.GEMINI -> GeminiClient(settings.geminiKey, ref.model)
        Provider.DEEPSEEK -> OpenAiCompatClient("DeepSeek", settings.deepseekBaseUrl, settings.deepseekKey, ref.model)
        Provider.OPENROUTER -> OpenAiCompatClient("OpenRouter", OpenRouterCatalog.BASE_URL, settings.openrouterKey, ref.model,
            vision = openRouterModels()[ref.model]?.vision == true,
            reasoning = openRouterModels()[ref.model]?.reasoning == true,
            json = openRouterModels()[ref.model]?.json == true,
            openRouter = true,
            cacheControl = OpenRouterCatalog.needsCacheControl(ref.model),
            extraHeaders = mapOf("X-Title" to "Coder"))
    }

    /** Prices and capabilities from the last OpenRouter model-list fetch (parsed once per change). */
    fun openRouterModels(): Map<String, OpenRouterModel> {
        val raw = settings.openrouterMeta
        if (raw != orMetaRaw) { orMeta = OpenRouterCatalog.fromMeta(raw); orMetaRaw = raw }
        return orMeta
    }

    /** True when [ref] can read screenshots. */
    fun supportsVision(ref: ModelRef) = when (ref.provider) {
        Provider.GEMINI -> true
        Provider.DEEPSEEK -> false
        Provider.OPENROUTER -> openRouterModels()[ref.model]?.vision == true
    }

    private fun overrides() = Pricing.parseOverrides(settings.priceOverrides)

    fun price(model: String): Price {
        val o = overrides()
        val m = model.lowercase()
        if (o.keys.none { m.contains(it) }) openRouterModels()[model]?.let { return it.price }
        return Pricing.priceFor(model, o)
    }

    fun costOf(r: ChatMessage.UsageRecord) = (r.actualUsd ?: (Pricing.cost(r.usage, price(r.model)) * r.discount)) + r.extraCostUsd

    /** Share of input tokens served from cache (implicit or explicit) across [records]. */
    fun cacheHitRate(records: List<ChatMessage.UsageRecord>): Double? {
        val input = records.sumOf { it.usage.input }
        return if (input <= 0) null else records.sumOf { it.usage.cached }.toDouble() / input
    }

    fun sessionCost(history: List<ChatMessage>) = history.filterIsInstance<ChatMessage.UsageRecord>().sumOf { costOf(it) }

    fun money(usd: Double) = "${Pricing.formatUsd(usd)} (${Pricing.formatTry(usd * settings.usdTry)})"

    /** The user's ordered stronger models (keys present); falls back to the single escalation model. */
    fun escalationChain(): List<ModelRef> {
        if (agent.modelOverride != null) return emptyList()
        val chain = Escalation.parse(settings.escalationChain).mapNotNull { ModelRef.parse(it) }
            .filter { settings.keyFor(it.provider).isNotBlank() }.distinctBy { it.key }
        return chain.ifEmpty { listOfNotNull(escalationRef()) }
    }

    /**
     * Middle rung of the editor ladder: the configured model, or the cheapest known model priced between the editor and
     * the architect (e.g. a Flash model between Flash-Lite and Neon). Null when none fits or set to "none".
     */
    fun escalationRef(): ModelRef? {
        if (agent.modelOverride != null) return null
        if (settings.escalationModel.equals("none", true)) return null
        ModelRef.parse(settings.escalationModel)?.let { return it }
        val lo = Pricing.blended(price(agent.editorRef().model))
        val hi = Pricing.blended(price(agent.architectRef().model))
        val skip = Regex("tts|image|live|decision|embedding|transcribe|robotics|omni|banana", RegexOption.IGNORE_CASE)
        val candidates = (settings.modelCatalog + listOf("gemini/gemini-3.8-flash", "gemini/gemini-2.5-flash"))
            .distinct().mapNotNull { ModelRef.parse(it) }
            .filter { !skip.containsMatchIn(it.model) }
            .filter { settings.keyFor(it.provider).isNotBlank() }
            .filter { it.key != agent.editorRef().key && it.key != agent.architectRef().key }
            .filter { Pricing.blended(price(it.model)) in (lo * 1.2)..(hi * 0.8) }
        val catalog = settings.modelCatalog.toSet()
        val pool = candidates.filter { it.key in catalog }.ifEmpty { candidates }
        return pool.minByOrNull { Pricing.blended(price(it.model)) }
    }

    /** Model for tier-2 routing: the configured one, else the cheapest catalog model whose thinking can be turned (nearly) off. */
    fun routerRef(): ModelRef? {
        ModelRef.parse(settings.routerModel)?.let { return it }
        val candidates = (settings.modelCatalog + listOf("gemini/gemini-2.5-flash-lite", "gemini/gemini-3.1-flash-lite", "deepseek/deepseek-chat"))
            .distinct().mapNotNull { ModelRef.parse(it) }
            .filter { settings.keyFor(it.provider).isNotBlank() }
            .filter { Pricing.canNearlyDisableThinking(it.model) }
        val catalog = settings.modelCatalog.toSet()
        // Prefer models the account actually listed; fall back to the built-in candidates.
        val pool = candidates.filter { it.key in catalog }.ifEmpty { candidates }
        return pool.minByOrNull { Pricing.blended(price(it.model)) }
    }

    fun usageSummary(history: List<ChatMessage>, from: Int): ChatMessage.Notice? {
        val recs = history.drop(from).filterIsInstance<ChatMessage.UsageRecord>()
        if (recs.isEmpty()) return null
        val overrides = Pricing.parseOverrides(settings.priceOverrides)
        fun estimated(model: String) = !Pricing.isKnown(model) && model !in openRouterModels() && overrides.keys.none { model.lowercase().contains(it) } &&
            recs.any { it.model == model && it.actualUsd == null }
        val parts = recs.groupBy { it.role to it.model }.map { (k, list) ->
            val (role, model) = k
            val u = list.fold(Usage()) { acc, r -> acc + r.usage }
            val c = list.sumOf { costOf(it) }
            val name = if (role in setOf("architect", "editor", "router", "planner", "reviewer")) "${Agent.roleLabel(role)} ($model)" else Agent.roleLabel(role)
            if (role == "cache") "$name ${Pricing.formatUsd(c)}"
            else "$name ${Pricing.formatTokens(u.input)}→${Pricing.formatTokens(u.output + u.thoughts)} ${Pricing.formatUsd(c)}" +
                if (estimated(model)) "*" else ""
        }
        val total = recs.sumOf { costOf(it) }
        val callRecs = recs.filter { it.role != "cache" && it.role != "decision" }
        val hit = cacheHitRate(callRecs)
        val flexCalls = recs.count { it.label == "flex" }
        val detail = (agent.lastAnatomy?.let { "İstek anatomisi (son ana çağrı, yaklaşık): $it\n" } ?: "") +
            (hit?.let { "Önbellekten okunan giriş: %${(it * 100).roundToInt()} (${callRecs.sumOf { r -> r.usage.cached }} / ${callRecs.sumOf { r -> r.usage.input }} token)\n" } ?: "") +
            (if (flexCalls > 0) "Flex (%50 indirimli) çağrı: $flexCalls\n" else "") +
            "Önbellek durumu: ${agent.cacheStatus}\n" + recs.groupBy { it.role to it.model }.entries.joinToString("\n") { (k, list) ->
            val u = list.fold(Usage()) { acc, r -> acc + r.usage }
            "${Agent.roleLabel(k.first)} (${k.second}), ${list.size} çağrı: giriş ${u.input} (önbellekten ${u.cached}), " +
                "çıkış ${u.output}, düşünme ${u.thoughts}, maliyet ${money(list.sumOf { costOf(it) })}"
        } + "\n" + recs.groupBy { if (it.model.contains('/')) "OpenRouter" else if (it.model.startsWith("deepseek")) "DeepSeek" else "Gemini" }.entries.joinToString("\n") { (p, l) ->
            "$p toplamı: ${money(l.sumOf { costOf(it) })}"
        } + "\nOturum toplamı: ${money(sessionCost(history))}" +
            (if (recs.any { estimated(it.model) }) "\n* Tahmini fiyat: bu model fiyat tablosunda yok; Ayarlar > Fiyat düzeltmeleri'nden girin." else "")
        val hitText = hit?.let { " · önbellek %${(it * 100).roundToInt()}" } ?: ""
        val counters = HashMap<String, Int>()
        val steps = recs.filter { it.role in setOf("architect", "agent", "editor", "escalation", "planner", "reviewer") }.joinToString("\n") { r ->
            val n = counters.merge(r.role, 1, Int::plus)
            val u = r.usage
            val cachePct = if (u.input > 0) (u.cached * 100 / u.input) else 0
            "  ${Agent.roleLabel(r.role)} adım $n (${r.model}): ${Pricing.formatTokens(u.input)} giriş (önbellek %$cachePct) → " +
                "${Pricing.formatTokens(u.output)} çıkış + ${Pricing.formatTokens(u.thoughts)} düşünme · ${Pricing.formatUsd(costOf(r))}" +
                if (r.label == "flex") " (Flex)" else ""
        }
        val detailWithSteps = if (steps.isBlank()) detail else "Adım adım:\n$steps\n\n$detail"
        return ChatMessage.Notice("💰 " + parts.joinToString(" · ") + " · Toplam ${money(total)}$hitText", detailWithSteps)
    }

    suspend fun preview(history: List<ChatMessage>, input: String): Agent.Preview {
        val ref = agent.mainRef()
        val kind = if (settings.architectMode) "architect" else "agent"
        val stable = agent.snapshot(kind) // only an already built snapshot; no network work for a preview
        val system = if (settings.architectMode) agent.architectSystem(agent.architectHasMap) else agent.agentSystem()
        val toolSpecs = if (settings.architectMode) agent.tools.singleShotSpecs else agent.tools.specs
        val prefix = stable?.let { listOf(ChatMessage.User(it), ChatMessage.Assistant(Agent.STABLE_ACK)) } ?: emptyList()
        val msgs = prefix + HistoryPruner.prepare(history + ChatMessage.User(input), settings.keepTurns).messages
        val client = client(ref)
        val exact = runCatching { client.countTokens(system, msgs, toolSpecs) }.getOrNull()
        val tokens = exact ?: run {
            val chars = system.length + HistoryPruner.charSize(msgs) + toolSpecs.sumOf { it.parameters.toString().length + it.description.length }
            (chars / 3.5).toLong()
        }
        return Agent.Preview(tokens, Pricing.inputCost(tokens, price(ref.model)), exact != null, ref.model)
    }
}
