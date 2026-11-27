package com.suarez.coder.llm

import okio.BufferedSource

/**
 * Reads an OpenAI-compatible JSON body in small pieces and feeds assistant text to [MidStreamWatchdog]
 * before the rest of the body is consumed. A cut calls [cancel] immediately, which is [okhttp3.Call.cancel]
 * for a live response.
 */
object JsonBodyWatch {
    const val DEFAULT_CHUNK = 128

    data class Result(val raw: String, val cut: MidStreamWatchdog.Verdict?, val text: String)

    fun read(
        source: BufferedSource,
        dog: MidStreamWatchdog,
        cancel: () -> Unit,
        chunkCodePoints: Int = DEFAULT_CHUNK,
    ): Result {
        val raw = StringBuilder()
        val tap = ContentTap(dog)
        while (!source.exhausted()) {
            if (!source.request(1)) break
            val chunk = StringBuilder()
            var n = 0
            val size = chunkCodePoints.coerceAtLeast(1)
            while (n < size && !source.exhausted() && source.request(1)) {
                chunk.appendCodePoint(source.readUtf8CodePoint())
                n++
            }
            if (chunk.isEmpty()) break
            raw.append(chunk)
            val verdict = tap.push(chunk.toString())
            if (verdict != null && verdict.cut) {
                cancel()
                return Result(raw.toString(), verdict, tap.text())
            }
        }
        return Result(raw.toString(), null, tap.text())
    }

    /** Pulls the first `"content":"..."` string out of a JSON body as characters arrive. */
    internal class ContentTap(private val dog: MidStreamWatchdog) {
        private val raw = StringBuilder()
        private val text = StringBuilder()
        private var scanAt = 0
        private var inString = false
        private var closed = false
        private var escape = false
        private var unicodeLeft = 0
        private val unicode = StringBuilder()
        private var tool = false

        fun text(): String = text.toString()

        fun push(chunk: String): MidStreamWatchdog.Verdict? {
            raw.append(chunk)
            while (scanAt < raw.length) {
                if (!inString) {
                    if (closed) {
                        val rest = raw.substring(scanAt)
                        val toolAt = rest.indexOf("\"tool_calls\"")
                        if (toolAt < 0) {
                            scanAt = raw.length - minOf(rest.length, HOLD)
                            return null
                        }
                        tool = true
                        scanAt += toolAt + TOOL_KEY.length
                        continue
                    }
                    val rest = raw.substring(scanAt)
                    val key = KEY.find(rest)
                    val toolAt = rest.indexOf("\"tool_calls\"")
                    if (key == null && toolAt < 0) {
                        scanAt = raw.length - minOf(rest.length, HOLD)
                        return null
                    }
                    if (toolAt >= 0 && (key == null || toolAt < key.range.first)) {
                        tool = true
                        scanAt += toolAt + TOOL_KEY.length
                        continue
                    }
                    scanAt += key!!.range.last + 1
                    inString = true
                    escape = false
                    unicodeLeft = 0
                    continue
                }
                if (scanAt >= raw.length) return null
                val c = raw[scanAt]
                if (unicodeLeft > 0) {
                    unicode.append(c)
                    scanAt++
                    unicodeLeft--
                    if (unicodeLeft == 0) {
                        val decoded = unicode.toString().toIntOrNull(16)?.toChar()?.toString().orEmpty()
                        unicode.clear()
                        emit(decoded)?.let { return it }
                    }
                    continue
                }
                if (escape) {
                    escape = false
                    scanAt++
                    if (c == 'u') {
                        unicodeLeft = 4
                        continue
                    }
                    val decoded = when (c) {
                        'n' -> "\n"
                        't' -> "\t"
                        'r' -> "\r"
                        'b' -> "\b"
                        'f' -> "\u000C"
                        else -> c.toString()
                    }
                    emit(decoded)?.let { return it }
                    continue
                }
                scanAt++
                when (c) {
                    '\\' -> escape = true
                    '"' -> {
                        inString = false
                        closed = true
                    }
                    else -> emit(c.toString())?.let { return it }
                }
            }
            return null
        }

        private fun emit(s: String): MidStreamWatchdog.Verdict? {
            if (s.isEmpty()) return null
            text.append(s)
            val verdict = dog.observe(s, tool)
            return verdict.takeIf { it.cut }
        }

        companion object {
            private const val HOLD = 24
            private const val TOOL_KEY = "\"tool_calls\""
            private val KEY = Regex("\"content\"\\s*:\\s*\"")
        }
    }
}
