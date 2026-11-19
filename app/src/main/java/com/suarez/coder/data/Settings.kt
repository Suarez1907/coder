package com.suarez.coder.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

enum class Provider(val label: String) { GEMINI("Gemini"), DEEPSEEK("DeepSeek") }

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

    var githubToken: String
        get() = str("github_token")
        set(v) = put("github_token", v.trim())

    var defaultRepo: String
        get() = str("default_repo")
        set(v) = put("default_repo", v.trim())

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
        set(v) = put("custom_instructions", v)

    var maxSteps: Int
        get() = prefs.getInt("max_steps", 40)
        set(v) = prefs.edit().putInt("max_steps", v.coerceIn(1, 200)).apply()

    val currentModel: String
        get() = if (provider == Provider.GEMINI) geminiModel else deepseekModel

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

    // ---- Selected context files ----

    var contextRepo: String
        get() = str("context_repo")
        set(v) = put("context_repo", v.trim())

    var contextRef: String
        get() = str("context_ref")
        set(v) = put("context_ref", v.trim())

    var contextFiles: List<String>
        get() = str("context_files").lines().filter { it.isNotBlank() }
        set(v) = put("context_files", v.distinct().joinToString("\n"))
}
