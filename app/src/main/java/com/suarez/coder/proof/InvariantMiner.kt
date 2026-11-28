package com.suarez.coder.proof

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Faz 7A — Otonom Değişmez Madencisi (`InvariantMiner`).
 *
 * Yeşil geçen `main` derlemelerinden ve repo ağacından `$0` maliyetle 3 kategoride
 * deterministik değişmezler (`.coder/invariants.json`) çıkarır:
 * - [Category.STRUCTURAL]: Çekirdek paket/dizin varlığı, ekran/sekme referansları, minimum boyut tabanı.
 * - [Category.DATA_COMPAT]: `Settings` ve kalıcı veri sınıflarında mevcut anahtar uyumluluğu.
 * - [Category.BEHAVIORAL]: `isEvolutionSession` auto-merge kilidi, kilitli `architectModel` koruması, negatif/NaN maliyet yasağı.
 */
object InvariantMiner {

    enum class Category { STRUCTURAL, DATA_COMPAT, BEHAVIORAL }

    data class Invariant(
        val id: String,
        val category: Category,
        val description: String,
        val targetPathPattern: String,
        val requiredToken: String? = null,
        val forbiddenToken: String? = null,
        val minBytes: Int = 0,
        val locked: Boolean = true,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("category", category.name)
            .put("description", description)
            .put("targetPathPattern", targetPathPattern)
            .put("requiredToken", requiredToken ?: JSONObject.NULL)
            .put("forbiddenToken", forbiddenToken ?: JSONObject.NULL)
            .put("minBytes", minBytes)
            .put("locked", locked)

        companion object {
            fun fromJson(o: JSONObject): Invariant = Invariant(
                id = o.optString("id"),
                category = runCatching { Category.valueOf(o.optString("category")) }.getOrDefault(Category.STRUCTURAL),
                description = o.optString("description"),
                targetPathPattern = o.optString("targetPathPattern"),
                requiredToken = if (o.isNull("requiredToken")) null else o.optString("requiredToken").ifBlank { null },
                forbiddenToken = if (o.isNull("forbiddenToken")) null else o.optString("forbiddenToken").ifBlank { null },
                minBytes = o.optInt("minBytes", 0),
                locked = o.optBoolean("locked", true),
            )
        }
    }

    /** Extracts deterministic baseline invariants from a repo file tree and key file contents ($0). */
    fun mine(paths: List<String>, keyFileContents: Map<String, String> = emptyMap()): List<Invariant> {
        val out = ArrayList<Invariant>()

        // 1) STRUCTURAL invariants
        val coreDirs = listOf("evolution/", "skills/", "harness/", "agent/", "data/", "ui/")
        for (dir in coreDirs) {
            if (paths.any { it.contains(dir) }) {
                out += Invariant(
                    id = "struct_dir_${dir.trimEnd('/')}",
                    category = Category.STRUCTURAL,
                    description = "Çekirdek paket '$dir' repoda korunmalıdır.",
                    targetPathPattern = dir,
                )
            }
        }
        keyFileContents.entries.firstOrNull { it.key.endsWith("SettingsScreen.kt") }?.let { (path, content) ->
            for (ref in listOf("EvolutionScreen", "SkillsScreen")) {
                if (content.contains(ref)) {
                    out += Invariant(
                        id = "struct_settings_ref_${ref.lowercase()}",
                        category = Category.STRUCTURAL,
                        description = "SettingsScreen içinde '$ref' ekran referansı kaybolmamalıdır.",
                        targetPathPattern = path,
                        requiredToken = ref,
                    )
                }
            }
            out += Invariant(
                id = "struct_settings_min_size",
                category = Category.STRUCTURAL,
                description = "SettingsScreen minimum boyut tabanı korunmalıdır.",
                targetPathPattern = path,
                minBytes = (content.length * 0.4).toInt().coerceAtLeast(200),
            )
        }

        // 2) DATA_COMPAT invariants
        keyFileContents.entries.firstOrNull { it.key.endsWith("Settings.kt") }?.let { (path, content) ->
            for (key in listOf("architect_model", "budget_usd", "gemini_key")) {
                if (content.contains("\"$key\"")) {
                    out += Invariant(
                        id = "data_compat_settings_$key",
                        category = Category.DATA_COMPAT,
                        description = "Settings.kt içinde '$key' kalıcı anahtarı geriye dönük uyumlu kalmalıdır.",
                        targetPathPattern = path,
                        requiredToken = "\"$key\"",
                    )
                }
            }
        }

        // 3) BEHAVIORAL invariants
        out += Invariant(
            id = "behav_architect_lock",
            category = Category.BEHAVIORAL,
            description = "Kilitli architectModel hiçbir otomatik evrim/piyasa modülü tarafından üzerine yazılamaz.",
            targetPathPattern = "evolution/",
            forbiddenToken = "settings.architectModel =",
        )
        out += Invariant(
            id = "behav_no_evo_automerge",
            category = Category.BEHAVIORAL,
            description = "Öz-gelişim PR'ları (evo/*) asla otomatik merge edilemez.",
            targetPathPattern = "evolution/",
            forbiddenToken = "autoMergeEvolutionPr = true",
        )
        out += Invariant(
            id = "behav_no_negative_pricing",
            category = Category.BEHAVIORAL,
            description = "Pricing hesaplaması negatif veya NaN maliyet üretemez.",
            targetPathPattern = "cost/Pricing.kt",
            forbiddenToken = "Double.NaN",
        )
        return out
    }

    fun toJson(invariants: List<Invariant>): String =
        JSONArray().also { arr -> invariants.forEach { arr.put(it.toJson()) } }.toString(2)

    fun fromJson(raw: String): List<Invariant> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Invariant.fromJson(it) } }
    }.getOrDefault(emptyList())

    fun save(file: File, invariants: List<Invariant>) {
        file.parentFile?.mkdirs()
        file.writeText(toJson(invariants))
    }

    fun load(file: File): List<Invariant> = if (file.isFile) fromJson(file.readText()) else emptyList()
}
