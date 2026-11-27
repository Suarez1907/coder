package com.suarez.coder.agent

import com.suarez.coder.context.ContextProvider
import com.suarez.coder.context.HistoryPruner
import com.suarez.coder.cost.Price
import com.suarez.coder.cost.Pricing
import com.suarez.coder.data.Provider
import com.suarez.coder.data.Settings
import com.suarez.coder.orchestrator.ContractDagRunner
import com.suarez.coder.orchestrator.MultiAgentOrchestrator
import com.suarez.coder.orchestrator.OrchestratorHooks
import com.suarez.coder.orchestrator.OrchestratorMode
import com.suarez.coder.orchestrator.OrchestratorPlan
import com.suarez.coder.orchestrator.OrchestratorPlanner
import com.suarez.coder.orchestrator.PlanState
import com.suarez.coder.orchestrator.SequentialOrchestrator
import com.suarez.coder.orchestrator.TaskStatus
import com.suarez.coder.decision.DecisionChain
import com.suarez.coder.decision.DecisionClient
import com.suarez.coder.decision.DecisionProvider
import com.suarez.coder.decision.LocalRouter
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
import com.suarez.coder.llm.OpenRouterCatalog
import com.suarez.coder.llm.OpenRouterModel
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.llm.ToolSpec
import com.suarez.coder.llm.Usage
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject

/** What the agent needs from the screen. */
interface AgentUi {
    fun update()
    suspend fun confirm(title: String, body: String, okText: String = "Onayla", cancelText: String = "Reddet"): Boolean
    /** What the agent is waiting for right now (shown with a running timer); null clears it. */
    fun status(text: String?) {}
}

class StopException(message: String) : Exception(message)

/** How a bounded architect turn ended. */
sealed class ArchOutcome {
    /** The plan tool was called with a usable plan (its ToolResult is added by the caller). */
    data class Submitted(val call: ToolCall) : ArchOutcome()
    /** The architect handed the work to the executor (its ToolResult is added by the caller). */
    data class Delegated(val call: ToolCall, val instructions: String) : ArchOutcome()
    /** Answered in text: no code change needed. */
    object Answered : ArchOutcome()
    data class Unusable(val why: String) : ArchOutcome()
}

class Agent(val settings: Settings, private val filesDir: java.io.File? = null) {
    val tools = ToolRegistry(settings)
    val context = ContextProvider(settings, tools.github)
    val repoMaps = com.suarez.coder.repomap.RepoMapService(tools.github, filesDir)

    init {
        if (filesDir != null) {
            tools.skills = com.suarez.coder.skills.SkillRepository(
                java.io.File(filesDir, "skills"),
                listPaths = { repo -> tools.github.treeEntries(repo, null)?.second?.map { it.path } },
                readFile = { repo, path -> tools.github.readFileRaw(repo, path, null) },
            )
            tools.cloud = com.suarez.coder.cloudtools.CloudToolRunner(
                tools.github,
                com.suarez.coder.apk.ApkDownloader({ settings.githubToken }, java.io.File(filesDir, "cloud-tmp"), com.suarez.coder.llm.Http.client),
                java.io.File(filesDir, "cloud"),
                ciErrors = { repo, id -> tools.ciErrors(repo, id) },
            )
            tools.flaky = com.suarez.coder.ci.Flaky.Store(java.io.File(filesDir, "flaky.json"))
            tools.semantic = com.suarez.coder.context.SemanticIndex(tools.github, { r, ref -> tools.filteredTree(r, ref) },
                java.io.File(filesDir, "semantic"), { settings.geminiKey })
        }
        tools.preCommit = { repo, branch, changes ->
            guards.supplyChain(repo, branch, changes) // no model call: cheapest first
            guards.selfReview(repo, branch, changes)
            guards.quickCheck(repo, branch, changes)
        }
        tools.beforeWrite = { repo, branch -> guards.checkpoint(repo, branch) }
        tools.afterCommit = { repo, result, paths, newFiles ->
            runCatching { foresight.recordCommit(repo, result, paths, newFiles) }
            // SkillsSync: a committed .coder/skills/* file makes the repo skill cache stale and tells the agent how to
            // use the skill right now (the catalog itself is refreshed on the next request).
            if (com.suarez.coder.skills.SkillSync.touches(paths)) {
                runCatching { tools.skills?.invalidate(repo) }
                com.suarez.coder.skills.SkillSync.notice(paths)?.let { n -> if (!isEvolutionSession) liveHistory?.add(ChatMessage.Notice(n)) }
            }
        }
    }

    // ---- Run-scoped helpers: self-review, checkpoints, @-mentions, similar tasks, custom modes ----

    /** The conversation of the current run (hooks called from tools write their notices and costs here). */
    internal var liveHistory: MutableList<ChatMessage>? = null
    internal var liveUi: AgentUi? = null
    internal val guards by lazy { CommitGuards(this) }
    internal val foresight by lazy { Foresight(this) }
    internal val cloudSkills by lazy { CloudSkills(this) }
    init { if (filesDir != null) tools.cloudOps = cloudSkills }
    /** Decision 1's difficulty for the current request (0..3), when routing produced one. */
    var lastComplexity: Double? = null
    internal fun filesDirOrNull() = filesDir
    internal fun architectSnapshot(): String? = snapshots["architect"]
    internal val safety by lazy { SafetyPolicy(this) }
    private val ciController by lazy { CiController(this) }
    private val requestContext by lazy { RequestContext(this) }
    private val prompts by lazy { AgentPrompts(this) }

    fun agentSystem() = prompts.agentSystem()
    fun architectSystem(hasMap: Boolean, singleShot: Boolean = false) = prompts.architectSystem(hasMap, singleShot)
    val editorSystem: String get() = prompts.editorSystem
    fun questionSystem() = prompts.questionSystem()
    fun securityQuestion(call: ToolCall, method: String?) = safety.securityQuestion(call, method)
    fun unattendedDenied(name: String, method: String?) = safety.unattendedDenied(name, method)
    /** Set by the app: a hint from similar successful earlier tasks, or null. */
    var similarFor: ((repo: String, request: String) -> String?)? = null
    /** CI error → fix memory (shared file; null without app storage). */
    val fixMemory: FixMemory? = filesDir?.let { FixMemory(java.io.File(it, "fixes.json")) }
    /** CI ders defteri: aynı hata tekrar gelince sınıf + çözüm ipucu (null depolama yoksa). */
    val ciKnowledge: com.suarez.coder.ci.CiKnowledgeBase? = filesDir?.let { com.suarez.coder.ci.CiKnowledgeBase(java.io.File(it, "ci-lessons.json")) }

    /** The user's ordered stronger models (keys present); falls back to the single escalation model. */
    fun escalationChain(): List<ModelRef> {
        if (modelOverride != null) return emptyList()
        val chain = Escalation.parse(settings.escalationChain).mapNotNull { ModelRef.parse(it) }
            .filter { settings.keyFor(it.provider).isNotBlank() }.distinctBy { it.key }
        return chain.ifEmpty { listOfNotNull(escalationRef()) }
    }

    /** Plan critic: a cheap model looks for missing callers/registrations; issues go to the executor, missing files join the plan. */
    suspend fun critiquePlan(history: MutableList<ChatMessage>, ui: AgentUi, plan: ParsedPlan): ParsedPlan {
        if (!settings.planCritic || isEvolutionSession || plan.files.isEmpty()) return plan
        val map = snapshots["architect"]?.substringAfter("REPO MAP", "")?.take(40_000)
        val planText = "Repo ${plan.repo}, branch ${plan.branch}\nCommit: ${plan.message}\nNotes: ${plan.notes}\n" +
            plan.files.joinToString("\n") { "## ${it.path} [${it.action}]\n${it.instructions}" }
        ui.status("Plan eleştirmeni planı inceliyor")
        val reply = try {
            callModel(history, listOf(ChatMessage.User("PLAN:\n$planText" + (map?.takeIf { it.isNotBlank() }?.let { "\n\nREPO MAP$it" } ?: ""))),
                routerRef() ?: editorRef(), "critic", PlanCritic.SYSTEM, emptyList(), ui, useCache = false, maxOut = 700, thinking = "low")
        } catch (e: CancellationException) { throw e } catch (e: Exception) { return plan } finally { ui.status(null) }
        val v = PlanCritic.parse(reply.text) ?: return plan
        if (v.ok) { history += ChatMessage.Notice("Plan eleştirmeni: eksik bulmadı"); ui.update(); return plan }
        val known = plan.files.map { it.path }.toSet()
        // The plan's branch is often new: fall back to its base, then the default branch.
        val tree = listOf(plan.branch.takeIf { it.isNotBlank() }, plan.base, null).distinct().firstNotNullOfOrNull { ref ->
            runCatching { tools.filteredTree(plan.repo, ref) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }.orEmpty().map { it.path }.toSet()
        val add = v.addFiles.filter { it.path !in known && (it.action == "create" || it.path in tree) }
        history += ChatMessage.Notice("Plan eleştirmeni ${v.issues.size} not" + (if (add.isNotEmpty()) ", ${add.size} dosya ekledi" else ""),
            (v.issues.map { "- $it" } + add.map { "+ ${it.path}: ${it.instructions}" }).joinToString("\n"))
        ui.update()
        val notes = (plan.notes + "\nReviewer concerns to address while implementing:\n" + v.issues.joinToString("\n") { "- $it" }).trim()
        return plan.copy(notes = notes, files = plan.files + add)
    }








    /** Queue runs: nobody is watching, so nothing is installed or asked; confirmations are answered by the run's own rules. */
    var unattended = false




    /** Parallel reads: no per-call status flicker (one status line covers them). */
    private class NoStatusUi(private val ui: AgentUi) : AgentUi {
        override fun update() {}
        override fun status(text: String?) {}
        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String) = ui.confirm(title, body, okText, cancelText)
    }

    internal object NoUi : AgentUi {
        override fun update() {}
        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String) = false
    }


    fun customModes(): List<CustomMode> = CustomMode.list(settings.customModes)
    fun activeMode(): CustomMode? = settings.activeMode.takeIf { it.isNotBlank() }?.let { n -> customModes().firstOrNull { it.name == n } }

    private suspend fun runMode(history: MutableList<ChatMessage>, ui: AgentUi, mode: CustomMode, task: String = "") {
        val ref = ModelRef.parse(mode.model)?.takeIf { settings.keyFor(it.provider).isNotBlank() } ?: editorRef()
        history += ChatMessage.RouteInfo("Mod: ${mode.name} (${ref.model}, ${mode.access.label})", mode.instructions.take(400), "MODE", false)
        ui.update()
        // READ_ONLY bir model kimliği değil, çalışma fazıdır: yazma niyetli görevde salt okunur moda düşmeyiz.
        val writeIntent = ReadOnlyMismatchRecovery.hasWriteIntent(task)
        val readOnly = mode.access == CustomMode.Access.READ_ONLY && !writeIntent
        val access = if (mode.access == CustomMode.Access.READ_ONLY && !readOnly) CustomMode.Access.FULL else mode.access
        val specs = when (access) {
            CustomMode.Access.READ_ONLY -> tools.readOnlySpecs.filter { it.name != "submit_plan" }
            CustomMode.Access.NO_OPS -> tools.specs.filter { it.name !in setOf("github_create_pull_request", "github_merge_pull_request", "github_run_workflow") }
            CustomMode.Access.FULL -> tools.specs
        }
        val system = (if (readOnly) questionSystem() else agentSystem()) + "\nACTIVE MODE \"${mode.name}\" (follow it): ${mode.instructions}"
        agentLoop(history, ui, ref, "agent", system, "agent", specs, readOnly = readOnly, stopTool = null,
            maxOut = settings.maxOutAgent, thinking = settings.thinkingLevel, writeIntent = writeIntent, writeSpecs = tools.specs)
    }

    private val cache = GeminiCacheManager(settings)
    private var budgetApprovedUpTo = 0.0
    /** Frozen stable project context per kind ("agent" / "architect"); rebuilt only at checkpoints or after many commits. */
    private val snapshots = HashMap<String, String?>()
    private var commitsSinceSnapshot = 0
    private var flexDisabledForRun = false
    /** Whether the architect snapshot contains a repo map (limits architect reads). */
    var architectHasMap = false
        private set
    /** Approximate composition of the last main request, for the cost summary. */
    var lastAnatomy: String? = null
        private set
    private val router = SmartRouter(
        tools.github,
        listFiles = { repo, ref -> tools.filteredTree(repo, ref) },
        decide = { input, questions -> decisionChain().also { lastDecisionChain = it }.decide(input, questions) },
    )

    private var lastDecisionChain: DecisionChain? = null

    /** Decision providers to try, in order, from the setting and the keys present. */
    fun decisionProviders(): List<DecisionProvider> {
        val hasGemini = settings.geminiKey.isNotBlank()
        val hasOr = settings.openrouterKey.isNotBlank()
        return when (settings.decisionProvider) {
            "gemini" -> listOfNotNull(DecisionProvider.GEMINI.takeIf { hasGemini })
            "openrouter" -> listOfNotNull(DecisionProvider.OPENROUTER.takeIf { hasOr })
            else -> listOfNotNull(DecisionProvider.GEMINI.takeIf { hasGemini }, DecisionProvider.OPENROUTER.takeIf { hasOr })
        }
    }

    private fun decisionChain() = DecisionChain(decisionProviders().map { p ->
        val client = when (p) {
            DecisionProvider.GEMINI -> DecisionClient(settings.geminiKey, settings.decisionModel, settings.decisionTimeoutMs)
            DecisionProvider.OPENROUTER -> DecisionClient(settings.openrouterKey, settings.openrouterDecisionModel,
                settings.decisionTimeoutMs, DecisionProvider.OPENROUTER)
        }
        p.label to client::decide
    })

    init {
        tools.onRepoChanged = {
            context.invalidate()
            if (++commitsSinceSnapshot > SNAPSHOT_MAX_COMMITS) snapshots.clear()
        }
        tools.isInSnapshot = { path -> snapshots.values.any { it?.contains("### $path\n") == true || it?.contains("\n$path\n") == true } }
    }

    companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val SUMMARIZE_AFTER_CHARS = 24_000
        const val MAX_ARCHITECT_READS = 3
        /** The architect is called at most this many times per task: plan, or ask for ≤3 files and then plan. */
        const val MAX_ARCHITECT_CALLS = 2
        const val CLARIFY_MARK = "❓"
        /** Prefix of messages the "until green" controller adds in the user's place. */
        const val AUTO_NUDGE = "[Otomatik denetleyici] "
        const val MAX_FAILOVERS = 2
        /** Read-only tools that may run at the same time. */
        val PARALLEL_SAFE = setOf("github_read_file", "github_read_files", "github_get_tree", "github_search_code", "github_list_repos",
            "web_search", "web_fetch", "lookup_docs", "find_references", "ci_errors", "load_skill")
        /** Roles whose output cap is learned (adaptive budget). */
        val ADAPTIVE_ROLES = setOf("agent", "editor", "escalation", "architect")

        /** Overload, rate limit, server error, timeout or a dead stream: worth trying another model. */
        fun isTransient(e: Exception): Boolean {
            val m = e.message.orEmpty().lowercase()
            return Regex("\\((429|500|502|503|504|520|522|524|529)\\)").containsMatchIn(m) ||
                listOf("overload", "rate limit", "unavailable", "bağlantı hatası", "takıldı", "20 dakikayı aştı", "boş yanıt", "timeout").any { m.contains(it) }
        }
        /** Decision 1 difficulty (0 trivial … 3 very hard) below which the medium architect is used. */
        const val MEDIUM_COMPLEXITY = 1.5
        const val SNAPSHOT_MAX_COMMITS = 6
        const val STABLE_ACK = "Tamam, proje bağlamını aldım."

        fun roleLabel(role: String) = when (role) {
            "architect" -> "Mimar"
            "editor" -> "Uygulayıcı"
            "summary" -> "Özet"
            "cache" -> "Önbellek"
            "decision" -> "Karar"
            "router" -> "Yönlendirici"
            "escalation" -> "Yükseltme"
            "planner" -> "Planlayıcı"
            "reviewer" -> "İnceleyici"
            else -> "Ajan"
        }
    }

    /** Deletes the active explicit cache (stops storage billing). */
    suspend fun clearCache(): String = cache.clear(settings.geminiKey)

    val cacheStatus: String get() = cache.describe()

    fun resetSession() {
        budgetApprovedUpTo = 0.0
        cache.invalidate()
        context.invalidate()
        invalidateSnapshot()
    }

    /** Forces the stable context to be rebuilt on the next request (pinned files or repo changed). */
    fun invalidateSnapshot() {
        snapshots.clear()
        commitsSinceSnapshot = 0
    }

    // ---- Models ----

    fun singleRef(p: Provider = settings.provider) = modelOverride ?: ModelRef(p, settings.modelFor(p))
    /** Per-request override chosen from Decision 1's difficulty score (cleared after each run). */
    private var architectOverride: ModelRef? = null
    private var architectThinkingOverride: String? = null

    fun architectRef() = modelOverride ?: roleOverrides["architect"] ?: architectOverride ?: ModelRef.parse(settings.architectModel) ?: singleRef()
    fun architectThinking() = architectThinkingOverride ?: settings.thinkingArchitect
    fun editorRef() = modelOverride ?: roleOverrides["editor"] ?: ModelRef.parse(settings.editorModel) ?: singleRef()
    fun mainRef() = if (settings.architectMode) architectRef() else singleRef()

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

    private var orMetaRaw: String? = null
    private var orMeta: Map<String, OpenRouterModel> = emptyMap()

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

    // ---- Cost ----

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

    /**
     * The architect's whole turn, bounded: at most [MAX_ARCHITECT_CALLS] calls with the same system prompt and the same
     * tool list (so the prefix stays cacheable). Ask-for-files is served once from the repo; after that the architect must
     * plan, delegate or answer. It never gets a tool loop, write tools or repository operations.
     */
    suspend fun architectTurns(
        history: MutableList<ChatMessage>,
        ui: AgentUi,
        role: String,
        system: String,
        tools: List<ToolSpec>,
        submitName: String,
        validate: (ToolCall) -> String?,
    ): ArchOutcome {
        val stable = stableContext("architect", history, ui)
        var lastError = "Mimar plan göndermedi."
        for (step in 1..MAX_ARCHITECT_CALLS) {
            val final = step == MAX_ARCHITECT_CALLS
            val prepared = HistoryPruner.prepare(history, settings.keepTurns)
            val reply = callModel(history, prepared.messages, architectRef(), role, system, tools, ui,
                maxOut = settings.maxOutArchitect, thinking = architectThinking(), stable = stable)
            history += reply
            ui.update()
            if (reply.toolCalls.isEmpty()) return ArchOutcome.Answered
            val submit = reply.toolCalls.firstOrNull { it.name == submitName }
            val delegate = reply.toolCalls.firstOrNull { it.name == "delegate_to_executor" }
            val chosen = submit?.takeIf { validate(it) == null } ?: delegate
            // Every call needs an answer before the next request; the chosen one is answered by the caller.
            for (c in reply.toolCalls) {
                if (c === chosen) continue
                val text = when {
                    c === submit -> "Plan kullanılamadı: ${validate(c)}".also { lastError = it }
                    c.name == "need_more_info" && !final -> {
                        val repo = context.repo.takeIf { it.isNotBlank() }?.let { this.tools.normalizeRepo(it) }
                        val paths = com.suarez.coder.tools.Args.stringList(c.args, "paths").orEmpty().take(MAX_ARCHITECT_READS)
                        val files = if (repo != null && paths.isNotEmpty()) {
                            runCatching { this.tools.readFilesText(repo, paths, context.ref) }.getOrElse { "(okunamadı: ${it.message})" }
                        } else "(dosya yolu verilmedi)"
                        "İstenen dosyalar aşağıda. Bu SON adım: şimdi $submitName veya delegate_to_executor çağır ya da metinle cevap ver.\n\n$files"
                    }
                    else -> "Bu adımda kullanılamaz: ${c.name}. $submitName veya delegate_to_executor çağır.".also { lastError = it }
                }
                history += ChatMessage.ToolResult(c.id, c.name, text, isError = c === submit || c.name != "need_more_info")
            }
            ui.update()
            when {
                chosen != null && chosen === delegate -> return ArchOutcome.Delegated(delegate,
                    delegate.args.optString("instructions").ifBlank { "Kullanıcının isteğini uygula." })
                chosen != null -> return ArchOutcome.Submitted(chosen)
            }
        }
        return ArchOutcome.Unusable(lastError)
    }

    /** Runs a delegated task with the executor (full tools); the architect is not involved any more. */
    suspend fun runDelegated(history: MutableList<ChatMessage>, ui: AgentUi, call: ToolCall, instructions: String) {
        history += ChatMessage.ToolResult(call.id, call.name, "Uygulayıcı'ya devredildi.")
        history += ChatMessage.RouteInfo("Mimar → Uygulayıcı'ya devretti (${editorRef().model})", instructions.take(600), Route.SIMPLE_EDIT.name, false)
        ui.update()
        agentLoop(history, ui, editorRef(), "agent", agentSystem(), "agent", tools.specs, readOnly = false, stopTool = null,
            maxOut = settings.maxOutAgent, thinking = settings.thinkingLevel)
    }

    // ---- Self-improvement manager hooks ----

    /** Per-role models for the current run (learned choice) or for a whole exam run (the variant under test). */
    var roleOverrides: Map<String, ModelRef> = emptyMap()
    /** Picks models for a route ("role" -> model); null/empty = keep the user's models. */
    /** Set by the app: prompt sections adopted by self-improvement (PromptSections ids). */
    var promptOverrides: (() -> Map<String, String>)? = null
    var modelPicker: ((route: String, ctx: DoubleArray?, paths: List<String>, intent: String) -> com.suarez.coder.evolution.SelfManager.Pick?)? = null
    /** Context and propensities of the last learned model choice (recorded in the run's trace). */
    var lastCtx: DoubleArray? = null
    var lastProp: String? = null
    private val langCache = HashMap<String, DoubleArray>()

    /** The task context for a contextual model choice: difficulty, files involved, the repo's languages. */
    private suspend fun routeContext(files: Int): DoubleArray? {
        val repo = context.repo.takeIf { it.isNotBlank() }?.let { tools.normalizeRepo(it) } ?: return null
        val langs = langCache[repo] ?: runCatching { tools.filteredTree(repo, context.ref).map { it.path } }.getOrNull()
            ?.let { com.suarez.coder.evolution.RouteContext.langShares(it) }?.also { langCache[repo] = it } ?: return null
        return com.suarez.coder.evolution.RouteContext.features(lastComplexity, files, langs)
    }
    /**
     * Implementer-only tactic for the current session (strategy A/B). Null on the control arm.
     * Never applied to the architect prompt.
     */
    var implementerTactic: String? = null
    /** What the last run used, for its trace. */
    var lastArm: String? = null
    var lastRoute: String? = null
    /** Working rules adopted by the manager (appended to every system prompt). */
    var policyText: (() -> String?)? = null

    // ---- Self-evolution hooks ----

    /** Runs of the evolution engine: never recorded as traces, so they can never start another evolution. */
    var isEvolutionSession = false
    /** Red-then-green CI lessons for the active repo. Null outside the app shell. */
    var ciLessons: com.suarez.coder.evolution.CiLessonLearner? = null
    /** Evolution runs use one isolated model for every role, independent of the chat models. */
    var modelOverride: ModelRef? = null
    /** Called with the cost of every model call (evolution budget accounting). */
    var onUsage: ((Double) -> Unit)? = null
    /** Called before every model call; may throw [StopException] (evolution daily budget). */
    var beforeCall: (() -> Unit)? = null
    /** Locally learned lessons for a repo, added to the stable context (rarely changes, so it stays cacheable). */
    var lessonsFor: ((String) -> String?)? = null
    /** Routing parameters tuned by evolution tier 2. */
    var adaptiveRouting: (() -> com.suarez.coder.evolution.AdaptiveRouting)? = null

    private fun mediumThreshold() = adaptiveRouting?.invoke()?.complexityThreshold ?: MEDIUM_COMPLEXITY

    /** Editor models that repeatedly fail switch to the escalation model earlier (tier-2 note). */
    fun earlyEscalation(model: String) = adaptiveRouting?.invoke()?.earlyEscalation(model) == true

    /** Orchestrator: the budget is checked between subtasks (and the run paused) instead of asking mid-task. */
    var budgetDeferred = false

    fun budgetExceeded(history: List<ChatMessage>): Boolean {
        val budget = settings.budgetUsd
        return budget > 0 && sessionCost(history) >= maxOf(budget, budgetApprovedUpTo)
    }

    /** Resuming a plan paused at the budget counts as approving one more budget step. */
    fun approveBudget(history: List<ChatMessage>) { budgetApprovedUpTo = sessionCost(history) + settings.budgetUsd }

    var orchestratorHooks: OrchestratorHooks? = null

    private fun orchestratorMode() = OrchestratorMode.of(settings.orchestratorMode)

    /** Architect route: the normal two-phase flow, or the orchestrator the user picked. */
    private suspend fun architectFlow(history: MutableList<ChatMessage>, ui: AgentUi) {
        val mode = orchestratorMode()
        val hooks = orchestratorHooks
        if (mode == OrchestratorMode.OFF || hooks == null) { ArchitectEditor(this).run(history, ui); return }
        val plan = OrchestratorPlanner(this).plan(history, ui, mode) ?: return
        hooks.save(plan)
        history += ChatMessage.Notice("🗂 ${mode.label} plan: ${plan.tasks.size} alt görev → ${plan.repo}@${plan.branch}",
            plan.tasks.joinToString("\n\n") { t -> "${t.id}. ${t.title} [${t.ownedFiles.joinToString()}]" +
                (if (t.dependsOn.isNotEmpty()) " ← ${t.dependsOn.joinToString()}" else "") + "\n${t.description}" })
        ui.update()
        if (!settings.autoApprove) {
            history += ChatMessage.Assistant("🗂 Plan hazır. Aşağıdaki kartta inceleyip **Planı Başlat**'a dokunun (veya düzenleyin / iptal edin).", model = "coder")
            return
        }
        runPlanSteps(history, ui, plan)
    }

    /** Starts or resumes a saved plan (the card's start / resume buttons). */
    suspend fun executePlan(history: MutableList<ChatMessage>, ui: AgentUi, plan: OrchestratorPlan) {
        flexDisabledForRun = false
        val start = history.size
        try {
            history += ChatMessage.Notice("▶️ Plan ${if (plan.state == PlanState.AWAITING_APPROVAL) "başlatıldı" else "devam ediyor"}: ${plan.tasks.count { it.status != TaskStatus.DONE }} alt görev kaldı")
            ui.update()
            runPlanSteps(history, ui, plan)
        } finally {
            usageSummary(history, start)?.let { history += it }
            ui.update()
        }
    }

    private suspend fun runPlanSteps(history: MutableList<ChatMessage>, ui: AgentUi, plan0: OrchestratorPlan) {
        val hooks = orchestratorHooks ?: return
        hooks.clearPause()
        if (budgetExceeded(history)) approveBudget(history)
        // Failed or interrupted subtasks go back to the queue (their error is kept as context for the retry).
        val plan = plan0.copy(tasks = plan0.tasks.map { if (it.status == TaskStatus.FAILED || it.status == TaskStatus.RUNNING) it.copy(status = TaskStatus.PENDING) else it })
        val dag = if (plan.mode == OrchestratorMode.CONTRACT_DAG) ContractDagRunner(this, hooks) else null
        val runner = if (plan.mode == OrchestratorMode.FULL_MULTI_AGENT) MultiAgentOrchestrator(this, hooks) else null
        val seq = if (runner == null && dag == null) SequentialOrchestrator(this, hooks) else null
        try {
            when {
                dag != null -> dag.run(history, ui, plan)
                runner != null -> runner.run(history, ui, plan)
                else -> seq!!.run(history, ui, plan)
            }
        } catch (e: Throwable) {
            // Stopped or crashed mid-step: keep the plan resumable.
            val last = dag?.current ?: runner?.current ?: seq?.current ?: plan
            if (!last.state.finished) hooks.save(last.copy(state = PlanState.PAUSED, note = "Durduruldu: ${e.message ?: e.javaClass.simpleName}".take(200),
                tasks = last.tasks.map { if (it.status == TaskStatus.RUNNING) it.copy(status = TaskStatus.PENDING) else it }))
            throw e
        }
    }

    private suspend fun checkBudget(history: List<ChatMessage>, ui: AgentUi) {
        if (budgetDeferred) return
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









    // ---- Stable project context ----

    /**
     * Notes, repo map (architect) and pinned files as one frozen block, sent right after the system prompt so that every
     * request in a session starts with the same content. Our own later commits appear in the conversation instead.
     */
    suspend fun stableContext(kind: String, history: MutableList<ChatMessage>, ui: AgentUi): String? {
        if (snapshots.containsKey(kind)) return snapshots[kind]
        ui.status(if (kind == "architect") "Proje bağlamı ve repo haritası hazırlanıyor" else "Proje bağlamı hazırlanıyor")
        val parts = ArrayList<String>()
        if (settings.projectNotes) context.loadNotes()?.let { parts += "PROJECT NOTES (.coder/NOTES.md):\n$it" }
        context.repo.takeIf { it.isNotBlank() }?.let { r -> lessonsFor?.invoke(tools.normalizeRepo(r))?.takeIf { it.isNotBlank() } }
            ?.let { parts += "LEARNED LESSONS (Coder's memory for this repo; follow them):\n$it" }
        var hasMap = false
        if (kind == "architect") repoMapFor(history, ui)?.let { parts += "REPO MAP (paths, types, signatures; bodies removed):\n$it"; hasMap = true }
        context.load()?.let { parts += it.text }
        if (settings.docsLookup) runCatching { requestContext.dependencies() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            parts += "DEPENDENCIES (versions the project uses; write code for these, check unsure APIs with lookup_docs):\n" + it.joinToString(", ")
        }
        if (kind == "architect") architectHasMap = hasMap
        // No date in the prefix: an identical snapshot stays cacheable across days.
        val text = if (parts.isEmpty()) null else
            "STABLE PROJECT CONTEXT (snapshot; changes made after it appear later in the conversation).\n\n" + parts.joinToString("\n\n")
        snapshots[kind] = text
        commitsSinceSnapshot = 0
        return text
    }

    /** Repo map for the architect, or null when disabled/unavailable (the architect then reads files with tools). */
    private suspend fun repoMapFor(history: MutableList<ChatMessage>, ui: AgentUi): String? {
        if (!settings.repoMapForArchitect) return null
        val repo = context.repo.takeIf { it.isNotBlank() }?.let { tools.normalizeRepo(it) } ?: return null
        return try {
            val r = kotlinx.coroutines.withTimeout(45_000) { repoMaps.get(repo, context.ref) }
            history += ChatMessage.Notice(
                "🗺 Repo haritası: ${r.files} dosya, ~${Pricing.formatTokens((r.map.length / 4).toLong())} token" +
                    (if (r.fromCache) " (önbellekten, ${r.sha.take(7)})" else " (oluşturuldu, ${r.sha.take(7)})"),
                r.map.take(20_000),
            )
            ui.update()
            r.map
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            history += ChatMessage.Notice("Repo haritası zamanında hazırlanamadı; Mimar dosyaları araçlarla okuyacak.")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            history += ChatMessage.Notice("Repo haritası oluşturulamadı (${e.message?.take(100)}); Mimar dosyaları araçlarla okuyacak.")
            null
        }
    }

    // ---- Model calls ----

    /**
     * One API call with budget check, cache handling, Flex tier and usage bookkeeping.
     * [stable] is sent as the first user message (followed by a short model acknowledgement) so it forms a cacheable prefix.
     */
    suspend fun callModel(
        history: MutableList<ChatMessage>,
        messages: List<ChatMessage>,
        ref: ModelRef,
        role: String,
        system: String,
        toolSpecs: List<ToolSpec>,
        ui: AgentUi,
        useCache: Boolean = true,
        maxOut: Int? = null,
        thinking: String? = null,
        stable: String? = null,
        jsonMode: Boolean = false,
        temperature: Double? = null,
    ): ChatMessage.Assistant {
        var cur = ref
        val tried = HashSet<String>()
        while (true) {
            tried += cur.key
            val t0 = System.currentTimeMillis()
            val mark = history.size
            fun spent() = history.drop(mark).filterIsInstance<ChatMessage.UsageRecord>().sumOf { costOf(it) }
            try {
                return callModelOn(history, messages, cur, role, system, toolSpecs, ui, useCache, maxOut, thinking, stable, jsonMode, temperature)
                    .also { span(Span.MODEL, "${cur.model} ($role)", t0, spent()) }
            } catch (e: com.suarez.coder.llm.LlmException) {
                span(Span.MODEL, "${cur.model} ($role)", t0, spent(), ok = false)
                // Provider failover: an overloaded or failing model hands the same call to the next model in the user's chain.
                val next = if (settings.failover && isTransient(e) && !isEvolutionSession && modelOverride == null && role !in setOf("router", "decision"))
                    (escalationChain() + listOfNotNull(escalationRef())).firstOrNull { it.key !in tried && settings.keyFor(it.provider).isNotBlank() }
                else null
                if (next == null || tried.size > MAX_FAILOVERS) throw e
                history += ChatMessage.Notice("${cur.model} yanıt vermedi; aynı istek ${next.model} ile tekrarlanıyor", e.message?.take(300))
                ui.update()
                cur = next
            }
        }
    }

    /** Set by the app: every timed step of a run (model calls, tools, CI waits, cloud runs) for the timeline. */
    var onSpan: ((Span) -> Unit)? = null

    internal fun span(kind: String, name: String, start: Long, cost: Double = 0.0, ok: Boolean = true) {
        runCatching { onSpan?.invoke(Span(kind, name, start, System.currentTimeMillis(), cost, ok)) }
    }

    /** The output cap for a role: learned from real answers (adaptive budget) or the configured value. */
    fun learnedMaxOut(role: String, configured: Int?): Int? {
        if (configured == null || !settings.adaptiveBudget || role !in ADAPTIVE_ROLES) return configured
        return outputStats?.cap(role, configured) ?: configured
    }

    private val outputStats: com.suarez.coder.cost.OutputStats? = filesDir?.let { com.suarez.coder.cost.OutputStats(java.io.File(it, "outstats.json")) }

    private suspend fun callModelOn(
        history: MutableList<ChatMessage>,
        messages: List<ChatMessage>,
        ref: ModelRef,
        role: String,
        system: String,
        toolSpecs: List<ToolSpec>,
        ui: AgentUi,
        useCache: Boolean,
        maxOutConfigured: Int?,
        thinking: String?,
        stable: String?,
        jsonMode: Boolean,
        temperature: Double? = null,
    ): ChatMessage.Assistant {
        val maxOut = learnedMaxOut(role, maxOutConfigured)
        beforeCall?.invoke()
        checkBudget(history, ui)
        val client = client(ref)
        val prefix = stable?.let { listOf(ChatMessage.User(it), ChatMessage.Assistant(STABLE_ACK)) } ?: emptyList()
        val full = prefix + messages
        // Background work (self-improvement, queue) may wait: it uses Flex (half price) when enabled.
        val background = settings.backgroundFlex && (isEvolutionSession || unattended)
        val flex = (settings.economyMode || background) && ref.provider == Provider.GEMINI && role != "router" && !flexDisabledForRun
        var opts = CallOptions(
            thinkingLevel = thinking?.takeIf { it != "minimal" },
            maxOutputTokens = maxOut,
            minimalThinking = thinking == "minimal",
            serviceTier = if (flex) "flex" else null,
            jsonMode = jsonMode,
            temperature = temperature,
        )
        var send = full
        if (useCache && client is GeminiClient) {
            cache.prepare(client, system, toolSpecs, prefix)?.let { p ->
                opts = opts.copy(cachedContent = p.name)
                send = full.drop(p.prefixCount)
                if (p.createdOrExtended) {
                    history += ChatMessage.UsageRecord(ref.model, "cache", Usage(),
                        Pricing.storageCost(p.tokens, p.ttlMinutes.toDouble(), price(ref.model)), cache.lastStatus)
                }
            }
        }
        if (stable != null || role == "agent" || role == "architect") {
            fun tok(chars: Int) = Pricing.formatTokens((chars / 4).toLong())
            lastAnatomy = "kurallar ~${tok(system.length)} · araçlar ~${tok(toolSpecs.sumOf { it.description.length + it.parameters.toString().length })}" +
                " · sabit bağlam ~${tok(stable?.length ?: 0)} · konuşma ~${tok(HistoryPruner.charSize(messages))} token"
        }

        suspend fun once(o: CallOptions): com.suarez.coder.llm.LlmResponse {
            return try {
                client.complete(system, if (o.cachedContent != null) send else full, toolSpecs, o)
            } catch (e: CacheRejectedException) {
                cache.invalidate()
                client.complete(system, full, toolSpecs, o.copy(cachedContent = null))
            } catch (e: GeminiClient.FlexUnavailableException) {
                val ok = (isEvolutionSession || unattended) || ui.confirm(
                    "Flex kullanılamıyor",
                    "${e.message}\nFlex (%50 indirimli) kendiliğinden standart fiyata geçmez. Bu çalıştırma standart fiyatla devam etsin mi?",
                    "Standart fiyatla devam", "Durdur",
                )
                if (!ok) throw StopException("Flex kapasitesi yok; durduruldu.")
                flexDisabledForRun = true
                client.complete(system, if (o.cachedContent != null) send else full, toolSpecs, o.copy(serviceTier = null))
            }
        }

        fun record(resp: com.suarez.coder.llm.LlmResponse, o: CallOptions) {
            val servedFlex = resp.serviceTier?.contains("flex") ?: (o.serviceTier == "flex" && !flexDisabledForRun)
            history += ChatMessage.UsageRecord(ref.model, role, resp.usage,
                label = if (servedFlex) "flex" else "", discount = if (servedFlex) 0.5 else 1.0, actualUsd = resp.costUsd)
            if (!resp.truncated && role in ADAPTIVE_ROLES) runCatching { outputStats?.add(role, resp.usage.output + resp.usage.thoughts) }
            onUsage?.invoke(costOf(history.last() as ChatMessage.UsageRecord))
            if (client is GeminiClient) cache.recordUsage(ref.model, resp.usage, o.cachedContent != null)
        }

        suspend fun timed(o0: CallOptions): com.suarez.coder.llm.LlmResponse {
            val tier = if (o0.serviceTier == "flex") " · Flex (yavaş olabilir)" else ""
            val think = o0.thinkingLevel?.let { " · düşünme $it" } ?: if (o0.minimalThinking) " · düşünme minimal" else ""
            val base = "${ref.model} yanıtı bekleniyor ($role)$think$tier"
            ui.status(base)
            // Timeout per hop: Flex may queue for minutes; long-decode hops may take up to 10 minutes each.
            var hopNo = 1
            // Streaming: the hop may run long while data keeps coming; it is "stuck" only when nothing arrives for the stall time.
            val thinksHard = o0.thinkingLevel in setOf("medium", "high", "xhigh")
            val stallMs = if (thinksHard) maxOf(settings.requestTimeoutSec * 1000L, 240_000L) else settings.requestTimeoutSec * 1000L
            val o = o0.copy(
                hopTimeoutMs = 20 * 60_000L,
                stallTimeoutMs = stallMs,
                onHop = { n -> hopNo = n; ui.status("$base · uzun yanıt, parça $n") },
                onProgress = { p ->
                    val parts = ArrayList<String>()
                    if (hopNo > 1) parts += "parça $hopNo"
                    if (p.lastThought.isNotBlank() && p.textChars == 0 && p.toolCalls == 0) parts += "düşünüyor: “${p.lastThought.take(60)}”"
                    else if (p.thoughtChars > 0 && p.textChars == 0 && p.toolCalls == 0) parts += "düşünüyor"
                    if (p.textChars > 0) parts += "yazıyor ~${Pricing.formatTokens((p.textChars / 4).toLong())} token"
                    if (p.toolCalls > 0) parts += "${p.toolCalls} araç çağrısı"
                    ui.status("$base · " + parts.joinToString(" · "))
                },
            )
            return try {
                once(o)
            } catch (e: GeminiClient.StallException) {
                throw com.suarez.coder.llm.LlmException(
                    "${ref.model}: ${e.message}; istek takıldı. Tekrar deneyin; sürerse düşünme seviyesini düşürün veya başka model seçin."
                )
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw com.suarez.coder.llm.LlmException("${ref.model}: tek parça 20 dakikayı aştı; istek durduruldu.")
            } finally {
                ui.status(null)
            }
        }

        var resp = timed(opts)
        record(resp, opts)
        if (resp.truncated && maxOut != null && maxOut >= 64) {
            // A cut-off answer is useless (and a cut-off diff is dangerous): retry once with a larger cap.
            val bigger = minOf(maxOf(maxOut * 2, maxOutConfigured ?: 0), Settings.MAX_OUTPUT)
            history += ChatMessage.Notice("✂️ ${roleLabel(role)} yanıtı çıktı sınırına ($maxOut token) takıldı; $bigger ile bir kez yeniden deneniyor.")
            ui.update()
            val o2 = opts.copy(maxOutputTokens = bigger)
            resp = timed(o2)
            record(resp, o2)
            if (resp.truncated) {
                history += ChatMessage.Notice("⚠️ Yanıt $bigger tokenda yine kesildi. Ayarlar'dan çıktı sınırını artırın veya düşünme seviyesini düşürün.", isError = true)
            }
        }
        return resp.message
    }

    // ---- Entry point ----

    /** [override] forces a route for this request (e.g. the user re-runs it in Architect mode). */
    suspend fun run(history: MutableList<ChatMessage>, ui: AgentUi, override: Route? = null) {
        flexDisabledForRun = false
        lastArm = null
        lastRoute = null
        tools.oscillation.reset()
        // Repo skills (.coder/skills/*/SKILL.md), cached for minutes so the catalog line stays stable.
        tools.skills?.let { s -> runCatching { kotlinx.coroutines.withTimeout(8_000) { s.refreshRepo(tools.activeRepo()) } } }
        architectOverride = null
        architectThinkingOverride = null
        val start = history.size
        liveHistory = history
        liveUi = ui
        lastComplexity = null
        if (settings.quickCheck && settings.quickCheckWarm && !unattended && !isEvolutionSession) guards.prewarmQuickCheck(tools.activeRepo())
        // Overlap: the tree, the repo map and the dependency versions load while routing decides (all cached afterwards).
        val warm = if (settings.pipelining && !isEvolutionSession) kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).launch {
            val repo = context.repo.takeIf { it.isNotBlank() }?.let { tools.normalizeRepo(it) } ?: return@launch
            runCatching { tools.filteredTree(repo, context.ref) }
            if (settings.riskModel) runCatching { foresight.labelPending() }
            if (settings.docsLookup) runCatching { requestContext.dependencies() }
            if (settings.repoMapForArchitect && (settings.architectMode || settings.smartRouting)) runCatching { repoMaps.get(repo, context.ref) }
        } else null
        guards.startRun()
        try {
            val requestIndex = history.indexOfLast { it is ChatMessage.User }
            val request = history.getOrNull(requestIndex) as? ChatMessage.User
            // @-mentions and similar earlier tasks go right after the request (the volatile end, not the cached prefix).
            if (request != null && requestIndex >= 0 && !isEvolutionSession) {
                var at = requestIndex + 1
                requestContext.mentionContext(request.text)?.let { history.add(at++, it); ui.update() }
                val repoNow = tools.activeRepo()
                if (settings.similarTasks) similarFor?.let { f -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { f(repoNow, request.text) } }?.let {
                    history.add(at, ChatMessage.ContextBlock(it, "benzer geçmiş görevler"))
                }
            }
            val mode = if (override == null && !isEvolutionSession) activeMode() else null
            if (mode != null) { runMode(history, ui, mode, request?.text.orEmpty()); return }
            var route: Route? = override
            var auto: com.suarez.coder.context.ContextProvider.Loaded? = null
            var autoPaths: List<String> = emptyList()
            if (override != null) {
                history += ChatMessage.RouteInfo("↻ Kullanıcı kararı geçersiz kıldı → Mimar Modu (${architectRef().model} + ${editorRef().model})",
                    null, override.name, false)
                ui.update()
            } else if (settings.smartRouting && request != null) {
                // Tier 1: free local rules.
                val local = LocalRouter.classify(request.text, settings.contextFiles.size,
                    adaptiveRouting?.invoke()?.localHints.orEmpty().map { it.pattern to it.route })
                if (local != null) {
                    route = local.route
                    history += ChatMessage.RouteInfo("Yerel Kural → ${modeName(local.route)} • $0", "Gerekçe: ${local.reason}",
                        local.route.name, local.route != Route.COMPLEX_ARCHITECT)
                } else {
                    var decisionError: String? = null
                    if (settings.useDecision1 && decisionProviders().isNotEmpty()) {
                        val repo = context.repo.takeIf { it.isNotBlank() }?.let { tools.normalizeRepo(it) }
                        val d = router.route(request.text, recentContext(history), repo, context.ref, context.hasManualFiles)
                        if (d.fallbackReason == null) {
                            history += ChatMessage.UsageRecord(d.decisionModel ?: settings.decisionModel, "decision",
                                Usage(input = d.inputTokens), actualUsd = d.costUsd)
                            lastDecisionChain?.skipped?.takeIf { it.isNotEmpty() }?.let { s ->
                                history += ChatMessage.Notice("↪️ Yedek karar sağlayıcısı kullanıldı", s.joinToString("\n"))
                            }
                            route = d.route
                            lastComplexity = d.complexity
                            // A vague change request: ask 1-3 cheap questions instead of planning the wrong thing.
                            if (needsClarification(d, history)) {
                                history += routeInfo(d)
                                askClarification(history, ui, request.text, d.clarity!!)
                                return
                            }
                            // Moderate work does not need the most expensive architect or deep thinking.
                            val medium = ModelRef.parse(settings.architectModelMedium)
                            if (d.route == Route.COMPLEX_ARCHITECT && d.complexity != null && d.complexity < mediumThreshold() &&
                                medium != null && medium.key != (ModelRef.parse(settings.architectModel)?.key)
                            ) {
                                architectOverride = medium
                                architectThinkingOverride = "low"
                            }
                            if (d.selected.isNotEmpty() && d.repo != null) {
                                autoPaths = d.selected.map { it.path }
                                auto = context.loadAuto(d.repo, d.ref, autoPaths)
                            }
                            history += routeInfo(d)
                        } else decisionError = d.fallbackReason
                    }
                    if (route == null) {
                        // Tier 2: one-word answer from the cheapest suitable model.
                        val cheap = cheapRoute(history, request.text, ui)
                        val why = decisionError?.let { "Decision 1 kullanılamadı: ${it.take(100)}\n" } ?: ""
                        if (cheap != null) {
                            route = cheap.first
                            history += ChatMessage.RouteInfo("${cheap.second.model} → ${modeName(cheap.first)} • ${Pricing.formatUsd(cheap.third)}",
                                why + "Tek kelimelik sınıflandırma (en fazla 10 çıktı tokenı).", cheap.first.name, cheap.first != Route.COMPLEX_ARCHITECT)
                        } else {
                            history += ChatMessage.RouteInfo("Otomatik yönlendirme yapılamadı → manuel seçim: ${manualModeLabel()}",
                                why.ifBlank { null }, "MANUAL", !settings.architectMode)
                        }
                    }
                }
                ui.update()
            }
            // Request-specific files go into the conversation (the volatile end), not the stable prefix.
            // The (expensive) architect only gets their names: contents go to the (cheap) editor, which reads the planned files anyway.
            auto?.let {
                if (requestIndex < 0) return@let
                val block = if (route == Route.COMPLEX_ARCHITECT || (route == null && settings.architectMode)) {
                    ChatMessage.ContextBlock(
                        "Decision 1'e göre bu istekle ilgili dosyalar: ${autoPaths.joinToString(", ")}. Yapıları repo haritasında; " +
                            "içerikleri plan sonrası Uygulayıcı'ya verilir. Plan için gerçekten gerekirse oku.",
                        autoPaths.joinToString(", ") + " (yalnızca adlar)",
                    )
                } else ChatMessage.ContextBlock(it.text, autoPaths.joinToString(", "))
                history.add(requestIndex + 1, block)
            }
            // Scoped rules follow the files of this turn. They stay out of the cached system prefix.
            if (!isEvolutionSession && requestIndex >= 0) {
                val mentioned = Regex("[\\w./-]+\\.[A-Za-z0-9]{1,8}").findAll(request?.text.orEmpty()).map { it.value }.toList()
                val rulePaths = (autoPaths + settings.contextFiles + mentioned).map { it.trim().removePrefix("./") }.filter { it.isNotBlank() }.distinct()
                val repoForRules = context.repo.takeIf { it.isNotBlank() }?.let { tools.normalizeRepo(it) }
                if (!repoForRules.isNullOrBlank() && rulePaths.isNotEmpty()) {
                    context.matchingRulesBlock(repoForRules, rulePaths)?.let { rules ->
                        val at = (requestIndex + 1 until history.size).lastOrNull { history[it] is ChatMessage.ContextBlock }
                        if (at != null) {
                            val b = history[at] as ChatMessage.ContextBlock
                            history[at] = b.copy(text = b.text.trimEnd() + "\n\n" + rules)
                        } else history.add(requestIndex + 1, ChatMessage.ContextBlock(rules, "proje kuralları"))
                    }
                }
            }
            // Learned model choice for this run (the user's pool; otherwise their fixed models).
            lastRoute = (route ?: if (settings.architectMode) Route.COMPLEX_ARCHITECT else Route.SIMPLE_EDIT).name
            lastCtx = null; lastProp = null
            val ctx = if (modelPicker != null && settings.contextualRouting) routeContext(maxOf(autoPaths.size, settings.contextFiles.size)) else null
            val intent = request?.text.orEmpty()
            modelPicker?.invoke(lastRoute!!, ctx, autoPaths.ifEmpty { settings.contextFiles }, intent)?.takeIf { it.models.isNotEmpty() }?.let { pick ->
                val picks = pick.models
                lastCtx = pick.ctx; lastProp = pick.prop
                roleOverrides = picks
                lastArm = picks.entries.joinToString(";") { "${lastRoute}|${it.key}|${it.value.key}" }
                history += ChatMessage.Notice("🎯 Yönetici seçimi: " + picks.entries.joinToString(", ") { "${roleLabel(it.key)} ${it.value.model}" },
                    "Öğrenen model seçimi: geçmiş başarı ve maliyete göre seçildi (arada bir alternatif denenir).")
                ui.update()
            }
            val questionSpecs = tools.readOnlySpecs.filter { it.name != "submit_plan" }
            when (route) {
                Route.COMPLEX_ARCHITECT -> architectFlow(history, ui)
                Route.SIMPLE_EDIT ->
                    agentLoop(history, ui, editorRef(), "agent", agentSystem(), "agent", tools.specs, readOnly = false, stopTool = null,
                        maxOut = settings.maxOutAgent, thinking = settings.thinkingLevel)
                Route.QUESTION_ONLY -> {
                    // Salt okunur yalnızca gerçek bir soru görevinde; yazma niyeti varsa READ_WRITE başla.
                    val ro = ReadOnlyMismatchRecovery.readOnlyForRoute(route, request?.text.orEmpty())
                    agentLoop(history, ui, editorRef(), "agent", if (ro) questionSystem() else agentSystem(), "agent",
                        if (ro) questionSpecs else tools.specs, readOnly = ro, stopTool = null,
                        maxOut = settings.maxOutAgent, thinking = settings.thinkingLevel,
                        writeIntent = ReadOnlyMismatchRecovery.hasWriteIntent(request?.text.orEmpty()), writeSpecs = tools.specs)
                }
                null ->
                    if (settings.architectMode) architectFlow(history, ui)
                    else agentLoop(history, ui, singleRef(), "agent", agentSystem(), "agent", tools.specs, readOnly = false, stopTool = null,
                        maxOut = settings.maxOutAgent, thinking = settings.thinkingLevel)
            }
        } finally {
            warm?.cancel()
            liveHistory = null
            liveUi = null
            usageSummary(history, start)?.let { history += it }
            architectOverride = null
            architectThinkingOverride = null
            roleOverrides = emptyMap()
            ui.update()
        }
    }

    /** True for a vague change request when the user has not just answered our questions. */
    fun needsClarification(d: RoutingDecision, history: List<ChatMessage>): Boolean {
        if (!settings.clarifyFirst || isEvolutionSession || d.route == Route.QUESTION_ONLY) return false
        val clarity = d.clarity ?: return false
        if (clarity >= com.suarez.coder.decision.RouterLogic.CLARITY_MIN) return false
        val lastUser = history.indexOfLast { it is ChatMessage.User }
        val prev = history.subList(0, maxOf(lastUser, 0)).lastOrNull { it is ChatMessage.Assistant } as? ChatMessage.Assistant
        return prev?.text?.startsWith(CLARIFY_MARK) != true
    }

    /** One short, cheap call (the executor model): at most 3 questions, each with a sensible default. */
    private suspend fun askClarification(history: MutableList<ChatMessage>, ui: AgentUi, request: String, clarity: Double) {
        val system = "The user's request to a coding assistant is too vague to implement well. Ask at most 3 short, numbered questions " +
            "in the user's language whose answers would change what gets built (what, where, how it should behave). For each, offer a " +
            "sensible default in parentheses so the user can just say \"varsayılanlarla devam\". No preamble."
        val reply = callModel(history, listOf(ChatMessage.User(request.take(3_000) + (recentContext(history)?.let { "\n\nPrevious exchange:\n$it" } ?: ""))),
            editorRef(), "agent", system, emptyList(), ui, useCache = false, maxOut = 500, thinking = "low")
        history += ChatMessage.Assistant("$CLARIFY_MARK Başlamadan önce netleştirelim (istek netliği %${(clarity * 100).toInt()}):\n\n" + reply.text.trim(), model = reply.model)
        ui.update()
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

    private fun modeName(r: Route) = when (r) {
        Route.SIMPLE_EDIT -> "Hızlı Mod (${editorRef().model})"
        Route.QUESTION_ONLY -> "Soru Modu (${editorRef().model})"
        Route.COMPLEX_ARCHITECT -> "Mimar Modu (${architectRef().model} + ${editorRef().model})"
    }

    /**
     * Middle rung of the editor ladder: the configured model, or the cheapest known model priced between the editor and
     * the architect (e.g. a Flash model between Flash-Lite and Neon). Null when none fits or set to "none".
     */
    fun escalationRef(): ModelRef? {
        if (modelOverride != null) return null
        if (settings.escalationModel.equals("none", true)) return null
        ModelRef.parse(settings.escalationModel)?.let { return it }
        val lo = Pricing.blended(price(editorRef().model))
        val hi = Pricing.blended(price(architectRef().model))
        val skip = Regex("tts|image|live|decision|embedding|transcribe|robotics|omni|banana", RegexOption.IGNORE_CASE)
        val candidates = (settings.modelCatalog + listOf("gemini/gemini-3.8-flash", "gemini/gemini-2.5-flash"))
            .distinct().mapNotNull { ModelRef.parse(it) }
            .filter { !skip.containsMatchIn(it.model) }
            .filter { settings.keyFor(it.provider).isNotBlank() }
            .filter { it.key != editorRef().key && it.key != architectRef().key }
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

    /** Returns (route, model, cost) or null when the classifier is unavailable or unsure. */
    private suspend fun cheapRoute(history: MutableList<ChatMessage>, request: String, ui: AgentUi): Triple<Route, ModelRef, Double>? {
        val ref = routerRef() ?: return null
        val system = "You classify requests sent to a coding assistant that edits a GitHub repository. Reply with exactly one word: " +
            "SIMPLE (single-file bug fix, UI/style/text change, small refactor, clearly scoped change, debugging a reported error, " +
            "or a GitHub operation), COMPLEX (only a new feature or structural change across several files that needs design) " +
            "or QUESTION (no code change needed). When unsure between SIMPLE and COMPLEX, answer SIMPLE."
        val prompt = (recentContext(history)?.let { "Previous exchange:\n$it\n\n" } ?: "") + "Request:\n" + request.take(2_000)
        val scratch = ArrayList<ChatMessage>()
        return try {
            val reply = kotlinx.coroutines.withTimeout(8_000) {
                callModel(scratch, listOf(ChatMessage.User(prompt)), ref, "router", system, emptyList(), ui,
                    useCache = false, maxOut = 10, thinking = "minimal")
            }
            val recs = scratch.filterIsInstance<ChatMessage.UsageRecord>()
            history.addAll(recs)
            LocalRouter.parseOneWord(reply.text)?.let { Triple(it, ref, recs.sumOf { r -> costOf(r) }) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: StopException) {
            throw e
        } catch (e: Exception) {
            null
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
                else "Mimar Modu (${architectRef().model} + ${editorRef().model}, ${pct(d.confidence)} güven" +
                    (d.complexity?.let { c -> ", zorluk ${"%.1f".format(c)}/3" } ?: "") + ")"
        }
        val files = when {
            d.manualFiles -> "📎 manuel dosyalar korundu"
            d.totalFiles > 0 -> "${d.selected.size}/${d.totalFiles} dosya seçildi"
            else -> null
        }
        val who = if (d.decisionModel?.contains('/') == true) d.decisionModel.substringAfterLast('/') else "Decision 1"
        val text = listOfNotNull("$who → $mode", files, "${d.latencyMs} ms").joinToString(" • ")
        val detail = buildString {
            if (d.routeProbabilities.isNotEmpty()) {
                append("Sınıflandırma olasılıkları:\n")
                d.routeProbabilities.entries.sortedByDescending { it.value }.forEach { append("  ${it.key}: ${pct(it.value)}\n") }
            }
            if (d.selected.isNotEmpty()) {
                append("\nBağlama eklenen dosyalar (evet olasılığı):\n")
                d.selected.forEach { append("  ${it.path}: ${pct(it.yes)}\n") }
            } else if (!d.manualFiles && d.totalFiles > 0) append("\nEşik (%60) üzerinde dosya yok; otomatik bağlam eklenmedi.\n")
            d.complexity?.let { c ->
                append("\nZorluk: ${"%.1f".format(c)} / 3")
                if (architectOverride != null) append(" → orta Mimar: ${architectOverride!!.model}, düşünme low")
                append('\n')
            }
            append("\nKarar çağrısı: ${d.inputTokens} giriş token, 0 çıkış token.")
        }
        return ChatMessage.RouteInfo(text, detail, d.route.name, d.route != Route.COMPLEX_ARCHITECT)
    }

    private fun usageSummary(history: List<ChatMessage>, from: Int): ChatMessage.Notice? {
        val recs = history.drop(from).filterIsInstance<ChatMessage.UsageRecord>()
        if (recs.isEmpty()) return null
        val overrides = Pricing.parseOverrides(settings.priceOverrides)
        fun estimated(model: String) = !Pricing.isKnown(model) && model !in openRouterModels() && overrides.keys.none { model.lowercase().contains(it) } &&
            recs.any { it.model == model && it.actualUsd == null }
        val parts = recs.groupBy { it.role to it.model }.map { (k, list) ->
            val (role, model) = k
            val u = list.fold(Usage()) { acc, r -> acc + r.usage }
            val c = list.sumOf { costOf(it) }
            val name = if (role in setOf("architect", "editor", "router", "planner", "reviewer")) "${roleLabel(role)} ($model)" else roleLabel(role)
            if (role == "cache") "$name ${Pricing.formatUsd(c)}"
            else "$name ${Pricing.formatTokens(u.input)}→${Pricing.formatTokens(u.output + u.thoughts)} ${Pricing.formatUsd(c)}" +
                if (estimated(model)) "*" else ""
        }
        val total = recs.sumOf { costOf(it) }
        val callRecs = recs.filter { it.role != "cache" && it.role != "decision" }
        val hit = cacheHitRate(callRecs)
        val flexCalls = recs.count { it.label == "flex" }
        val detail = (lastAnatomy?.let { "İstek anatomisi (son ana çağrı, yaklaşık): $it\n" } ?: "") +
            (hit?.let { "Önbellekten okunan giriş: %${(it * 100).roundToInt()} (${callRecs.sumOf { r -> r.usage.cached }} / ${callRecs.sumOf { r -> r.usage.input }} token)\n" } ?: "") +
            (if (flexCalls > 0) "Flex (%50 indirimli) çağrı: $flexCalls\n" else "") +
            "Önbellek durumu: ${cache.describe()}\n" + recs.groupBy { it.role to it.model }.entries.joinToString("\n") { (k, list) ->
            val u = list.fold(Usage()) { acc, r -> acc + r.usage }
            "${roleLabel(k.first)} (${k.second}), ${list.size} çağrı: giriş ${u.input} (önbellekten ${u.cached}), " +
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
            "  ${roleLabel(r.role)} adım $n (${r.model}): ${Pricing.formatTokens(u.input)} giriş (önbellek %$cachePct) → " +
                "${Pricing.formatTokens(u.output)} çıkış + ${Pricing.formatTokens(u.thoughts)} düşünme · ${Pricing.formatUsd(costOf(r))}" +
                if (r.label == "flex") " (Flex)" else ""
        }
        val detailWithSteps = if (steps.isBlank()) detail else "Adım adım:\n$steps\n\n$detail"
        return ChatMessage.Notice("💰 " + parts.joinToString(" · ") + " · Toplam ${money(total)}$hitText", detailWithSteps)
    }

    /** Folds compacted old turns into a short summary once they get large (only right after a checkpoint). */
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
            val reply = callModel(scratch, listOf(ChatMessage.User(prompt)), editorRef(), "summary", system, emptyList(), ui,
                useCache = false, maxOut = 1024, thinking = "minimal")
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
        stableKind: String?,
        toolSpecs: List<ToolSpec>,
        readOnly: Boolean,
        stopTool: String?,
        maxOut: Int? = null,
        maxReads: Int? = null,
        thinking: String? = null,
        allowOps: Boolean = false,
        writeIntent: Boolean = false,
        writeSpecs: List<ToolSpec>? = null,
    ): ToolCall? {
        var consecutiveFailures = 0
        var reads = 0
        var cur = ref
        val ci = CiController.CiState()
        // Yetki kurtarma: yazma niyetli görevde salt okunur yanıt gelirse oturumu bir kez READ_WRITE'a yükselt.
        var ro = readOnly
        var specs = toolSpecs
        var upgrades = 0
        fun tryUpgrade(why: String): Boolean {
            if (!ro || !writeIntent) return false
            val action = ReadOnlyMismatchRecovery.next(upgrades)
            if (action == ReadOnlyMismatchRecovery.Action.HANDOFF) return false
            upgrades++
            ro = false
            specs = writeSpecs ?: toolSpecs
            history += ChatMessage.User(ReadOnlyMismatchRecovery.upgradePrompt(), isHarnessInternal = true)
            history += ChatMessage.Notice(ReadOnlyMismatchRecovery.statusLine(action), why)
            ui.update()
            return true
        }
        // Adaptive budget: the executor starts with low thinking and goes back to the configured level once it struggles.
        val adaptiveThink = settings.adaptiveBudget && role == "agent" && !ro && thinking in setOf("medium", "high", "xhigh")
        var think = if (adaptiveThink) "low" else thinking
        fun raiseThinking(why: String) {
            if (think != thinking) { think = thinking; history += ChatMessage.Notice("Düşünme seviyesi $thinking'e yükseltildi ($why)") }
        }
        // Şeffaflık: salt okunur fazda başlayan görev zaman çizelgesine tek satırla kaydedilir.
        if (ro) {
            history += ChatMessage.Notice(ReadOnlyMismatchRecovery.phaseLine())
            ui.update()
        }
        for (step in 1..settings.maxSteps) {
            // Rare bulk compaction instead of re-shortening every step: the prefix changes once, then stays cacheable.
            HistoryPruner.checkpointIndex(history, settings.keepTurns, settings.compactTailChars)?.let { idx ->
                history.add(idx, ChatMessage.CompactMark)
                snapshots.clear()
                history += ChatMessage.Notice("🗜 Geçmiş toplu sıkıştırıldı; önbellek bu adımda bir kez yenilenecek.")
                maybeSummarize(history, ui)
                ui.update()
            }
            val stable = stableKind?.let { stableContext(it, history, ui) }
            val prepared = HistoryPruner.prepare(history, settings.keepTurns)
            val reply = callModel(history, prepared.messages, cur, role, system, specs, ui, maxOut = maxOut, thinking = think, stable = stable)
            history += reply
            ui.update()
            if (reply.toolCalls.isEmpty()) {
                // Yetki kurtarma: "yetkim yok" halüsinasyonu → yükselt ve aynı görevi bir kez daha dene.
                if (ReadOnlyMismatchRecovery.isMismatch(reply.text, writeIntent, ro)) {
                    if (tryUpgrade("model yazma yetkisi olmadığını söyledi")) continue
                    // Yükseltme tükendi: salt okunur oturumu kapat, görev yazma yetkili Uygulayıcı'ya devredilir.
                    history += ChatMessage.Notice(ReadOnlyMismatchRecovery.statusLine(ReadOnlyMismatchRecovery.Action.HANDOFF),
                        "Görev yazma gerektiriyor; bu salt okunur oturum burada bırakıldı.")
                    ui.update()
                    return null
                }
                // Until green: the model may not stop on a red CI (twice at most, then the user decides).
                if (settings.untilGreen && ci.lastFailed && !ro && !isEvolutionSession && ci.nudges < 2) {
                    ci.nudges++
                    history += ChatMessage.User(AUTO_NUDGE + "CI hâlâ kırmızı. Son hatayı düzelt, commit et ve ci_wait ile doğrula; " +
                        "düzeltilemiyorsa nedenini kısaca açıkla.")
                    ui.update()
                    continue
                }
                return null
            }

            var stopCall: ToolCall? = null
            val calls = reply.toolCalls
            // Several read-only calls in one step run at the same time (results stay in order).
            if (calls.size > 1 && calls.all { it.name in PARALLEL_SAFE } && maxReads == null) {
                ui.status("${calls.size} okuma aynı anda çalışıyor")
                val results = kotlinx.coroutines.coroutineScope {
                    calls.map { c -> async { executeTool(c, ro, allowOps, NoStatusUi(ui), role) } }.map { it.await() }
                }
                ui.status(null)
                results.forEach { (r, _) -> history += r }
                consecutiveFailures = if (results.all { it.first.isError }) consecutiveFailures + 1 else 0
                ui.update()
                continue
            }
            for ((i, call) in calls.withIndex()) {
                if (call.name == stopTool) { stopCall = call; continue }
                val readCount = if (call.name == "github_read_files") call.args.optJSONArray("paths")?.length() ?: 1 else 1
                if (maxReads != null && call.name in setOf("github_read_file", "github_read_files") && reads >= maxReads) {
                    history += ChatMessage.ToolResult(call.id, call.name,
                        "Okuma sınırına ($maxReads dosya) ulaşıldı. Repo haritası ve okuduğun dosyalarla planı şimdi submit_plan ile gönder.")
                    ui.update()
                    continue
                }
                if (call.name in setOf("github_read_file", "github_read_files")) reads += readCount
                var (result, rejected) = executeTool(call, ro, allowOps, ui, role)
                if (call.name == "ci_wait" && !result.isError) {
                    val (r2, next) = ciController.afterCi(call, result, ci, cur, history, ui)
                    result = r2
                    if (ci.lastFailed) raiseThinking("kırmızı CI")
                    if (next == null) {
                        history += result
                        calls.drop(i + 1).forEach { history += ChatMessage.ToolResult(it.id, it.name, "İptal edildi: kullanıcı durdurdu.", true) }
                        history += ChatMessage.Notice("Kullanıcı kırmızı CI'dan sonra durdurdu.")
                        return null
                    }
                    cur = next
                }
                if (ci.pendingSig != null && !result.isError && call.name in setOf("github_apply_diff", "github_write_files")) {
                    ci.fixes += (call.args.optString("message").ifBlank { "değişiklik" }) + " [" +
                        Regex("(?m)^([\\w./-]+\\.[\\w]+)\\s*$").findAll(call.args.optString("diff")).map { it.groupValues[1] }.distinct().take(4).joinToString() + "]"
                }
                history += result
                ui.update()
                // Araç salt okunur reddettiyse oturumu yükselt; kalan çağrılar bir sonraki adımda yeniden denenebilir.
                if (result.isError && ReadOnlyMismatchRecovery.isMismatch(result.content, writeIntent, ro)) {
                    calls.drop(i + 1).forEach { history += ChatMessage.ToolResult(it.id, it.name, "Yetki yükseltildi; sonraki adımda yeniden çağrılabilir.", true) }
                    if (tryUpgrade("araç yazmayı salt okunur gerekçesiyle reddetti")) break
                    history += ChatMessage.Notice(ReadOnlyMismatchRecovery.statusLine(ReadOnlyMismatchRecovery.Action.HANDOFF),
                        "Araç yazmayı reddetti; görev yazma yetkili Uygulayıcı'ya devredildi.")
                    ui.update()
                    return null
                }
                consecutiveFailures = if (result.isError && !rejected) consecutiveFailures + 1 else 0
                if (consecutiveFailures >= 2) raiseThinking("üst üste hata")
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && !isEvolutionSession) {
                    // Climb the user's model chain before asking.
                    Escalation.next(escalationChain(), cur)?.let { up ->
                        history += ChatMessage.Notice("$MAX_CONSECUTIVE_FAILURES başarısız deneme: daha güçlü modele geçiliyor (${up.model})")
                        cur = up
                        consecutiveFailures = 0
                        ui.update()
                    }
                }
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
    private suspend fun executeTool(call: ToolCall, readOnly: Boolean, allowOps: Boolean, ui: AgentUi, role: String? = null): Pair<ChatMessage.ToolResult, Boolean> {
        val t0 = System.currentTimeMillis()
        return executeToolTimed(call, readOnly, allowOps, ui, role).also { (r, _) ->
            val name = if (call.name == "run_cloud_tool") "run_cloud_tool:" + call.args.optString("tool") else call.name
            span(Span.kindOfTool(call.name), name, t0, ok = !r.isError)
        }
    }

    private suspend fun executeToolTimed(call: ToolCall, readOnly: Boolean, allowOps: Boolean, ui: AgentUi, role: String?): Pair<ChatMessage.ToolResult, Boolean> {
        ui.status(if (call.name == "ci_wait") "GitHub Actions derlemesi bekleniyor" else "Araç çalışıyor: ${call.name}")
        return try {
            val method = call.args.optString("method").ifBlank { null }
            if (unattended) unattendedDenied(call.name, method)?.let { return ChatMessage.ToolResult(call.id, call.name, it, true) to false }
            // Hard rules (prompt injection, auto-approve): dangerous API calls and writes to unknown repos always ask.
            securityQuestion(call, method)?.let { (title, body) ->
                if (unattended || isEvolutionSession) return ChatMessage.ToolResult(call.id, call.name, "Güvenlik: $title gözetimsiz çalışmada yapılamaz.", true) to false
                if (!ui.confirm(title, body, "Yine de yap", "İptal")) {
                    return ChatMessage.ToolResult(call.id, call.name, "Kullanıcı güvenlik onayı vermedi: $title. Bunu neden yapmak istediğini kullanıcıya açıkla.", true) to true
                }
                call.args.optString("repo").takeIf { it.isNotBlank() }?.let { safety.confirmedRepos += tools.normalizeRepo(it).lowercase() }
            }
            val denied = (if (role in ToolRegistry.ARCHITECT_ROLES) ToolRegistry.architectDenied(call.name, method) else null)
                ?: ToolRegistry.accessError(call.name, method, readOnly, allowOps)
            if (denied == null && tools.needsApproval(call) && !settings.autoApprove &&
                !ui.confirm("İzin ver: ${call.name}", "Bu işlem GitHub'da değişiklik yapacak.\n\n" + call.args.toString(2).take(20_000))
            ) {
                ChatMessage.ToolResult(call.id, call.name, "Kullanıcı bu işlemi reddetti. Ne yapmak istediğini kullanıcıya sor.", true) to true
            } else {
                ChatMessage.ToolResult(call.id, call.name, tools.execute(call, readOnly, allowOps, role)) to false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatMessage.ToolResult(call.id, call.name, e.message ?: e.toString(), true) to false
        } finally {
            ui.status(null)
        }
    }

    /** Sends a tiny request to a model and reports latency or the error, to diagnose hangs and wrong settings. */
    suspend fun ping(ref: ModelRef): String {
        val started = System.currentTimeMillis()
        return try {
            val r = kotlinx.coroutines.withTimeout(60_000) {
                client(ref).complete("Reply with the single word OK.", listOf(ChatMessage.User("ping")), emptyList(),
                    CallOptions(maxOutputTokens = 64, minimalThinking = true))
            }
            val ms = System.currentTimeMillis() - started
            "✓ ${ref.model}: $ms ms" + if (r.message.text.isBlank()) " (boş yanıt)" else ""
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            "✗ ${ref.model}: 60 sn içinde yanıt yok"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "✗ ${ref.model}: ${e.message?.take(160)}"
        }
    }

    // ---- Token preview ----

    data class Preview(val tokens: Long, val costUsd: Double, val exact: Boolean, val model: String)

    suspend fun preview(history: List<ChatMessage>, input: String): Preview {
        val ref = mainRef()
        val kind = if (settings.architectMode) "architect" else "agent"
        val stable = snapshots[kind] // only an already built snapshot; no network work for a preview
        val system = if (settings.architectMode) architectSystem(architectHasMap) else agentSystem()
        val toolSpecs = if (settings.architectMode) tools.singleShotSpecs else tools.specs
        val prefix = stable?.let { listOf(ChatMessage.User(it), ChatMessage.Assistant(STABLE_ACK)) } ?: emptyList()
        val msgs = prefix + HistoryPruner.prepare(history + ChatMessage.User(input), settings.keepTurns).messages
        val client = client(ref)
        val exact = runCatching { client.countTokens(system, msgs, toolSpecs) }.getOrNull()
        val tokens = exact ?: run {
            val chars = system.length + HistoryPruner.charSize(msgs) + toolSpecs.sumOf { it.parameters.toString().length + it.description.length }
            (chars / 3.5).toLong()
        }
        return Preview(tokens, Pricing.inputCost(tokens, price(ref.model)), exact != null, ref.model)
    }
}
