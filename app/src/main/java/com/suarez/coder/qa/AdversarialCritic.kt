package com.suarez.coder.qa

import com.suarez.coder.data.Provider
import com.suarez.coder.llm.ModelRef
import org.json.JSONObject

/**
 * A second model reads only the PR diff and the task summary. It is never the architect, and it never writes
 * the architect selection back. One provider still means a different model at low temperature.
 */
object AdversarialCritic {
    const val APPROVE = "APPROVE"
    const val BLOCK_CRITICAL = "BLOCK_CRITICAL"
    const val MAX_FIX_ROUNDS = 1
    const val LINE_THRESHOLD = 80

    val CATEGORIES = listOf(
        "Güvenlik/Sızıntı",
        "Null/Crash Kenar Durumu",
        "Yarış Durumu/Sonsuz Döngü",
        "Kapsam Dışı/Gereksiz Değişiklik",
    )

    data class Finding(val file: String, val line: Int, val category: String, val detail: String) {
        val site: String get() = "$file:$line"
    }

    data class Verdict(val status: String, val findings: List<Finding>)

    data class Pick(
        val provider: Provider,
        val modelId: String,
        val family: String,
        val temperature: Double,
        /** Echo of the locked architect id. This value is not written anywhere. */
        val architectModelId: String,
        val changedArchitect: Boolean = false,
    )

    fun applies(risk: String, diffLines: Int): Boolean {
        val r = risk.trim().lowercase()
        val serious = r == "orta" || r == "yüksek" || r == "yuksek" || r == "medium" || r == "high"
        return serious || diffLines >= LINE_THRESHOLD
    }

    fun diffLineCount(diff: String): Int = diff.lineSequence().count { line ->
        (line.startsWith("+") || line.startsWith("-")) && !line.startsWith("+++") && !line.startsWith("---")
    }

    fun familyOf(provider: Provider, modelId: String): String {
        val id = modelId.lowercase()
        return when {
            "claude" in id || "anthropic" in id -> "anthropic"
            "gemini" in id || "gemma" in id || provider == Provider.GEMINI -> "google"
            id.startsWith("gpt") || "openai/" in id || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") -> "openai"
            "deepseek" in id || provider == Provider.DEEPSEEK -> "deepseek"
            "qwen" in id -> "qwen"
            "llama" in id -> "meta"
            "mistral" in id || "mixtral" in id -> "mistral"
            "nemotron" in id || "nvidia" in id -> "nvidia"
            else -> provider.name.lowercase()
        }
    }

    /**
     * Picks a critic from [catalog] (models the user already configured). The architect id is returned unchanged.
     */
    fun select(implementer: ModelRef, catalog: List<ModelRef>, architectModelId: String): Pick {
        val implFamily = familyOf(implementer.provider, implementer.model)
        val pool = catalog.filter { it.model.isNotBlank() }.distinctBy { it.key }
            .filterNot { it.key.equals(architectModelId, true) || it.model.equals(architectModelId.substringAfter('/'), true) && it.key.equals(architectModelId, true) }
        val otherFamily = pool.filter { familyOf(it.provider, it.model) != implFamily }
        val sameProviderOther = pool.filter { it.provider == implementer.provider && !it.model.equals(implementer.model, true) }
        val chosen = otherFamily.minByOrNull { fastRank(it.model) }
            ?: sameProviderOther.minByOrNull { fastRank(it.model) }
            ?: pool.firstOrNull { !it.model.equals(implementer.model, true) }
            ?: implementer
        val differentFamily = familyOf(chosen.provider, chosen.model) != implFamily
        val temperature = if (differentFamily) 0.2 else 0.0
        return Pick(chosen.provider, chosen.model, familyOf(chosen.provider, chosen.model), temperature, architectModelId, false)
    }

    private fun fastRank(model: String): Int {
        val id = model.lowercase()
        val hint = listOf("flash", "mini", "haiku", "nano", "lite", "small", "instant")
        return if (hint.any { it in id }) 0 else 1
    }

    fun parse(json: String): Verdict {
        val o = runCatching { JSONObject(json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }.getOrNull()
            ?: return Verdict(BLOCK_CRITICAL, listOf(Finding("?", 1, CATEGORIES[0], "Eleştirmen JSON ayrışmadı.")))
        val status = o.optString("verdict").ifBlank { o.optString("status") }
        val arr = o.optJSONArray("findings")
        val findings = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            val f = arr.optJSONObject(i) ?: return@mapNotNull null
            val file = f.optString("file").ifBlank { return@mapNotNull null }
            Finding(file, f.optInt("line", 1).coerceAtLeast(1), f.optString("category").ifBlank { CATEGORIES[0] }, f.optString("detail"))
        }
        return when (status) {
            APPROVE -> Verdict(APPROVE, emptyList())
            BLOCK_CRITICAL -> Verdict(BLOCK_CRITICAL, findings.ifEmpty { listOf(Finding("?", 1, CATEGORIES[0], "Kritik bulgu.")) })
            else -> Verdict(BLOCK_CRITICAL, listOf(Finding("?", 1, CATEGORIES[0], "Beklenmeyen hüküm: $status")))
        }
    }

    /** $0 scan used when the user has no second model call available. Secrets block; ordinary diffs pass. */
    fun heuristic(diff: String): Verdict {
        val hits = ArrayList<Finding>()
        diff.lines().forEachIndexed { i, line ->
            if (!line.startsWith("+") || line.startsWith("+++")) return@forEachIndexed
            val token = TOKEN.find(line) ?: return@forEachIndexed
            if (isPlaceholder(token.value)) return@forEachIndexed
            hits += Finding(file = "diff", line = i + 1, category = CATEGORIES[0], detail = "Eklenen satırda gizli anahtar kalıbı.")
        }
        return if (hits.isEmpty()) Verdict(APPROVE, emptyList()) else Verdict(BLOCK_CRITICAL, hits)
    }

    fun blocksMerge(verdict: Verdict?): Boolean = verdict != null && verdict.status == BLOCK_CRITICAL

    fun prompt(task: String, diff: String): String = buildString {
        append("Görevi yazan modelden bağımsız bir eleştirmen olarak yalnızca bu diff'i incele. JSON döndür: ")
        append("{\"verdict\":\"APPROVE\"|\"BLOCK_CRITICAL\",\"findings\":[{\"file\":\"a.kt\",\"line\":1,\"category\":\"")
        append(CATEGORIES.joinToString("|"))
        append("\",\"detail\":\"...\"}]}. ")
        append("Kategoriler yalnızca şunlar: ")
        append(CATEGORIES.joinToString(", "))
        append(".\nGörev: ")
        append(task.take(800))
        append("\nDiff:\n")
        append(diff.take(20_000))
    }

    private val TOKEN = Regex("""ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z\-_]{20,}|sk-or-[A-Za-z0-9\-_]{10,}|sk-ant-[A-Za-z0-9\-_]{10,}|xoxb-[A-Za-z0-9\-]{10,}""")

    private fun isPlaceholder(value: String): Boolean {
        val v = value.lowercase()
        return listOf("example", "placeholder", "dummy", "changeme", "fake", "redacted", "your_").any { it in v }
    }
}
