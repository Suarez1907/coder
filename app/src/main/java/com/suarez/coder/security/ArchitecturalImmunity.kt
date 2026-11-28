package com.suarez.coder.security

import com.suarez.coder.harness.ForensicDiffEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Faz 6B — Mimari Bağışıklık Sistemi (`ArchitecturalImmunity`).
 *
 * Geçmiş kırmızı CI loglarından, [ForensicDiffEngine] bulgularından ve `CommitGuards` retlerinden
 * kalıcı, deterministik "antikor/kural" kayıtları ([ImmunityRule]) üretir ve JSON deposunda saklar.
 * Pre-commit aşamasında daha önce yakalanmış bir mimari/regresyon hatası yeniden yazılmaya çalışılırsa
 * commit öncesi anında engeller. Her kuralda yanlış pozitif koruması için [confidence] eşiği ve
 * [enabled] anahtarı bulunur.
 */
object ArchitecturalImmunity {

    const val DEFAULT_CONFIDENCE_THRESHOLD = 0.70

    data class ImmunityRule(
        val id: String,
        val triggerPattern: String,
        val forbiddenAntiPattern: String,
        val suggestedFix: String,
        val hits: Int = 0,
        val lastSeenMs: Long = System.currentTimeMillis(),
        val confidence: Double = 0.80,
        val enabled: Boolean = true,
        val stackTag: String = "kotlin",
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("triggerPattern", triggerPattern)
            .put("forbiddenAntiPattern", forbiddenAntiPattern)
            .put("suggestedFix", suggestedFix)
            .put("hits", hits)
            .put("lastSeenMs", lastSeenMs)
            .put("confidence", confidence)
            .put("enabled", enabled)
            .put("stackTag", stackTag)

        companion object {
            fun fromJson(o: JSONObject): ImmunityRule = ImmunityRule(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                triggerPattern = o.optString("triggerPattern"),
                forbiddenAntiPattern = o.optString("forbiddenAntiPattern"),
                suggestedFix = o.optString("suggestedFix"),
                hits = o.optInt("hits", 0),
                lastSeenMs = o.optLong("lastSeenMs", 0L),
                confidence = o.optDouble("confidence", 0.80).coerceIn(0.0, 1.0),
                enabled = o.optBoolean("enabled", true),
                stackTag = o.optString("stackTag", "kotlin").ifBlank { "kotlin" },
            )
        }
    }

    data class Match(
        val path: String,
        val rule: ImmunityRule,
        val excerpt: String,
    )

    class Store(private val file: File? = null) {
        private val rules = LinkedHashMap<String, ImmunityRule>()

        init {
            loadFromDisk()
        }

        @Synchronized
        fun all(): List<ImmunityRule> = rules.values.toList()

        @Synchronized
        fun active(minConfidence: Double = DEFAULT_CONFIDENCE_THRESHOLD): List<ImmunityRule> =
            rules.values.filter { it.enabled && it.confidence >= minConfidence }

        @Synchronized
        fun upsert(rule: ImmunityRule): ImmunityRule {
            val existing = rules[rule.id] ?: rules.values.firstOrNull {
                it.forbiddenAntiPattern == rule.forbiddenAntiPattern && it.stackTag == rule.stackTag
            }
            val merged = if (existing != null) {
                existing.copy(
                    triggerPattern = rule.triggerPattern.ifBlank { existing.triggerPattern },
                    suggestedFix = rule.suggestedFix.ifBlank { existing.suggestedFix },
                    hits = existing.hits + rule.hits.coerceAtLeast(1),
                    lastSeenMs = maxOf(existing.lastSeenMs, rule.lastSeenMs),
                    confidence = maxOf(existing.confidence, (existing.confidence + 0.08).coerceAtMost(0.99)),
                )
            } else {
                rule
            }
            rules[merged.id] = merged
            saveToDisk()
            return merged
        }

        /** Enables or disables a rule to prevent false positives. */
        @Synchronized
        fun setEnabled(ruleId: String, enabled: Boolean): Boolean {
            val cur = rules[ruleId] ?: return false
            rules[ruleId] = cur.copy(enabled = enabled)
            saveToDisk()
            return true
        }

        /**
         * Learns a new deterministic antibody rule from a red CI log / ForensicDiffEngine report
         * or CommitGuards rejection.
         */
        @Synchronized
        fun learnFromForensic(
            rawLog: String,
            forbiddenAntiPattern: String,
            suggestedFix: String,
            stackTag: String = "kotlin",
            confidence: Double = 0.80,
            nowMs: Long = System.currentTimeMillis(),
        ): ImmunityRule {
            val report = ForensicDiffEngine.diagnose(rawLog)
            val trigger = report.targets.firstOrNull()?.let { t ->
                t.symbol?.let { Regex.escape(it) } ?: Regex.escape(t.message.take(80))
            } ?: Regex.escape(rawLog.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(80))
            val rule = ImmunityRule(
                id = "imm_${UUID.randomUUID().toString().take(8)}",
                triggerPattern = trigger,
                forbiddenAntiPattern = forbiddenAntiPattern,
                suggestedFix = suggestedFix,
                hits = 1,
                lastSeenMs = nowMs,
                confidence = confidence.coerceIn(0.0, 1.0),
                enabled = true,
                stackTag = stackTag.lowercase(),
            )
            return upsert(rule)
        }

        /**
         * Checks changed files against active high-confidence immunity rules.
         * Triggers only when [forbiddenAntiPattern] appears in newly added/modified content
         * (not pre-existing untouched lines).
         */
        @Synchronized
        fun checkAll(
            changes: List<Triple<String, String?, String?>>,
            minConfidence: Double = DEFAULT_CONFIDENCE_THRESHOLD,
            nowMs: Long = System.currentTimeMillis(),
        ): List<Match> {
            val activeRules = active(minConfidence)
            if (activeRules.isEmpty()) return emptyList()
            val matches = ArrayList<Match>()
            var dirty = false
            for ((path, before, after) in changes) {
                if (after == null || after == before) continue
                val beforeLines = before?.lines()?.toSet().orEmpty()
                val addedText = after.lines().filter { it !in beforeLines }.joinToString("\n")
                if (addedText.isBlank()) continue
                for (rule in activeRules) {
                    if (rule.forbiddenAntiPattern.isBlank()) continue
                    val re = runCatching { Regex(rule.forbiddenAntiPattern) }.getOrNull() ?: continue
                    val found = re.find(addedText) ?: continue
                    val updated = rule.copy(hits = rule.hits + 1, lastSeenMs = nowMs)
                    rules[rule.id] = updated
                    dirty = true
                    matches += Match(path, updated, found.value.take(120))
                }
            }
            if (dirty) saveToDisk()
            return matches
        }

        @Synchronized
        fun toJsonString(): String =
            JSONArray().also { arr -> rules.values.forEach { arr.put(it.toJson()) } }.toString()

        @Synchronized
        fun loadJsonString(raw: String) {
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val r = ImmunityRule.fromJson(obj)
                rules[r.id] = r
            }
        }

        private fun loadFromDisk() {
            val f = file ?: return
            if (!f.isFile) return
            runCatching { loadJsonString(f.readText()) }
        }

        private fun saveToDisk() {
            val f = file ?: return
            runCatching {
                f.parentFile?.mkdirs()
                f.writeText(toJsonString())
            }
        }
    }

    fun formatBlockMessage(matches: List<Match>): String = buildString {
        append("Mimari Bağışıklık Sistemi bilinen regresyon desenini yakaladı (commit durduruldu):\n")
        for (m in matches) {
            append("- ").append(m.path).append(" [kural: ").append(m.rule.id).append(", güven: ")
                .append("%.2f".format(m.rule.confidence)).append("]: `").append(m.excerpt)
                .append("` → Önerilen düzeltme: ").append(m.rule.suggestedFix).append('\n')
        }
    }.trim()
}
