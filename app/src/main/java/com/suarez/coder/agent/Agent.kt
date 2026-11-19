package com.suarez.coder.agent

import com.suarez.coder.context.ContextProvider
import com.suarez.coder.context.HistoryPruner
import com.suarez.coder.cost.Pricing
import com.suarez.coder.data.Provider
import com.suarez.coder.data.Settings
import com.suarez.coder.decision.DecisionClient
import com.suarez.coder.decision.Route
import com.suarez.coder.decision.RoutingDecision
import com.suarez.coder.decision.SmartRouter
import com.suarez.coder.llm.CacheRejectedException
import com.suarez.coder.llm.CallOptions
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.GeminiClient
import com.suarez.coder.llm.LlmClient
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.OpenAiCompatClient
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.llm.ToolSpec
import com.suarez.coder.llm.Usage
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** What the agent needs from the screen. */
interface AgentUi {
    fun update()
    suspend fun confirm(title: String, body: String, okText: String = "Onayla", cancelText: String = "Reddet"): Boolean
}

class StopException(message: String) : Exception(message)

class Agent(val settings: Settings) {
    val tools = ToolRegistry(settings)
    val context = ContextProvider(settings, tools.github)
    private val cache = GeminiCacheManager(settings)
    private var budgetApprovedUpTo = 0.0
    private val router = SmartRouter(
        tools.github,
        listFiles = { repo, ref -> tools.filteredTree(repo, ref) },
        decide = { input, questions ->
            DecisionClient(settings.geminiKey, settings.decisionModel, settings.decisionTimeoutMs).decide(input, questions)
        },
    )

    init {
        tools.onRepoChanged = { context.invalidate() }
    }

    companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val SUMMARIZE_AFTER_CHARS = 8_000

        fun roleLabel(role: String) = when (role) {
            "architect" -> "Mimar"
            "editor" -> "Uygulayıcı"
            "summary" -> "Özet"
            "cache" -> "Önbellek"
            "decision" -> "Karar"
            else -> "Ajan"
        }
    }

    fun resetSession() {
        budgetApprovedUpTo = 0.0
        cache.invalidate()
        context.invalidate()
    }

    // ---- Models ----

    fun singleRef(p: Provider = settings.provider) = ModelRef(p, if (p == Provider.GEMINI) settings.geminiModel else settings.deepseekModel)
    fun architectRef() = ModelRef.parse(settings.architectModel) ?: singleRef()
    fun editorRef() = ModelRef.parse(settings.editorModel) ?: singleRef()
    fun mainRef() = if (settings.architectMode) architectRef() else singleRef()

    fun client(ref: ModelRef): LlmClient = when (ref.provider) {
        Provider.GEMINI -> GeminiClient(settings.geminiKey, ref.model)
        Provider.DEEPSEEK -> OpenAiCompatClient("DeepSeek", settings.deepseekBaseUrl, settings.deepseekKey, ref.model)
    }

    // ---- Cost ----

    private fun overrides() = Pricing.parseOverrides(settings.priceOverrides)
    fun price(model: String) = Pricing.priceFor(model, overrides())
    fun costOf(r: ChatMessage.UsageRecord) = Pricing.cost(r.usage, price(r.model)) + r.extraCostUsd
    fun sessionCost(history: List<ChatMessage>) = history.filterIsInstance<ChatMessage.UsageRecord>().sumOf { costOf(it) }
    fun money(usd: Double) = "${Pricing.formatUsd(usd)} (${Pricing.formatTry(usd * settings.usdTry)})"

    private suspend fun checkBudget(history: List<ChatMessage>, ui: AgentUi) {
        val budget = settings.budgetUsd
        if (budget <= 0) return
        val cost = sessionCost(history)
        val limit = maxOf(budget, budgetApprovedUpTo)
        if (cost < limit) return
        val ok = ui.confirm(
            "Bütçe eşiği aşıldı",
            "Bu sohbetin maliyeti ${money(cost)}, eşik ${money(limit)}.\nYeni API isteği gönderilsin mi?",
            "Devam et", "Durdur",
        )
        if (!ok) throw StopException("Bütçe eşiğinde durduruldu (${money(cost)}).")
        budgetApprovedUpTo = cost + budget
    }

    // ---- Prompts ----

    private fun header(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val repo = settings.defaultRepo.takeIf { it.isNotBlank() }
        return buildString {
            appendLine("You are Coder, a software engineering agent inside an Android app. The user works only from a phone.")
            appendLine("Tools give you GitHub (read, commit, branches, PRs, any REST endpoint incl. Actions logs) and the web (search, fetch).")
            appendLine("You cannot run code on the device; to build or test, use the repo's GitHub Actions and read the logs.")
            appendLine("Reply in the user's language (default Turkish). Keep answers short; the screen is small.")
            appendLine("Today: $date.")
            if (repo != null) appendLine("Default repository: $repo.")
            if (settings.customInstructions.isNotBlank()) appendLine("User instructions: ${settings.customInstructions}")
        }
    }

    private val tokenRules = """
        Token economy (important, the user pays per token):
        - Read only what you need: use the file tree, then read specific line ranges of large files.
        - Change existing files with github_apply_diff (SEARCH/REPLACE blocks), never by rewriting the whole file.
          Keep each SEARCH small but unique: the changed lines plus 1-3 context lines, copied exactly.
        - Use github_write_files only for new files, full rewrites of tiny files, or deletions.
        - Do not repeat file contents or diffs in your answer; summarize what changed with links.
    """.trimIndent()

    fun agentSystem(ctx: String?) = buildString {
        append(header()).append('\n').append(tokenRules).append('\n')
        appendLine("Workflow: understand first, then change; group related edits in one commit; unless told otherwise use the branch the user names, or a new feature branch and offer a PR.")
        if (ctx != null) append('\n').append(ctx)
    }

    fun architectSystem(ctx: String?) = buildString {
        append(header()).append('\n')
        appendLine(
            """
            You are the ARCHITECT. A cheaper EDITOR model will implement your plan. You must not write the code yourself.
            1. Inspect only what you need with the read-only tools (tree, specific line ranges, search, web).
            2. If code must change, call submit_plan exactly once. For each file give short, precise, numbered logical steps:
               which function/class/section changes, what to add/remove, exact names, signatures and behaviour.
               Snippets only when essential and at most 5 lines. List every file that must change (action modify/create/delete).
            3. If the request needs no code change (a question, explanation or research), answer directly and do not call submit_plan.
            Branch: use the one the user names; otherwise choose a short new feature branch name.
            """.trimIndent()
        )
        if (ctx != null) append('\n').append(ctx)
    }

    val editorSystem = """
        You are a code EDITOR. Implement the given plan in the given files by emitting SEARCH/REPLACE blocks ONLY.
        Format for every change:
        path/to/file
        <<<<<<< SEARCH
        exact existing lines
        =======
        new lines
        >>>>>>> REPLACE
        Rules:
        - Put the file path alone on the line right above <<<<<<< SEARCH.
        - SEARCH must copy existing lines character for character (indentation included) and must be unique in the file.
        - Keep blocks small: only the lines that change plus 1-3 unchanged context lines. Use several blocks per file, in file order.
        - New file: leave SEARCH empty and put the whole file in REPLACE. To remove code, leave REPLACE empty.
        - Write complete, working code; no placeholders. Only touch the files listed.
        - Output nothing except the blocks: no explanations, no whole files.
    """.trimIndent()

    // ---- Model calls ----

    /** One API call with budget check, optional Gemini cache and usage bookkeeping. */
    suspend fun callModel(
        history: MutableList<ChatMessage>,
        messages: List<ChatMessage>,
        ref: ModelRef,
        role: String,
        system: String,
        toolSpecs: List<ToolSpec>,
        ui: AgentUi,
        useCache: Boolean = true,
    ): ChatMessage.Assistant {
        checkBudget(history, ui)
        val client = client(ref)
        var opts = CallOptions(thinkingLevel = settings.thinkingLevel)
        if (useCache && client is GeminiClient) {
            cache.prepare(client, system, toolSpecs)?.let { p ->
                opts = opts.copy(cachedContent = p.name)
                if (p.createdOrExtended) {
                    history += ChatMessage.UsageRecord(ref.model, "cache", Usage(),
                        Pricing.storageCost(p.tokens, p.ttlMinutes.toDouble(), price(ref.model)), cache.lastStatus)
                }
            }
        }
        val resp = try {
            client.complete(system, messages, toolSpecs, opts)
        } catch (e: CacheRejectedException) {
            cache.invalidate()
            client.complete(system, messages, toolSpecs, opts.copy(cachedContent = null))
        }
        history += ChatMessage.UsageRecord(ref.model, role, resp.usage)
        return resp.message
    }

    fun questionSystem(ctx: String?) = agentSystem(ctx) +
        "\nThis request needs no code change: answer it, using read-only tools if needed. Do not modify the repository.\n"

    // ---- Entry point ----

    /** [override] forces a route for this request (e.g. the user re-runs it in Architect mode). */
    suspend fun run(history: MutableList<ChatMessage>, ui: AgentUi, override: Route? = null) {
        maybeSummarize(history, ui)
        val start = history.size
        try {
            val request = history.lastOrNull { it is ChatMessage.User } as? ChatMessage.User
            var route: Route? = override
            var autoCtx: String? = null
            if (override != null) {
                history += ChatMessage.RouteInfo("↻ Kullanıcı kararı geçersiz kıldı → Mimar Modu (${architectRef().model} + ${editorRef().model})",
                    null, override.name, false)
                ui.update()
            } else if (settings.smartRouting && request != null) {
                val repo = context.repo.takeIf { it.isNotBlank() }?.let { tools.normalizeRepo(it) }
                val d = router.route(request.text, recentContext(history), repo, context.ref, context.hasManualFiles)
                if (d.fallbackReason == null) {
                    history += ChatMessage.UsageRecord(settings.decisionModel, "decision", Usage(input = d.inputTokens))
                    route = d.route
                    if (d.selected.isNotEmpty() && d.repo != null) {
                        autoCtx = context.loadAuto(d.repo, d.ref, d.selected.map { it.path })?.text
                    }
                }
                history += routeInfo(d)
                ui.update()
            }
            val ctx = autoCtx ?: context.load()?.text
            when (route) {
                Route.COMPLEX_ARCHITECT -> ArchitectEditor(this).run(history, ui, ctx)
                Route.SIMPLE_EDIT ->
                    agentLoop(history, ui, editorRef(), "agent", agentSystem(ctx), tools.specs, readOnly = false, stopTool = null)
                Route.QUESTION_ONLY ->
                    agentLoop(history, ui, editorRef(), "agent", questionSystem(ctx),
                        tools.readOnlySpecs.filter { it.name != "submit_plan" }, readOnly = true, stopTool = null)
                null ->
                    if (settings.architectMode) ArchitectEditor(this).run(history, ui, ctx)
                    else agentLoop(history, ui, singleRef(), "agent", agentSystem(ctx), tools.specs, readOnly = false, stopTool = null)
            }
        } finally {
            usageSummary(history, start)?.let { history += it }
            ui.update()
        }
    }

    /** The previous exchange as plain text, so follow-ups like "devam et" can be classified. */
    private fun recentContext(history: List<ChatMessage>): String? {
        val lastUser = history.indexOfLast { it is ChatMessage.User }
        if (lastUser <= 0) return null
        val prevAnswer = history.subList(0, lastUser).lastOrNull { it is ChatMessage.Assistant && it.text.isNotBlank() } as? ChatMessage.Assistant
        val prevUser = history.subList(0, lastUser).lastOrNull { it is ChatMessage.User } as? ChatMessage.User
        if (prevAnswer == null && prevUser == null) return null
        return buildString {
            prevUser?.let { append("User: ").append(it.text.take(300)).append('\n') }
            prevAnswer?.let { append("Assistant: ").append(HistoryPruner.stripCodeBlocks(it.text).take(500)) }
        }
    }

    private fun pct(v: Double) = "%${(v * 100).roundToInt()}"

    private fun manualModeLabel() =
        if (settings.architectMode) "Mimar Modu (${architectRef().model} + ${editorRef().model})" else singleRef().model

    fun routeInfo(d: RoutingDecision): ChatMessage.RouteInfo {
        if (d.fallbackReason != null) {
            return ChatMessage.RouteInfo(
                "Decision 1 kullanılamadı (${d.fallbackReason.take(80)}) → manuel seçim: ${manualModeLabel()}",
                null, "MANUAL", !settings.architectMode,
            )
        }
        val mode = when (d.route) {
            Route.SIMPLE_EDIT -> "Hızlı Mod (${editorRef().model}, ${pct(d.confidence)} güven)"
            Route.QUESTION_ONLY -> "Soru Modu (${editorRef().model}, ${pct(d.confidence)} güven)"
            Route.COMPLEX_ARCHITECT ->
                if (d.upgraded && d.rawRoute == Route.SIMPLE_EDIT) "Mimar Modu (basit düzenleme ${pct(d.confidence)} < %75, yükseltildi)"
                else if (d.upgraded) "Mimar Modu (karar okunamadı, güvenli seçim)"
                else "Mimar Modu (${architectRef().model} + ${editorRef().model}, ${pct(d.confidence)} güven)"
        }
        val files = when {
            d.manualFiles -> "📎 manuel dosyalar korundu"
            d.totalFiles > 0 -> "${d.selected.size}/${d.totalFiles} dosya seçildi"
            else -> null
        }
        val text = listOfNotNull("Decision 1 → $mode", files, "${d.latencyMs} ms").joinToString(" • ")
        val detail = buildString {
            if (d.routeProbabilities.isNotEmpty()) {
                append("Sınıflandırma olasılıkları:\n")
                d.routeProbabilities.entries.sortedByDescending { it.value }.forEach { append("  ${it.key}: ${pct(it.value)}\n") }
            }
            if (d.selected.isNotEmpty()) {
                append("\nBağlama eklenen dosyalar (evet olasılığı):\n")
                d.selected.forEach { append("  ${it.path}: ${pct(it.yes)}\n") }
            } else if (!d.manualFiles && d.totalFiles > 0) append("\nEşik (%60) üzerinde dosya yok; otomatik bağlam eklenmedi.\n")
            append("\nKarar çağrısı: ${d.inputTokens} giriş token, 0 çıkış token.")
        }
        return ChatMessage.RouteInfo(text, detail, d.route.name, d.route != Route.COMPLEX_ARCHITECT)
    }

    private fun usageSummary(history: List<ChatMessage>, from: Int): ChatMessage.Notice? {
        val recs = history.drop(from).filterIsInstance<ChatMessage.UsageRecord>()
        if (recs.isEmpty()) return null
        val parts = recs.groupBy { it.role }.map { (role, list) ->
            val u = list.fold(Usage()) { acc, r -> acc + r.usage }
            val c = list.sumOf { costOf(it) }
            if (role == "cache") "${roleLabel(role)} ${Pricing.formatUsd(c)}"
            else "${roleLabel(role)} ${Pricing.formatTokens(u.input)}→${Pricing.formatTokens(u.output + u.thoughts)} ${Pricing.formatUsd(c)}"
        }
        val total = recs.sumOf { costOf(it) }
        val detail = recs.groupBy { it.role to it.model }.entries.joinToString("\n") { (k, list) ->
            val u = list.fold(Usage()) { acc, r -> acc + r.usage }
            "${roleLabel(k.first)} (${k.second}), ${list.size} çağrı: giriş ${u.input} (önbellekten ${u.cached}), " +
                "çıkış ${u.output}, düşünme ${u.thoughts}, maliyet ${money(list.sumOf { costOf(it) })}"
        } + "\nOturum toplamı: ${money(sessionCost(history))}"
        return ChatMessage.Notice("💰 " + parts.joinToString(" · ") + " · Toplam ${money(total)}", detail)
    }

    /** Folds old turns into a short summary once they get large. */
    private suspend fun maybeSummarize(history: MutableList<ChatMessage>, ui: AgentUi) {
        val p = HistoryPruner.prepare(history, settings.keepTurns)
        if (HistoryPruner.charSize(p.summarizable) < SUMMARIZE_AFTER_CHARS) return
        val prompt = buildString {
            if (p.summary != null) append("Önceki özet:\n").append(p.summary).append("\n\n")
            append("Konuşma:\n").append(HistoryPruner.transcript(p.summarizable))
        }
        val system = "Summarize this coding conversation for a future assistant in at most 15 short bullet points, in the conversation's language. " +
            "Keep: user goals and preferences, repos/branches/files touched, decisions, commits and PRs (with links), open problems. Drop code and tool output."
        try {
            val scratch = ArrayList<ChatMessage>()
            val reply = callModel(scratch, listOf(ChatMessage.User(prompt)), editorRef(), "summary", system, emptyList(), ui, useCache = false)
            val rec = scratch.filterIsInstance<ChatMessage.UsageRecord>()
            if (reply.text.isNotBlank() && !reply.text.startsWith("(Model boş")) {
                history.add(p.summaryInsertIndex, ChatMessage.Summary(reply.text.trim()))
                history.addAll(p.summaryInsertIndex + 1, rec)
                ui.update()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StopException) {
            throw e
        } catch (e: Exception) {
            history += ChatMessage.Notice("Özetleme yapılamadı, kısaltılmış geçmişle devam ediliyor: ${e.message}")
        }
    }

    /**
     * Tool-use loop. Returns the [stopTool] call when the model makes it (its result is left to the caller),
     * or null when the model answered without tools.
     */
    suspend fun agentLoop(
        history: MutableList<ChatMessage>,
        ui: AgentUi,
        ref: ModelRef,
        role: String,
        system: String,
        toolSpecs: List<ToolSpec>,
        readOnly: Boolean,
        stopTool: String?,
    ): ToolCall? {
        var consecutiveFailures = 0
        for (step in 1..settings.maxSteps) {
            val prepared = HistoryPruner.prepare(history, settings.keepTurns)
            val reply = callModel(history, prepared.messages, ref, role, system, toolSpecs, ui)
            history += reply
            ui.update()
            if (reply.toolCalls.isEmpty()) return null

            var stopCall: ToolCall? = null
            val calls = reply.toolCalls
            for ((i, call) in calls.withIndex()) {
                if (call.name == stopTool) { stopCall = call; continue }
                val (result, rejected) = executeTool(call, readOnly, ui)
                history += result
                ui.update()
                consecutiveFailures = if (result.isError && !rejected) consecutiveFailures + 1 else 0
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    val ok = ui.confirm(
                        "$MAX_CONSECUTIVE_FAILURES deneme üst üste başarısız",
                        "Ajan aynı hatayı tekrarlıyor olabilir; devam etmek maliyeti artırır.\n\nSon hata:\n${result.content.take(800)}\n\nDevam edilsin mi?",
                        "Devam et", "Durdur",
                    )
                    if (!ok) {
                        // Every tool call must get an answer, or the next request is rejected.
                        calls.drop(i + 1).forEach { history += ChatMessage.ToolResult(it.id, it.name, "İptal edildi: kullanıcı durdurdu.", true) }
                        history += ChatMessage.Notice("$MAX_CONSECUTIVE_FAILURES başarısız denemeden sonra durduruldu.")
                        return null
                    }
                    consecutiveFailures = 0
                }
            }
            if (stopCall != null) return stopCall
        }
        history += ChatMessage.Notice("Adım sınırına (${settings.maxSteps}) ulaşıldı. Devam etmek için \"devam et\" yazın.")
        return null
    }

    /** Returns the result and whether the user rejected it. */
    private suspend fun executeTool(call: ToolCall, readOnly: Boolean, ui: AgentUi): Pair<ChatMessage.ToolResult, Boolean> {
        return try {
            if (!readOnly && tools.needsApproval(call) && !settings.autoApprove &&
                !ui.confirm("İzin ver: ${call.name}", "Bu işlem GitHub'da değişiklik yapacak.\n\n" + call.args.toString(2).take(20_000))
            ) {
                ChatMessage.ToolResult(call.id, call.name, "Kullanıcı bu işlemi reddetti. Ne yapmak istediğini kullanıcıya sor.", true) to true
            } else {
                ChatMessage.ToolResult(call.id, call.name, tools.execute(call, readOnly)) to false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatMessage.ToolResult(call.id, call.name, e.message ?: e.toString(), true) to false
        }
    }

    // ---- Token preview ----

    data class Preview(val tokens: Long, val costUsd: Double, val exact: Boolean, val model: String)

    suspend fun preview(history: List<ChatMessage>, input: String): Preview {
        val ref = mainRef()
        val ctx = context.load()?.text
        val system = if (settings.architectMode) architectSystem(ctx) else agentSystem(ctx)
        val toolSpecs = if (settings.architectMode) tools.readOnlySpecs else tools.specs
        val msgs = HistoryPruner.prepare(history + ChatMessage.User(input), settings.keepTurns).messages
        val client = client(ref)
        val exact = runCatching { client.countTokens(system, msgs, toolSpecs) }.getOrNull()
        val tokens = exact ?: run {
            val chars = system.length + HistoryPruner.charSize(msgs) + toolSpecs.sumOf { it.parameters.toString().length + it.description.length }
            (chars / 3.5).toLong()
        }
        return Preview(tokens, Pricing.inputCost(tokens, price(ref.model)), exact != null, ref.model)
    }
}
