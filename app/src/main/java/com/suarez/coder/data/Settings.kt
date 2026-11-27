package com.suarez.coder.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

enum class Provider(val label: String) { GEMINI("Gemini"), DEEPSEEK("DeepSeek"), OPENROUTER("OpenRouter") }

/** Stores API keys and preferences, encrypted on the device. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            "coder_secure",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.getSharedPreferences("coder_plain", Context.MODE_PRIVATE)
    }

    private fun str(key: String, def: String = "") = prefs.getString(key, def) ?: def
    private fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()

    var geminiKey: String
        get() = str("gemini_key")
        set(v) = put("gemini_key", v.trim())

    var deepseekKey: String
        get() = str("deepseek_key")
        set(v) = put("deepseek_key", v.trim())

    var deepseekBaseUrl: String
        get() = str("deepseek_base", "https://api.deepseek.com")
        set(v) = put("deepseek_base", v.trim().trimEnd('/'))

    var openrouterKey: String
        get() = str("openrouter_key")
        set(v) = put("openrouter_key", v.trim())

    /** OpenRouter model id, e.g. anthropic/claude-sonnet-4.5 (the provider prefix is part of the id). */
    var openrouterModel: String
        get() = str("openrouter_model", "deepseek/deepseek-chat")
        set(v) = put("openrouter_model", v.trim())

    /** Prices and image support fetched from OpenRouter's model list (JSON, see OpenRouterCatalog). */
    var openrouterMeta: String
        get() = str("openrouter_meta")
        set(v) = put("openrouter_meta", v)

    fun keyFor(p: Provider): String = when (p) {
        Provider.GEMINI -> geminiKey
        Provider.DEEPSEEK -> deepseekKey
        Provider.OPENROUTER -> openrouterKey
    }

    fun modelFor(p: Provider): String = when (p) {
        Provider.GEMINI -> geminiModel
        Provider.DEEPSEEK -> deepseekModel
        Provider.OPENROUTER -> openrouterModel
    }

    fun setModelFor(p: Provider, model: String) = when (p) {
        Provider.GEMINI -> geminiModel = model
        Provider.DEEPSEEK -> deepseekModel = model
        Provider.OPENROUTER -> openrouterModel = model
    }

    var githubToken: String
        get() = str("github_token")
        set(v) = put("github_token", v.trim())

    var defaultRepo: String
        get() = str("default_repo")
        set(v) { put("default_repo", v.trim()); updateActive { it.copy(repo = v.trim()) } }

    var geminiModel: String
        get() = str("gemini_model", "gemini-2.5-pro")
        set(v) = put("gemini_model", v.trim())

    var deepseekModel: String
        get() = str("deepseek_model", "deepseek-chat")
        set(v) = put("deepseek_model", v.trim())

    var provider: Provider
        get() = runCatching { Provider.valueOf(str("provider", Provider.GEMINI.name)) }.getOrDefault(Provider.GEMINI)
        set(v) = put("provider", v.name)

    var autoApprove: Boolean
        get() = prefs.getBoolean("auto_approve", false)
        set(v) = prefs.edit().putBoolean("auto_approve", v).apply()

    var customInstructions: String
        get() = str("custom_instructions")
        set(v) { put("custom_instructions", v); updateActive { it.copy(instructions = v) } }

    var maxSteps: Int
        get() = prefs.getInt("max_steps", 40)
        set(v) = prefs.edit().putInt("max_steps", v.coerceIn(1, 200)).apply()

    companion object {
        /** Gemini 4 long decode allows up to the 1M-token context window. */
        const val MAX_OUTPUT = 1_048_576

        val DEFAULT_TEMPLATES = listOf(
            "release" to "Ana daldaki son değişikliklerle yeni bir sürüm çıkar: sürüm numarasını artır, gerekiyorsa CHANGELOG'u güncelle, " +
                "release workflow'unu çalıştır ve ci_wait ile sonucunu bekle. Bitince APK bağlantısını ver.",
            "ui-fix" to "Ekteki ekran görüntüsündeki arayüz sorununu düzelt: ilgili ekranı bul, taşma ve hizalama sorunlarını onar, " +
                "değişikliği küçük tut. ",
            "review" to "Son commit'teki (veya açık PR'daki) değişiklikleri incele: hata, güvenlik ve performans sorunlarını listele; " +
                "her biri için dosya:satır ve kısa düzeltme önerisi ver. Kodu değiştirme.",
            "notes" to ".coder/NOTES.md dosyasını projenin güncel durumuna göre oluştur veya güncelle: amaç, yapı, önemli dosyalar, " +
                "derleme/test komutları, dikkat edilecekler. 60 satırı geçme.",
        )
    }

    val currentModel: String
        get() = modelFor(provider)

    private fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    // ---- Cost optimisation ----

    var architectMode: Boolean
        get() = bool("architect_mode", false)
        set(v) = putBool("architect_mode", v)

    /** "provider/model" */
    var architectModel: String
        get() = str("architect_model", "gemini/gemini-2.5-pro")
        set(v) = put("architect_model", v)

    var editorModel: String
        get() = str("editor_model", "gemini/gemini-2.5-flash")
        set(v) = put("editor_model", v)

    /** low | medium | high */
    var thinkingLevel: String
        get() = str("thinking_level", "low")
        set(v) = put("thinking_level", v)

    var cacheEnabled: Boolean
        get() = bool("cache_enabled", true)
        set(v) = putBool("cache_enabled", v)

    var cacheTtlMinutes: Int
        get() = prefs.getInt("cache_ttl_min", 10)
        set(v) = prefs.edit().putInt("cache_ttl_min", v.coerceIn(1, 120)).apply()

    var keepTurns: Int
        get() = prefs.getInt("keep_turns", 4)
        set(v) = prefs.edit().putInt("keep_turns", v.coerceIn(1, 20)).apply()

    var budgetUsd: Double
        get() = str("budget_usd", "1.00").toDoubleOrNull() ?: 1.0
        set(v) = put("budget_usd", v.toString())

    var usdTry: Double
        get() = str("usd_try", "42.0").toDoubleOrNull() ?: 42.0
        set(v) = put("usd_try", v.toString())

    var priceOverrides: String
        get() = str("price_overrides")
        set(v) = put("price_overrides", v)

    var tokenPreview: Boolean
        get() = bool("token_preview", true)
        set(v) = putBool("token_preview", v)

    /** Models fetched from the APIs, as "provider/model" lines. */
    /** When the model lists were last fetched (daily background refresh). */
    var catalogAt: Long
        get() = prefs.getLong("catalog_at", 0L)
        set(v) = prefs.edit().putLong("catalog_at", v).apply()

    var modelCatalog: List<String>
        get() = str("model_catalog").lines().filter { it.isNotBlank() }
        set(v) = put("model_catalog", v.distinct().joinToString("\n"))

    // ---- Per-role thinking (minimal | low | medium | high) ----

    var thinkingArchitect: String
        get() = str("thinking_architect", "medium")
        set(v) = put("thinking_architect", v)

    var thinkingEditor: String
        get() = str("thinking_editor", "minimal")
        set(v) = put("thinking_editor", v)

    // ---- Cache-friendly history and caching policy ----

    /** Uncompacted history (characters) that may accumulate before one bulk compaction. */
    var compactTailChars: Int
        get() = prefs.getInt("compact_tail_chars", 80_000)
        set(v) = prefs.edit().putInt("compact_tail_chars", v.coerceIn(20_000, 2_000_000)).apply()

    /** Use explicit caching only when the measured implicit hit rate is low. */
    var explicitCacheAuto: Boolean
        get() = bool("explicit_cache_auto", true)
        set(v) = putBool("explicit_cache_auto", v)

    /** Gemini Flex tier: 50% cheaper, minutes of latency, best-effort capacity. */
    var economyMode: Boolean
        get() = bool("economy_mode", false)
        set(v) = putBool("economy_mode", v)

    /** Keep and use .coder/NOTES.md as persistent project memory. */
    var projectNotes: Boolean
        get() = bool("project_notes", true)
        set(v) = putBool("project_notes", v)

    /** "provider/model" between editor and architect for failed edits; blank = automatic, "none" = skip. */
    var escalationModel: String
        get() = str("escalation_model")
        set(v) = put("escalation_model", v.trim())

    /** Standard (non-Flex) model requests fail with a clear message after this many seconds. */
    var requestTimeoutSec: Int
        get() = prefs.getInt("request_timeout_sec", 180)
        set(v) = prefs.edit().putInt("request_timeout_sec", v.coerceIn(30, 900)).apply()

    /** Architect first tries to plan in one call (map + context, no read tools). */
    /** Executor self-repair attempts per file group before the run pauses for the user (never the architect). */
    var maxExecutorRetries: Int
        get() = prefs.getInt("max_executor_retries", 5).coerceIn(1, 10)
        set(v) = prefs.edit().putInt("max_executor_retries", v.coerceIn(1, 10)).apply()

    var architectSingleShot: Boolean
        get() = bool("architect_single_shot", true)
        set(v) = putBool("architect_single_shot", v)

    /** "provider/model" used as architect for requests Decision 1 rates below medium difficulty; blank = off. */
    var architectModelMedium: String
        get() = str("architect_model_medium", "")
        set(v) = put("architect_model_medium", v.trim())

    /** Slash-command prompt templates (name without "/", text). Defaults until the user edits them. */
    var promptTemplates: List<Pair<String, String>>
        get() {
            val raw = prefs.getString("prompt_templates", null) ?: return DEFAULT_TEMPLATES
            return runCatching {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("name") to o.getString("text") } }
            }.getOrDefault(DEFAULT_TEMPLATES)
        }
        set(v) {
            val arr = org.json.JSONArray()
            v.forEach { (n, t) -> arr.put(org.json.JSONObject().put("name", n).put("text", t)) }
            put("prompt_templates", arr.toString())
        }

    /** "dark" (IDE theme, default), "oled", "light" or "system". */
    var themeMode: String
        get() = str("theme_mode", "dark")
        set(v) = put("theme_mode", v)

    // ---- Output limits ----

    /** "Sadece Diff (Açıklamasız Mod)" */
    var terseMode: Boolean
        get() = bool("terse_mode", true)
        set(v) = putBool("terse_mode", v)

    var maxOutArchitect: Int
        get() = prefs.getInt("max_out_architect", 1500)
        set(v) = prefs.edit().putInt("max_out_architect", v.coerceIn(256, MAX_OUTPUT)).apply()

    var maxOutEditor: Int
        get() = prefs.getInt("max_out_editor", 4096)
        set(v) = prefs.edit().putInt("max_out_editor", v.coerceIn(256, MAX_OUTPUT)).apply()

    var maxOutAgent: Int
        get() = prefs.getInt("max_out_agent", 4096)
        set(v) = prefs.edit().putInt("max_out_agent", v.coerceIn(256, MAX_OUTPUT)).apply()

    // ---- Cache threshold, repo map ----

    var cacheMinTokens: Int
        get() = prefs.getInt("cache_min_tokens", 32_000)
        set(v) = prefs.edit().putInt("cache_min_tokens", v.coerceIn(1_024, 1_000_000)).apply()

    var repoMapForArchitect: Boolean
        get() = bool("repo_map_architect", true)
        set(v) = putBool("repo_map_architect", v)

    // ---- Local + cheap router ----

    /** "provider/model" for tier-2 classification; blank = cheapest suitable model from the catalog. */
    var routerModel: String
        get() = str("router_model")
        set(v) = put("router_model", v.trim())

    var useDecision1: Boolean
        get() = bool("use_decision1", true)
        set(v) = putBool("use_decision1", v)

    // ---- Smart routing (Gemini Decision 1) ----

    var smartRouting: Boolean
        get() = bool("smart_routing", false)
        set(v) = putBool("smart_routing", v)

    var decisionModel: String
        get() = str("decision_model", "gemini-decision-1")
        set(v) = put("decision_model", v.trim())

    // ---- Self-improvement manager (learned model choice, exams, earned autonomy) ----

    /** 1 = only proposes; 2 = applies data changes (models, prompts, skills) that won an exam; 3 = also merges self-PRs that pass the gates. */
    var autonomyLevel: Int
        get() = prefs.getInt("autonomy_level", 1).coerceIn(1, 3)
        set(v) = prefs.edit().putInt("autonomy_level", v.coerceIn(1, 3)).apply()
    /** 0 = best quality whatever it costs … 1 = cheapest that still works. */
    var costWeight: Double
        get() = str("cost_weight", "0.5").toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.5
        set(v) = put("cost_weight", v.coerceIn(0.0, 1.0).toString())
    /** Models the manager may choose from ("provider/model"); fewer than two = the user's fixed choice. */
    var modelPool: List<String>
        get() = str("model_pool").lines().map { it.trim() }.filter { it.isNotBlank() }
        set(v) = put("model_pool", v.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString("\n"))
    var learnedRouting: Boolean
        get() = bool("learned_routing", true)
        set(v) = putBool("learned_routing", v)
    var examWeeklyBudget: Double
        get() = str("exam_budget", "1.0").toDoubleOrNull()?.coerceIn(0.0, 20.0) ?: 1.0
        set(v) = put("exam_budget", v.coerceIn(0.0, 20.0).toString())
    var scoutEnabled: Boolean
        get() = bool("scout_enabled", true)
        set(v) = putBool("scout_enabled", v)
    /** The user's goal in their own words (shown in the manager card). */
    var managerGoal: String
        get() = str("manager_goal")
        set(v) = put("manager_goal", v.trim())

    // ---- Self-evolution ----

    var evoEnabled: Boolean
        get() = bool("evo_enabled", false)
        set(v) = putBool("evo_enabled", v)
    var evoMemory: Boolean
        get() = bool("evo_k1", true)
        set(v) = putBool("evo_k1", v)
    var evoRouting: Boolean
        get() = bool("evo_k2", true)
        set(v) = putBool("evo_k2", v)
    var evoSelfPr: Boolean
        get() = bool("evo_k3", true)
        set(v) = putBool("evo_k3", v)
    /** OPENROUTER or GEMINI. Independent of the chat provider and of the architect model. */
    var evoProvider: String
        get() = if (str("evo_provider", "OPENROUTER").equals("GEMINI", true)) "GEMINI" else "OPENROUTER"
        set(v) = put("evo_provider", if (v.equals("GEMINI", true)) "GEMINI" else "OPENROUTER")

    /** OpenRouter id of the isolated evolution model. Default is Nemotron. Does not write the architect model. */
    var evoModel: String
        get() = str("evo_model", "nvidia/nemotron-3-ultra")
        set(v) = put("evo_model", v.trim().removePrefix("openrouter/").ifBlank { "nvidia/nemotron-3-ultra" })

    /** Gemini API model id used when [evoProvider] is GEMINI. Uses [geminiKey], not the architect model. */
    var evoGeminiModel: String
        get() = str("evo_gemini_model", "gemini-2.5-flash")
        set(v) = put("evo_gemini_model", v.trim().removePrefix("gemini/").ifBlank { "gemini-2.5-flash" })
    var evoDailyBudget: Double
        get() = str("evo_budget", "0.15").toDoubleOrNull() ?: 0.15
        set(v) = put("evo_budget", v.coerceIn(0.0, 10.0).toString())
    var evoWifiOnly: Boolean
        get() = bool("evo_wifi", false)
        set(v) = putBool("evo_wifi", v)

    /** Distinct sessions required before a repeated failure may open a self-PR. */
    var clusterMinSessions: Int
        get() = prefs.getInt("cluster_min_sessions", 2).coerceIn(1, 20)
        set(v) = prefs.edit().putInt("cluster_min_sessions", v.coerceIn(1, 20)).apply()

    /** Cumulative wasted cost (USD) that also opens the self-PR gate. */
    var clusterMinCostUsd: Double
        get() = str("cluster_min_cost", "0.05").toDoubleOrNull()?.coerceIn(0.0, 10.0) ?: 0.05
        set(v) = put("cluster_min_cost", v.coerceIn(0.0, 10.0).toString())

    /** Cost-to-green exploration for the implementer. Never changes the architect. */
    var ctgExperiments: Boolean
        get() = bool("ctg_experiments", true)
        set(v) = putBool("ctg_experiments", v)

    /** When set, the implementer stays on this model. Blank keeps cost-to-green selection. */
    var pinnedImplementer: String
        get() = str("pinned_implementer", "")
        set(v) = put("pinned_implementer", v.trim())

    /**
     * When on, the next commit the user already asked for also updates `.coder/NOTES.md`.
     * Off by default: nothing is committed on its own, and nothing is merged.
     */
    var syncProjectNotes: Boolean
        get() = bool("sync_project_notes", false)
        set(v) = putBool("sync_project_notes", v)

    /** Vague change requests get 1-3 short clarifying questions before any expensive planning. */
    var clarifyFirst: Boolean
        get() = bool("clarify_first", true)
        set(v) = putBool("clarify_first", v)

    /** Repo|tool pairs whose cloud workflow the user declined to install. */
    var workflowDeclined: Set<String>
        get() = str("workflow_declined").split('\n').filter { it.isNotBlank() }.toSet()
        set(v) = put("workflow_declined", v.joinToString("\n"))
    /** Code index in GitHub Actions (ctags + usages) for precise references and reading single symbols. */
    var codeIndex: Boolean
        get() = bool("code_index", true)
        set(v) = putBool("code_index", v)
    /** Quick check also runs the tests that exercise the changed files. */
    var targetedTests: Boolean
        get() = bool("targeted_tests", true)
        set(v) = putBool("targeted_tests", v)
    /** Android evaluation drives the app with Maestro flows written from the acceptance criteria. */
    var androidUiTests: Boolean
        get() = bool("android_ui_tests", true)
        set(v) = putBool("android_ui_tests", v)

    /** Self-improvement may rewrite evolvable prompt sections (each rewrite must win an exam). */
    var promptEvolution: Boolean
        get() = bool("prompt_evolution", true)
        set(v) = putBool("prompt_evolution", v)
    /** Recurring successful procedures become skill candidates (tested by exams). */
    var procedureMining: Boolean
        get() = bool("procedure_mining", true)
        set(v) = putBool("procedure_mining", v)

    /** Choose models with the task context (contextual bandit) instead of per route only. */
    var contextualRouting: Boolean
        get() = bool("contextual_routing", true)
        set(v) = putBool("contextual_routing", v)

    /** Candidate edits per file group (1 = off) and when to make them: "risk" (predicted CI failure), "always", "off". */
    var candidates: Int
        get() = prefs.getInt("candidates", 3).coerceIn(1, 5)
        set(v) = prefs.edit().putInt("candidates", v.coerceIn(1, 5)).apply()
    var candidateTrigger: String
        get() = str("candidate_trigger", "risk")
        set(v) = put("candidate_trigger", v)

    /** Learn the risk of a red CI from the user's own commits (logistic regression on the phone). */
    var riskModel: Boolean
        get() = bool("risk_model", true)
        set(v) = putBool("risk_model", v)
    /** Predicted failure probability from which candidates are made. */
    var riskThreshold: Double
        get() = str("risk_threshold", "0.35").toDoubleOrNull()?.coerceIn(0.05, 0.95) ?: 0.35
        set(v) = put("risk_threshold", v.coerceIn(0.05, 0.95).toString())
    /** Skip the quick check for changes the trained risk model rates very safe. */
    var quickCheckAdaptive: Boolean
        get() = bool("quick_check_adaptive", true)
        set(v) = putBool("quick_check_adaptive", v)
    /** Measure how sure a plan is (independent guesses of the files) and ask when it is unclear. */
    var planUncertainty: Boolean
        get() = bool("plan_uncertainty", true)
        set(v) = putBool("plan_uncertainty", v)

    /** Overlap waits: tree/repo map load during routing, the next subtask is edited while CI runs, the evaluator runs with CI. */
    var pipelining: Boolean
        get() = bool("pipelining", true)
        set(v) = putBool("pipelining", v)

    /** Secrets and new dependencies (existence, look-alike names, known vulnerabilities) are checked before each commit. */
    var supplyChainCheck: Boolean
        get() = bool("supply_chain", true)
        set(v) = putBool("supply_chain", v)

    /** A red CI that looks transient (network, runner, a test known to flip) is re-run once before the agent "fixes" anything. */
    var flakyRetry: Boolean
        get() = bool("flaky_retry", true)
        set(v) = putBool("flaky_retry", v)

    /** Run the fixed evaluation set once after each app update (inside the weekly exam budget). */
    var evalOnUpdate: Boolean
        get() = bool("eval_on_update", true)
        set(v) = putBool("eval_on_update", v)

    /** A failing or overloaded model hands the call to the next model in the escalation order. */
    var failover: Boolean
        get() = bool("failover", true)
        set(v) = putBool("failover", v)

    /** Background work (self-improvement, queue) uses Gemini Flex (half price, may be slower). */
    var backgroundFlex: Boolean
        get() = bool("background_flex", true)
        set(v) = putBool("background_flex", v)

    /** Background jobs may use provider Flex / economic routing. Unsupported providers stay on the standard endpoint. */
    var flexArbitrage: Boolean
        get() = bool("flex_arbitrage", false)
        set(v) = putBool("flex_arbitrage", v)

    /** Cut a live answer that is looping, rambling, or rewriting a whole file. */
    var streamWatchdog: Boolean
        get() = bool("stream_watchdog", true)
        set(v) = putBool("stream_watchdog", v)

    /** Pack the most relevant symbols before the first coding call. */
    var turn0Pack: Boolean
        get() = bool("turn0_pack", true)
        set(v) = putBool("turn0_pack", v)

    /** Character cap of the Turn-0 pack. Symbols win over call sites, tests and rules. */
    var turn0CharCap: Int
        get() = prefs.getInt("turn0_char_cap", 14_000)
        set(v) = prefs.edit().putInt("turn0_char_cap", v.coerceIn(2_000, 80_000)).apply()

    /** Executor starts with low thinking and learned output caps; raised when it struggles. */
    var adaptiveBudget: Boolean
        get() = bool("adaptive_budget", true)
        set(v) = putBool("adaptive_budget", v)

    /** Queue runs only between these local hours (start..end, may wrap midnight); -1 = any time. */
    var queueWindowStart: Int
        get() = prefs.getInt("queue_win_start", -1).coerceIn(-1, 23)
        set(v) = prefs.edit().putInt("queue_win_start", v.coerceIn(-1, 23)).apply()
    var queueWindowEnd: Int
        get() = prefs.getInt("queue_win_end", -1).coerceIn(-1, 23)
        set(v) = prefs.edit().putInt("queue_win_end", v.coerceIn(-1, 23)).apply()

    /** Warm the quick-check caches on the default branch once a day per repo. */
    var quickCheckWarm: Boolean
        get() = bool("quick_check_warm", true)
        set(v) = putBool("quick_check_warm", v)
    var quickCheckWarmedAt: String
        get() = str("quick_check_warmed")
        set(v) = put("quick_check_warmed", v)

    /** Prices of the watched models at the last check (PriceWatch JSON) and alerts the user dismissed. */
    var priceSnapshot: String
        get() = str("price_snapshot")
        set(v) = put("price_snapshot", v)
    var activePriceAlerts: Set<String>
        get() = str("price_alerts_active").split('\n').filter { it.isNotBlank() }.toSet()
        set(v) = put("price_alerts_active", v.joinToString("\n"))
    var dismissedPriceAlerts: Set<String>
        get() = str("price_alerts_dismissed").split('\n').filter { it.isNotBlank() }.toSet()
        set(v) = put("price_alerts_dismissed", v.joinToString("\n"))

    /** The user's ordered list of stronger models ("provider/model" per line): repairs climb it one by one. */
    var escalationChain: String
        get() = str("escalation_chain")
        set(v) = put("escalation_chain", v)

    /** "Until green": a red CI keeps the agent fixing; the same error twice moves up the escalation chain. */
    var untilGreen: Boolean
        get() = bool("until_green", true)
        set(v) = putBool("until_green", v)

    /** Red CI rounds in one task before the user is asked whether to go on. */
    var ciMaxRounds: Int
        get() = prefs.getInt("ci_max_rounds", 4).coerceIn(1, 10)
        set(v) = prefs.edit().putInt("ci_max_rounds", v.coerceIn(1, 10)).apply()

    // Auto-Ship: for user-initiated tasks only, run code, PR, CI, merge, version bump and APK
    // automatically. Never for evo-prefixed PRs. (Not a block comment: Kotlin nests /* */.)
    var autoShip: Boolean
        get() = bool("auto_ship", false)
        set(v) = putBool("auto_ship", v)

    /** Max automatic CI-fix rounds before giving up (1-5). */
    var maxCiHealAttempts: Int
        get() = prefs.getInt("max_ci_heal_attempts", 3).coerceIn(1, 5)
        set(v) = prefs.edit().putInt("max_ci_heal_attempts", v.coerceIn(1, 5)).apply()

    /** "auto" = bump version automatically on ship; "tag" = only tag a release on the existing tag flow. */
    var versionBumpMode: String
        get() = str("version_bump_mode", "auto")
        set(v) = put("version_bump_mode", v)

    /** A cheap model checks the architect's plan for missing callers/registrations before code is written. */
    var planCritic: Boolean
        get() = bool("plan_critic", true)
        set(v) = putBool("plan_critic", v)

    /** Remember how CI errors were fixed and hint the fix when the same error comes back. */
    var fixMemory: Boolean
        get() = bool("fix_memory", true)
        set(v) = putBool("fix_memory", v)

    /** Compile/type-check on a throwaway [skip ci] branch before each code commit (GitHub Actions, 1-5 min). */
    var quickCheck: Boolean
        get() = bool("quick_check", false)
        set(v) = putBool("quick_check", v)

    /** Repos where the user declined installing the quick-check workflow. */
    var quickCheckDeclined: Set<String>
        get() = str("quick_check_declined").split('\n').filter { it.isNotBlank() }.toSet()
        set(v) = put("quick_check_declined", v.joinToString("\n"))

    /** Full orchestrator on Android projects: emulator screenshots in GitHub Actions after CI passes. */
    var androidShots: Boolean
        get() = bool("android_shots", false)
        set(v) = putBool("android_shots", v)

    /** Run queued tasks unattended. */
    var queueEnabled: Boolean
        get() = bool("queue_enabled", true)
        set(v) = putBool("queue_enabled", v)

    var queueRequiresCharging: Boolean
        get() = bool("queue_charging", true)
        set(v) = putBool("queue_charging", v)

    var queueRequiresWifi: Boolean
        get() = bool("queue_wifi", true)
        set(v) = putBool("queue_wifi", v)

    /** Spending cap for one queued task. */
    var queueTaskBudget: Double
        get() = str("queue_task_budget", "0.5").toDoubleOrNull()?.coerceIn(0.05, 10.0) ?: 0.5
        set(v) = put("queue_task_budget", v.coerceIn(0.05, 10.0).toString())

    /** Issues labelled "coder" in the user's repos become queued tasks. */
    var issueWatch: Boolean
        get() = bool("issue_watch", false)
        set(v) = putBool("issue_watch", v)

    /** Weekly: the user's PR review comments become project rules (lessons). */
    var learnFromReviews: Boolean
        get() = bool("learn_reviews", true)
        set(v) = putBool("learn_reviews", v)

    /** fetch_live_docs: current library documentation (Context7, then the web). lookup_docs is an unlisted alias. */
    var docsLookup: Boolean
        get() = bool("docs_lookup", true)
        set(v) = putBool("docs_lookup", v)

    /** Optional Context7 API key (higher rate limits). */
    var context7Key: String
        get() = str("context7_key")
        set(v) = put("context7_key", v.trim())

    /** update_todos: the agent keeps a visible task list on multi-step work. */
    var todoList: Boolean
        get() = bool("todo_list", true)
        set(v) = putBool("todo_list", v)

    /** A cheap model checks every change right before it is committed. */
    var selfReview: Boolean
        get() = bool("self_review", true)
        set(v) = putBool("self_review", v)

    /** Successful earlier tasks similar to the request are given as a hint. */
    var similarTasks: Boolean
        get() = bool("similar_tasks", true)
        set(v) = putBool("similar_tasks", v)

    /** The branch head before the agent's first write is saved, so the chat can rewind to it. */
    var checkpoints: Boolean
        get() = bool("checkpoints", true)
        set(v) = putBool("checkpoints", v)

    /** semantic_search over an embedding index of the repo (Gemini key needed). */
    var semanticSearch: Boolean
        get() = bool("semantic_search", true)
        set(v) = putBool("semantic_search", v)

    /** User-defined modes (JSON list of CustomMode). */
    var customModes: String
        get() = str("custom_modes")
        set(v) = put("custom_modes", v)

    /** Name of the active custom mode; empty = automatic routing. */
    var activeMode: String
        get() = str("active_mode")
        set(v) = put("active_mode", v)

    /** Full orchestrator: two models build the same plan; the evaluator (or CI / reviewer) keeps the better one. */
    var bestOfTwo: Boolean
        get() = bool("best_of_two", false)
        set(v) = putBool("best_of_two", v)

    /** Full orchestrator: an evaluator tests the running app (web: Playwright in GitHub Actions) against the contract. */
    var evaluatorEnabled: Boolean
        get() = bool("evaluator_enabled", false)
        set(v) = putBool("evaluator_enabled", v)

    /** OrchestratorMode name; OFF keeps the normal flow untouched. */
    var orchestratorMode: String
        get() = str("orchestrator_mode", "OFF")
        set(v) = put("orchestrator_mode", v)

    var orchMaxParallel: Int
        get() = prefs.getInt("orch_max_parallel", 2).coerceIn(1, 3)
        set(v) = prefs.edit().putInt("orch_max_parallel", v.coerceIn(1, 3)).apply()

    /** Implementer A/B tactics. Off until the user enables them; the architect model is not an arm. */
    var abEnabled: Boolean
        get() = bool("ab_enabled", false)
        set(v) = putBool("ab_enabled", v)

    /** Proactive sentinel. Off by default. */
    var sentinelEnabled: Boolean
        get() = bool("sentinel_enabled", false)
        set(v) = putBool("sentinel_enabled", v)

    var sentinelHours: Int
        get() = prefs.getInt("sentinel_hours", 12).let { if (it == 6 || it == 24) it else 12 }
        set(v) = prefs.edit().putInt("sentinel_hours", if (v == 6 || v == 24) v else 12).apply()

    var sentinelRepos: String
        get() = str("sentinel_repos")
        set(v) = put("sentinel_repos", v.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct().take(5).joinToString("\n"))

    var sentinelBudget: Double
        get() = str("sentinel_budget", "0.10").toDoubleOrNull()?.coerceIn(0.0, 5.0) ?: 0.10
        set(v) = put("sentinel_budget", v.coerceIn(0.0, 5.0).toString())

    var sentinelDraft: Boolean
        get() = bool("sentinel_draft", false)
        set(v) = putBool("sentinel_draft", v)

    var orchMaxReviewRounds: Int
        get() = prefs.getInt("orch_max_review_rounds", 2).coerceIn(0, 5)
        set(v) = prefs.edit().putInt("orch_max_review_rounds", v.coerceIn(0, 5)).apply()

    /** "gemini", "openrouter" or "auto" (primary + backup). */
    var decisionProvider: String
        get() = str("decision_provider", "auto")
        set(v) = put("decision_provider", v)

    var openrouterDecisionModel: String
        get() = str("openrouter_decision_model", "typesafe/jev-1.14")
        set(v) = put("openrouter_decision_model", v.trim())

    var decisionTimeoutMs: Long
        get() = prefs.getLong("decision_timeout_ms", 2_000L)
        set(v) = prefs.edit().putLong("decision_timeout_ms", v.coerceIn(500L, 30_000L)).apply()

    /** Complex tasks wait for a spec card before code is written. Small tasks never wait. */
    var specApproval: Boolean
        get() = bool("spec_approval", false)
        set(v) = putBool("spec_approval", v)

    var visualQa: Boolean
        get() = bool("visual_qa", true)
        set(v) = putBool("visual_qa", v)

    var adversarialCritic: Boolean
        get() = bool("adversarial_critic", true)
        set(v) = putBool("adversarial_critic", v)

    /** "github" (default) or "sandbox". An unreachable sandbox falls back to GitHub Actions. */
    var executionBackend: String
        get() = str("execution_backend", "github").let { if (it == "sandbox") it else "github" }
        set(v) = put("execution_backend", if (v == "sandbox") "sandbox" else "github")

    var sandboxUrl: String
        get() = str("sandbox_url")
        set(v) = put("sandbox_url", v.trim())

    var sandboxToken: String
        get() = str("sandbox_token")
        set(v) = put("sandbox_token", v.trim())

    /** User-picked on-device model. Empty means the heuristic fallback. Never an APK asset. */
    var tier0ModelPath: String
        get() = str("tier0_model")
        set(v) = put("tier0_model", v.trim())

    var tier0Redact: Boolean
        get() = bool("tier0_redact", true)
        set(v) = putBool("tier0_redact", v)

    var tier0Voice: Boolean
        get() = bool("tier0_voice", true)
        set(v) = putBool("tier0_voice", v)

    var tier0Classify: Boolean
        get() = bool("tier0_classify", true)
        set(v) = putBool("tier0_classify", v)

    var mcpServers: String
        get() = str("mcp_servers", "[]")
        set(v) = put("mcp_servers", v)

    var mcpTools: String
        get() = str("mcp_tools", "[]")
        set(v) = put("mcp_tools", v)

    /** Optional official llms.txt or docs URL used by fetch_live_docs. */
    var docsLlmsUrl: String
        get() = str("docs_llms_url")
        set(v) = put("docs_llms_url", v.trim())

    // ---- Selected context files ----

    var contextRepo: String
        get() = str("context_repo")
        set(v) = put("context_repo", v.trim())

    var contextRef: String
        get() = str("context_ref")
        set(v) { put("context_ref", v.trim()); updateActive { it.copy(contextRef = v.trim()) } }

    var contextFiles: List<String>
        get() = str("context_files").lines().filter { it.isNotBlank() }
        set(v) { put("context_files", v.distinct().joinToString("\n")); updateActive { it.copy(contextFiles = v.distinct()) } }

    // ---- Project profiles ----
    // The active profile is mirrored into defaultRepo / customInstructions / context*: everything that reads those
    // keeps working, and every write to them is also stored in the active profile.

    var defaultBranch: String
        get() = str("default_branch")
        set(v) { put("default_branch", v.trim()); updateActive { it.copy(branch = v.trim()) } }

    val activeProfileId: String get() = str("active_profile")

    /** All profiles; on first use the current repo/instructions/context become the first profile. */
    fun profiles(): List<ProjectProfile> {
        val stored = ProfileJson.fromJson(str("profiles", "[]"))
        if (stored.isNotEmpty()) return stored
        val first = ProjectProfile(
            repo = defaultRepo, branch = str("default_branch"), instructions = customInstructions,
            contextRef = contextRef, contextFiles = contextFiles,
        )
        put("profiles", ProfileJson.toJson(listOf(first)))
        put("active_profile", first.id)
        return listOf(first)
    }

    fun activeProfile(): ProjectProfile = profiles().let { list -> list.firstOrNull { it.id == activeProfileId } ?: list.first() }

    private fun saveProfiles(list: List<ProjectProfile>) = put("profiles", ProfileJson.toJson(list))

    private fun updateActive(change: (ProjectProfile) -> ProjectProfile) {
        val list = ProfileJson.fromJson(str("profiles", "[]"))
        if (list.isEmpty()) return // not migrated yet: profiles() will copy the current values
        val id = activeProfileId
        saveProfiles(list.map { if (it.id == id) change(it) else it })
    }

    /** Makes [id] active and copies its values into the mirrored settings. */
    fun activateProfile(id: String) {
        val p = profiles().firstOrNull { it.id == id } ?: return
        put("active_profile", p.id)
        put("default_repo", p.repo)
        put("default_branch", p.branch)
        put("custom_instructions", p.instructions)
        put("context_repo", "")
        put("context_ref", p.contextRef)
        put("context_files", p.contextFiles.joinToString("\n"))
        if (p.architectModel.isNotBlank()) architectModel = p.architectModel
        if (p.editorModel.isNotBlank()) editorModel = p.editorModel
    }

    /** Adds or replaces a profile (matched by id). */
    fun upsertProfile(p: ProjectProfile) {
        val list = profiles()
        saveProfiles(if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p)
        if (p.id == activeProfileId) activateProfile(p.id)
    }

    fun deleteProfile(id: String) {
        val rest = profiles().filter { it.id != id }
        if (rest.isEmpty()) return // keep at least one
        saveProfiles(rest)
        if (id == activeProfileId) activateProfile(rest.first().id)
    }
}
