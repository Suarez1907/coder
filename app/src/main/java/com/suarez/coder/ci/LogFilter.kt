package com.suarez.coder.ci

/**
 * Extracts the useful part of a CI log locally: error lines with a little context, deduplicated.
 * A failed Gradle/npm build log is often thousands of lines; the cause is usually a handful of them.
 */
object LogFilter {
    private val timestamp = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z\\s?")
    private val errorRe = Regex(
        "(^e: |^w: .*\\berror\\b|\\berror\\b|\\berror\\[|##\\[error\\]|\\bFAILED\\b|\\bFAILURE\\b|What went wrong|" +
            "\\b\\w*(Error|Exception)\\b:|Exception in thread|Caused by:|Unresolved reference|cannot find symbol|Could not (find|resolve)|" +
            "npm ERR!|Traceback|^\\s*File \".*\", line \\d+|^fatal:|\\btest failed\\b|Process completed with exit code [1-9]|exit code [1-9]|" +
            "SCRIPT ERROR|Parse Error:|\\.gd:\\d+|" +
            "manifest unknown|manifest not found)",
        RegexOption.IGNORE_CASE,
    )
    /**
     * License boilerplate ("WITHOUT WARRANTY ... FAILURE OF ESSENTIAL PURPOSE") is printed by some tools and is not an
     * error. Matched in capitals only, so real error lines that merely mention such words stay.
     */
    private val legal = Regex("\\b(WARRANTY|WARRANTIES|WARRANTED|LIABILITY|LIABLE|DAMAGES|INFRINGEMENT|NONINFRINGEMENT|MERCHANTABILITY|FITNESS|PURPOSE)\\b")
    private val noise = Regex("(warning: \\[options\\]|^\\s*at (java|kotlin|org\\.gradle|jdk)\\.|Picked up JAVA_TOOL_OPTIONS|DeprecationWarning|deprecated)", RegexOption.IGNORE_CASE)

    fun extract(log: String, context: Int = 2, maxChars: Int = 6_000): String {
        val lines = log.lines().map { it.replace(timestamp, "").trimEnd() }
        val keep = sortedSetOf<Int>()
        lines.forEachIndexed { i, l ->
            if (errorRe.containsMatchIn(l) && !noise.containsMatchIn(l) && !legal.containsMatchIn(l)) {
                for (j in (i - context).coerceAtLeast(0)..(i + context).coerceAtMost(lines.size - 1)) keep += j
            }
        }
        if (keep.isEmpty()) {
            val tail = lines.takeLast(40).joinToString("\n")
            return "(Belirgin hata satırı bulunamadı; logun son 40 satırı)\n" + tail.takeLast(maxChars)
        }
        val seen = HashSet<String>()
        val sb = StringBuilder()
        var prev = -2
        for (i in keep) {
            val l = lines[i]
            if (l.isBlank() || !seen.add(l.trim())) continue
            if (i != prev + 1 && sb.isNotEmpty()) sb.append("…\n")
            sb.append(l).append('\n')
            prev = i
            if (sb.length > maxChars) { sb.append("…(kısaltıldı)\n"); break }
        }
        return sb.toString()
    }
}
