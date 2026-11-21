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
    var architectSingleShot: Boolean
        get() = bool("architect_single_shot", true)
        set(v) = putBool("architect_single_shot", v)

    /** "provider/model" used as architect for requests Decision 1 rates below medium difficulty; blank = off. */
    var architectModelMedium: String
        get() = str("architect_model_medium", "gemini/gemini-3.8-flash")
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
