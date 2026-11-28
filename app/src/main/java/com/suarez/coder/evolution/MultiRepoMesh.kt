package com.suarez.coder.evolution

import com.suarez.coder.security.ArchitecturalImmunity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Faz 6B — Çoklu Repo Bilgi Ağı (`MultiRepoMesh`).
 *
 * Mevcut [CrossRepoKnowledge] yapısını genişleterek kullanıcının farklı repoları (Android/Kotlin,
 * Gradle, Godot/GDScript, webOS vb.) arasında öğrenilen mimari kuralları, derleme tuzaklarını ve
 * bağışıklık antikorlarını dil/stack etiketine göre (`kotlin`, `gradle`, `gdscript`, `webos`) paylaşır.
 *
 * Yeni görev başlarken yalnızca aktif reponun teknoloji etiketiyle eşleşen en fazla [MAX_INJECTED_RULES] (5)
 * yüksek-güvenli kuralı sabit bağlama ekler ve toplam çıktıyı kesinlikle [MAX_CONTEXT_CHARS] (2000)
 * karakter tavanında tutar.
 */
class MultiRepoMesh(private val file: File? = null) {

    companion object {
        const val MAX_INJECTED_RULES = 5
        const val MAX_CONTEXT_CHARS = 2_000
        const val MIN_CONFIDENCE = 0.70

        /** Detects active technology stack tags from repo file paths. */
        fun detectStackTags(paths: List<String>, repoName: String = ""): Set<String> {
            val tags = LinkedHashSet<String>()
            for (raw in paths) {
                val p = raw.lowercase()
                when {
                    p.endsWith(".kt") || p.endsWith(".kts") -> tags += "kotlin"
                    p.endsWith(".gradle") || p.endsWith(".gradle.kts") || p.endsWith("settings.gradle") -> tags += "gradle"
                    p.endsWith(".gd") || p.endsWith(".tscn") || p.endsWith("project.godot") -> tags += "gdscript"
                    p.endsWith("appinfo.json") || p.contains("webos") || p.endsWith(".ipk") -> tags += "webos"
                    p.endsWith(".ts") || p.endsWith(".tsx") || p.endsWith(".js") -> tags += "js"
                    p.endsWith(".py") -> tags += "python"
                }
            }
            val lowerRepo = repoName.lowercase()
            if ("godot" in lowerRepo) tags += "gdscript"
            if ("webos" in lowerRepo) tags += "webos"
            return tags
        }
    }

    data class MeshRule(
        val id: String,
        val stackTag: String,
        val text: String,
        val confidence: Double = 0.80,
        val hits: Int = 1,
        val sourceRepo: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("stackTag", stackTag)
            .put("text", text)
            .put("confidence", confidence)
            .put("hits", hits)
            .put("sourceRepo", sourceRepo)

        companion object {
            fun fromJson(o: JSONObject): MeshRule = MeshRule(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                stackTag = o.optString("stackTag", "kotlin").lowercase(),
                text = o.optString("text"),
                confidence = o.optDouble("confidence", 0.80).coerceIn(0.0, 1.0),
                hits = o.optInt("hits", 1),
                sourceRepo = o.optString("sourceRepo", ""),
            )
        }
    }

    private val entries = LinkedHashMap<String, MeshRule>()

    init {
        loadFromDisk()
    }

    @Synchronized
    fun all(): List<MeshRule> = entries.values.toList()

    @Synchronized
    fun record(
        stackTag: String,
        text: String,
        confidence: Double = 0.80,
        sourceRepo: String = "",
    ): MeshRule? {
        val cleanTag = stackTag.trim().lowercase()
        val cleanText = text.trim()
        if (cleanTag.isBlank() || cleanText.isBlank()) return null
        val existing = entries.values.firstOrNull { it.stackTag == cleanTag && it.text.equals(cleanText, true) }
        val updated = if (existing != null) {
            existing.copy(
                hits = existing.hits + 1,
                confidence = maxOf(existing.confidence, (existing.confidence + 0.05).coerceAtMost(0.99)),
            )
        } else {
            MeshRule("mesh_${UUID.randomUUID().toString().take(8)}", cleanTag, cleanText, confidence.coerceIn(0.0, 1.0), 1, sourceRepo)
        }
        entries[updated.id] = updated
        saveToDisk()
        return updated
    }

    /** Imports active high-confidence immunity antibodies into the cross-repo mesh. */
    @Synchronized
    fun importImmunityRules(rules: List<ArchitecturalImmunity.ImmunityRule>) {
        for (r in rules) {
            if (!r.enabled || r.confidence < MIN_CONFIDENCE) continue
            record(r.stackTag, "[Antikor] ${r.suggestedFix}", r.confidence)
        }
    }

    /**
     * Selects up to [maxRules] (default 5) highest-confidence rules matching [activeTags]
     * and formats them for stable context, strictly capped at [maxChars] (default 2000).
     */
    @Synchronized
    fun selectForTags(
        activeTags: Set<String>,
        maxRules: Int = MAX_INJECTED_RULES,
        minConfidence: Double = MIN_CONFIDENCE,
    ): List<MeshRule> {
        if (activeTags.isEmpty()) return emptyList()
        val norm = activeTags.map { it.lowercase() }.toSet()
        return entries.values
            .filter { it.stackTag in norm && it.confidence >= minConfidence }
            .sortedWith(compareByDescending<MeshRule> { it.confidence }.thenByDescending { it.hits })
            .take(maxRules.coerceIn(0, MAX_INJECTED_RULES))
    }

    @Synchronized
    fun formatContext(
        activeTags: Set<String>,
        maxRules: Int = MAX_INJECTED_RULES,
        maxChars: Int = MAX_CONTEXT_CHARS,
    ): String? {
        val picked = selectForTags(activeTags, maxRules)
        if (picked.isEmpty()) return null
        val cap = maxChars.coerceIn(100, MAX_CONTEXT_CHARS)
        val sb = StringBuilder("MULTI-REPO KNOWLEDGE MESH (${activeTags.joinToString(", ")}):\n")
        for (r in picked) {
            val line = "- [${r.stackTag}] ${r.text}\n"
            if (sb.length + line.length > cap) {
                val room = cap - sb.length
                if (room > 20) sb.append(line.take(room - 1)).append('…')
                break
            }
            sb.append(line)
        }
        return sb.toString().trim().take(cap)
    }

    private fun loadFromDisk() {
        val f = file ?: return
        if (!f.isFile) return
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val r = MeshRule.fromJson(o)
                entries[r.id] = r
            }
        }
    }

    private fun saveToDisk() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val arr = JSONArray()
            entries.values.forEach { arr.put(it.toJson()) }
            f.writeText(arr.toString())
        }
    }
}
