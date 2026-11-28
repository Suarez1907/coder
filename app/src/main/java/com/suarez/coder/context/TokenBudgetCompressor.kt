package com.suarez.coder.context

import com.suarez.coder.llm.ChatMessage

/**
 * Faz 6A — Token Bütçe Sıkıştırıcı (`TokenBudgetCompressor`).
 *
 * Önbellek önekini (prefix) ve ilk `ChatMessage.User` görev mesajını HİÇ BOZMADAN,
 * yalnızca araç çıktılarındaki (`ChatMessage.ToolResult`) gürültüyü deterministik biçimde kırpar:
 * - Ardışık tekrar eden log satırlarını `[xN tekrar]` ile birleştirir.
 * - Uzun JVM/Gradle stack trace bloklarında proje (`com.suarez.coder`) ve kök neden satırlarını koruyup
 *   aradaki çerçeve (framework) gürültüsünü sıkıştırır.
 * - Değişmeyen uzun düz kod/çıktı bloklarını baştan ve sondan bağlam bırakarak daraltır.
 */
object TokenBudgetCompressor {

    const val DEFAULT_MAX_TOOL_CHARS = 8_000
    const val DEFAULT_PROTECTED_PREFIX_MESSAGES = 1

    data class CompressionStats(
        val originalChars: Int,
        val compressedChars: Int,
        val toolResultsCompressed: Int,
    ) {
        val savedChars: Int get() = (originalChars - compressedChars).coerceAtLeast(0)
    }

    /**
     * Compresses noisy [ChatMessage.ToolResult] payloads while keeping:
     * 1) the first [ChatMessage.User] task message byte-for-byte identical,
     * 2) any leading prefix messages up to [protectedPrefixCount] completely untouched (cache stability),
     * 3) all non-ToolResult messages untouched.
     */
    fun compress(
        messages: List<ChatMessage>,
        maxToolChars: Int = DEFAULT_MAX_TOOL_CHARS,
        protectedPrefixCount: Int = DEFAULT_PROTECTED_PREFIX_MESSAGES,
    ): List<ChatMessage> = compressWithStats(messages, maxToolChars, protectedPrefixCount).first

    fun compressWithStats(
        messages: List<ChatMessage>,
        maxToolChars: Int = DEFAULT_MAX_TOOL_CHARS,
        protectedPrefixCount: Int = DEFAULT_PROTECTED_PREFIX_MESSAGES,
    ): Pair<List<ChatMessage>, CompressionStats> {
        if (messages.isEmpty()) return messages to CompressionStats(0, 0, 0)
        val firstUserIdx = messages.indexOfFirst { it is ChatMessage.User }
        var origChars = 0
        var compChars = 0
        var touched = 0

        val out = messages.mapIndexed { idx, m ->
            if (idx < protectedPrefixCount || idx == firstUserIdx || m !is ChatMessage.ToolResult) {
                val len = msgLen(m)
                origChars += len
                compChars += len
                m
            } else {
                origChars += m.content.length
                val cleaned = compressToolText(m.content, maxToolChars)
                compChars += cleaned.length
                if (cleaned != m.content) touched++
                if (cleaned == m.content) m else m.copy(content = cleaned)
            }
        }
        return out to CompressionStats(origChars, compChars, touched)
    }

    /** Deterministic single tool-output compressor. */
    fun compressToolText(raw: String, maxChars: Int = DEFAULT_MAX_TOOL_CHARS): String {
        if (raw.isEmpty()) return raw
        var text = collapseRepeatedLines(raw)
        text = compressStackTraces(text)
        if (text.length > maxChars) {
            val headLen = (maxChars * 0.65).toInt().coerceAtLeast(200)
            val tailLen = (maxChars - headLen - 64).coerceAtLeast(100)
            val omitted = text.length - headLen - tailLen
            if (omitted > 0) {
                text = text.substring(0, headLen) +
                    "\n…[$omitted karakter değişmeyen/uzun blok kırpıldı]…\n" +
                    text.substring(text.length - tailLen)
            }
        }
        return text
    }

    private fun collapseRepeatedLines(raw: String): String {
        val lines = raw.lines()
        if (lines.size < 3) return raw
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val cur = lines[i]
            var count = 1
            while (i + count < lines.size && lines[i + count] == cur) {
                count++
            }
            if (count >= 3 && cur.isNotBlank()) {
                out += "$cur [x$count tekrar]"
            } else {
                for (k in 0 until count) out += cur
            }
            i += count
        }
        return out.joinToString("\n")
    }

    private val stackFrameRe = Regex("""^\s+at\s+([\w.$<>]+)\(""")

    private fun compressStackTraces(raw: String): String {
        val lines = raw.lines()
        val out = ArrayList<String>(lines.size)
        var consecutiveNoise = 0
        var keptFramesInBlock = 0
        for (line in lines) {
            val m = stackFrameRe.find(line)
            if (m != null) {
                val target = m.groupValues[1]
                val isProject = target.startsWith("com.suarez.coder")
                if (isProject || keptFramesInBlock < 3) {
                    if (consecutiveNoise > 0) {
                        out += "\t... [$consecutiveNoise çerçeve satırı gizlendi]"
                        consecutiveNoise = 0
                    }
                    out += line
                    keptFramesInBlock++
                } else {
                    consecutiveNoise++
                }
            } else {
                if (consecutiveNoise > 0) {
                    out += "\t... [$consecutiveNoise çerçeve satırı gizlendi]"
                    consecutiveNoise = 0
                }
                keptFramesInBlock = 0
                out += line
            }
        }
        if (consecutiveNoise > 0) {
            out += "\t... [$consecutiveNoise çerçeve satırı gizlendi]"
        }
        return out.joinToString("\n")
    }

    private fun msgLen(m: ChatMessage): Int = when (m) {
        is ChatMessage.User -> m.text.length
        is ChatMessage.Assistant -> m.text.length
        is ChatMessage.ToolResult -> m.content.length
        is ChatMessage.ContextBlock -> m.text.length
        else -> 0
    }
}
