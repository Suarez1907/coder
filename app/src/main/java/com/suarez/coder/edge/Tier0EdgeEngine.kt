package com.suarez.coder.edge

import java.io.File

/** Optional on-device model. A missing native bridge or an empty path must not throw. */
interface LocalEdgeModelBackend {
    val available: Boolean
    val accelerator: String
    suspend fun complete(prompt: String): String?
}

/** Deterministic stand-in. No weights, no network, no crash. */
class HeuristicFallbackBackend : LocalEdgeModelBackend {
    override val available = true
    override val accelerator = "NONE"
    override suspend fun complete(prompt: String): String? = null
}

/**
 * Looks for a user-picked .litertlm / .task / .gguf file. Native LiteRT is not packaged in the APK.
 * If the bridge class is absent the engine stays on [HeuristicFallbackBackend].
 */
class OptionalEdgeBackend(private val modelPath: String?) : LocalEdgeModelBackend {
    override val available: Boolean = !modelPath.isNullOrBlank() && File(modelPath).exists() && bridgePresent()
    override val accelerator: String = when {
        modelPath.isNullOrBlank() -> "NONE"
        !bridgePresent() -> "NONE"
        else -> "CPU"
    }

    override suspend fun complete(prompt: String): String? = null

    private fun bridgePresent(): Boolean = runCatching {
        Class.forName("com.google.ai.edge.litert.Engine")
        true
    }.getOrDefault(false)

    fun status(): String = when {
        modelPath.isNullOrBlank() -> "Model seçilmedi · HeuristicFallback"
        !modelPath.substringAfterLast('.').lowercase().let { it in EXTENSIONS } -> "Desteklenmeyen dosya · HeuristicFallback"
        !bridgePresent() -> "Model dosyası hazır · NPU/GPU/CPU köprüsü yok · HeuristicFallback"
        else -> "Yerel model · $accelerator"
    }

    companion object {
        val EXTENSIONS = setOf("litertlm", "task", "gguf")
    }
}

data class Tier0Flags(val redact: Boolean = true, val voice: Boolean = true, val classify: Boolean = true)

data class Redaction(val masked: String, val vault: List<Pair<String, String>>)

enum class Tier0Class { SIMPLE_QUESTION, FILE_INTENT, COMPLEX, OTHER }

/**
 * On-device tier-0. Every path has a deterministic fallback, so a missing model file changes nothing
 * except that the cloud still receives masked text when redaction is on.
 */
class Tier0EdgeEngine(
    private val backend: LocalEdgeModelBackend = HeuristicFallbackBackend(),
    private val flags: Tier0Flags = Tier0Flags(),
) {
    val accelerator: String get() = backend.accelerator

    fun redact(text: String): Redaction {
        if (!flags.redact) return Redaction(text, emptyList())
        val vault = ArrayList<Pair<String, String>>()
        var out = text
        val hits = ArrayList<Pair<IntRange, String>>()
        PATTERNS.forEach { re -> re.findAll(text).forEach { hits += it.range to it.value } }
        val ordered = hits.sortedBy { it.first.first }.fold(ArrayList<Pair<IntRange, String>>()) { acc, hit ->
            if (acc.any { it.first.first <= hit.first.first && hit.first.last <= it.first.last }) return@fold acc
            val value = hit.second
            if (value.endsWith("@example.com", true) || value.endsWith("@test.com", true)) return@fold acc
            acc += hit
            acc
        }
        ordered.asReversed().forEachIndexed { rev, hit ->
            val n = ordered.size - rev
            val token = "[REDACTED_$n]"
            vault += token to hit.second
            out = out.replaceRange(hit.first, token)
        }
        vault.reverse()
        return Redaction(out, vault)
    }

    fun restore(text: String, vault: List<Pair<String, String>>): String {
        var out = text
        vault.forEach { (token, value) -> out = out.replace(token, value) }
        return out
    }

    suspend fun structureVoice(text: String): String {
        if (!flags.voice) return text
        val drafted = runCatching { backend.complete("voice:\n$text") }.getOrNull()
        if (!drafted.isNullOrBlank()) return drafted
        return structureVoiceNow(text)
    }

    fun structureVoiceNow(text: String): String = if (!flags.voice) text else heuristicVoice(text)

    fun classify(text: String): Tier0Class {
        if (!flags.classify) return Tier0Class.OTHER
        val t = text.lowercase()
        val writes = listOf("ekle", "düzelt", "yaz", "sil", "refactor", "değiştir", "oluştur").any { it in t }
        if (!writes && t.length < 180 && (t.contains("?") || t.contains("nedir") || t.contains("ne işe") || t.startsWith("açıkla"))) {
            return Tier0Class.SIMPLE_QUESTION
        }
        if (Regex("""[\w./-]+\.[a-z0-9]{1,5}""").containsMatchIn(t) || "dosya" in t || "bu sınıf" in t) return Tier0Class.FILE_INTENT
        if (writes || t.length > 240) return Tier0Class.COMPLEX
        return Tier0Class.OTHER
    }

    private fun heuristicVoice(raw: String): String {
        val parts = raw.split(Regex("(?i)\\s*,?\\s*bir de\\s+|\\s*,\\s*ayrıca\\s+|\\s*;\\s+"))
            .map { it.trim().trim('.', ' ') }.filter { it.isNotBlank() }
        val lines = parts.mapIndexed { i, part ->
            var p = part
            p = Regex("^şurdaki\\s+", RegexOption.IGNORE_CASE).replace(p, "")
            p = Regex("\\bbüyüt\\b", RegexOption.IGNORE_CASE).replace(p, "boyutunu artır")
            p = Regex("\\bkesilmesin\\b", RegexOption.IGNORE_CASE).replace(p, "kırpılmasın; kenar boşluğu ve durum çubuğu ile çakışmasın")
            "${i + 1}. ${p.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}"
        }
        return "Hedef: dağınık isteği teknik maddeye çevir.\n" + lines.joinToString("\n") +
            "\nKabul: maddeler uygulanır, istenmeyen yeniden tasarım yok."
    }

    companion object {
        private val PATTERNS = listOf(
            Regex("ghp_[A-Za-z0-9]{20,}"),
            Regex("github_pat_[A-Za-z0-9_]{20,}"),
            Regex("AIza[0-9A-Za-z\\-_]{20,}"),
            Regex("sk-or-[A-Za-z0-9\\-_]{10,}"),
            Regex("sk-ant-[A-Za-z0-9\\-_]{10,}"),
            Regex("xoxb-[A-Za-z0-9\\-]{10,}"),
            Regex("-----BEGIN PRIVATE KEY-----[\\s\\S]*?-----END PRIVATE KEY-----"),
            Regex("\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b"),
            Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        )
    }
}

/** Offline prep queue. Items wait on disk until the device is online again. */
class PrepQueue(private val file: File) {
    data class Item(val at: Long, val kind: String, val text: String)

    fun enqueue(kind: String, text: String, at: Long = System.currentTimeMillis()) {
        val all = load() + Item(at, kind, text.take(4_000))
        file.parentFile?.mkdirs()
        file.writeText(all.takeLast(40).joinToString("\n") { "${it.at}\t${it.kind}\t${it.text.replace('\n', ' ')}" })
    }

    fun load(): List<Item> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { line ->
            val p = line.split('\t', limit = 3)
            if (p.size < 3) return@mapNotNull null
            Item(p[0].toLongOrNull() ?: 0L, p[1], p[2])
        }
    }.getOrDefault(emptyList())
}
