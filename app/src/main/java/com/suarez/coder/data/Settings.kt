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
}
