package com.suarez.coder.llm

/**
 * Watches an in-flight answer and stops it when the output is clearly wasted.
 * The check is local: no model call. A cut never changes which architect model is locked.
 */
class MidStreamWatchdog(
    /** Prose overflow applies only to a non-question write turn. */
    val proseGuard: Boolean,
    /** Full-file rewrite applies only when a diff tool is in the tool list. */
    val diffToolAvailable: Boolean,
    /** Files already in context that are longer than [LARGE_FILE_LINES]. */
    val largeFiles: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    // Akış donması için en fazla beklenecek süre (ms); 0 veya daha küçükse izleme kapalı.
    val stallTimeoutMs: Long = 0L,
    // Her parça sonunda çağrılan ilerleme bildirimi (throttle uygulanmaz).
    val onProgress: ((String) -> Unit)? = null,
) {
    enum class Reason(val label: String) {
        REPEAT("tekrar"),
        PROSE("duzyazi"),
        REWRITE("tam dosya"),
    }

    data class Verdict(val cut: Boolean, val reason: Reason?, val consumedChars: Int)

    private val full = StringBuilder()
    private var prose = 0
    private var codeStarted = false
    private var toolSeen = false

    fun captured(): String = full.toString()

    /** Akıştan yeni parça geldiğinde çağrılır; donma zamanlayıcısını sıfırlar ve ilerleme bildirir. */
    fun progress(delta: String) {
        onProgress?.invoke(delta)
    }

    /** Stall izlemesi açık mı? */
    fun stallWatchEnabled(): Boolean = stallTimeoutMs > 0L

    fun observe(delta: String, sawToolCall: Boolean = false): Verdict {
        if (!enabled) return Verdict(false, null, full.length)
        if (sawToolCall) toolSeen = true
        if (delta.isNotEmpty()) {
            full.append(delta)
            if (full.length > MAX_KEEP) full.delete(0, full.length - MAX_KEEP)
            progress(delta)
        }
        val text = full.toString()
        val window = if (text.length <= WINDOW) text else text.substring(text.length - WINDOW)
        val reason = when {
            repeated(window) -> Reason.REPEAT
            proseOverflow(delta) -> Reason.PROSE
            rewrite(text) -> Reason.REWRITE
            else -> null
        }
        return Verdict(reason != null, reason, text.length)
    }

    private fun proseOverflow(delta: String): Boolean {
        if (!proseGuard || toolSeen || codeStarted) return false
        if (delta.contains("```") || full.contains("```")) {
            codeStarted = true
            return false
        }
        prose += delta.length
        return prose > PROSE_LIMIT
    }

    private fun rewrite(text: String): Boolean {
        if (!diffToolAvailable || largeFiles.isEmpty() || text.length < 400) return false
        val regions = codeRegions(text) + text
        for (src in largeFiles.values) {
            val lines = src.lines()
            if (lines.size <= LARGE_FILE_LINES) continue
            for (region in regions) {
                if (unchangedRun(lines, region.lines(), REWRITE_LINES)) return true
            }
        }
        return false
    }

    companion object {
        const val WINDOW = 1_500
        const val REPEAT_CHARS = 60
        const val REPEAT_TIMES = 3
        const val PROSE_LIMIT = 1_600
        const val REWRITE_LINES = 80
        const val LARGE_FILE_LINES = 200
        const val MAX_KEEP = 80_000
        const val CONSTRAINT =
            "[WATCHDOG]: Tekrarlayan/aşırı çıktı kesildi. Doğrudan ilgili araçla minimal diff uygula."

        /** Same line, or the same block of at least [REPEAT_CHARS], three times in a row. */
        fun repeated(window: String): Boolean {
            if (window.length < REPEAT_CHARS * REPEAT_TIMES) return false
            val lines = window.lines()
            var run = 1
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.length >= REPEAT_CHARS && line == lines[i - 1]) {
                    run++
                    if (run >= REPEAT_TIMES) return true
                } else run = 1
            }
            val n = window.length
            val maxL = minOf(n / REPEAT_TIMES, 500)
            var len = REPEAT_CHARS
            while (len <= maxL) {
                val start = n - REPEAT_TIMES * len
                if (start >= 0) {
                    val a = window.substring(start, start + len)
                    val b = window.substring(start + len, start + 2 * len)
                    val c = window.substring(start + 2 * len, start + 3 * len)
                    if (a == b && b == c) return true
                }
                len++
            }
            return false
        }

        fun unchangedRun(source: List<String>, emitted: List<String>, minLines: Int): Boolean {
            if (source.size <= LARGE_FILE_LINES || emitted.size < minLines) return false
            val want = HashMap<Int, MutableList<Int>>()
            for (i in 0..source.size - minLines) {
                want.getOrPut(hashLines(source, i, minLines)) { ArrayList() }.add(i)
            }
            for (i in 0..emitted.size - minLines) {
                val hits = want[hashLines(emitted, i, minLines)] ?: continue
                for (at in hits) {
                    var same = true
                    for (k in 0 until minLines) {
                        if (source[at + k] != emitted[i + k]) { same = false; break }
                    }
                    if (same) return true
                }
            }
            return false
        }

        private fun hashLines(lines: List<String>, at: Int, n: Int): Int {
            var h = 1
            for (i in at until at + n) h = 31 * h + lines[i].hashCode()
            return h
        }

        private fun codeRegions(text: String): List<String> {
            val out = ArrayList<String>()
            var from = 0
            while (from < text.length) {
                val open = text.indexOf("```", from)
                if (open < 0) break
                val body = text.indexOf('\n', open + 3).let { if (it < 0) open + 3 else it + 1 }
                val close = text.indexOf("```", body)
                if (close < 0) {
                    out += text.substring(body)
                    break
                }
                out += text.substring(body, close)
                from = close + 3
            }
            return out
        }
    }
}

/** The stream was cancelled. [usage] is whatever the provider had already reported, which may be empty. */
class WatchdogCut(
    val reason: MidStreamWatchdog.Reason,
    val consumedChars: Int,
    val partialText: String,
    val usage: Usage = Usage(),
) : Exception("Akış kesildi: ${reason.label}")
