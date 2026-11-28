package com.suarez.coder.harness

/**
 * Faz 6A — Adli Hata Teşhisi & Delta Debugging (`ForensicDiffEngine`).
 *
 * 1) Kırmızı CI, `kotlinc` / Gradle derleme hatası, `PreFlightValidator` bulgusu veya JVM stack trace
 *    çıktılarını deterministik olarak `file`, `symbol` ve `lineRange` hedeflerine indirger.
 * 2) Çoklu dosya veya çoklu blok değişiminde klasik delta-debugging (`ddmin`) daraltması ile
 *    hatayı üreten en küçük diff alt kümesini izole eder.
 * 3) Ajana tüm dosya/log yığını yerine yalnızca sorumlu blokları içeren kompakt geri besleme üretir.
 */
object ForensicDiffEngine {

    enum class ErrorKind {
        UNRESOLVED_REFERENCE,
        TYPE_MISMATCH,
        MISSING_PARAMETER,
        SYNTAX_OR_PREFLIGHT,
        RUNTIME_EXCEPTION,
        TEST_FAILURE,
        UNKNOWN,
    }

    data class ForensicTarget(
        val file: String,
        val symbol: String?,
        val startLine: Int,
        val endLine: Int,
        val kind: ErrorKind,
        val message: String,
    )

    data class ForensicReport(
        val targets: List<ForensicTarget>,
        val rootCauseSummary: String,
    ) {
        val hasTargets: Boolean get() = targets.isNotEmpty()
        /** 0.0 (en ağır hata) .. 1.0 (hata yok) deterministik sağlık skoru. */
        val healthScore: Double
            get() = if (targets.isEmpty()) 1.0 else (1.0 / (1.0 + targets.size * 0.5)).coerceIn(0.0, 1.0)
    }

    data class DiffHunk(
        val path: String,
        val startLine: Int,
        val endLine: Int,
        val before: String?,
        val after: String?,
    )

    data class NarrowResult(
        val culpritHunks: List<DiffHunk>,
        val originalCount: Int,
        val probes: Int,
    )

    // e: file:///.../OpenAiCompatClient.kt:158:21 Unresolved reference 'close'.
    // e: app/src/main/java/.../Foo.kt: (70, 12): Unresolved reference: AutoHealer
    private val kotlincRe = Regex(
        """(?m)^(?:e:\s*)?(?:file://)?([^\s:]+\.(?:kt|kts|java|xml|gradle))(?::\s*\((\d+)(?:,\s*\d+)?\)|:(\d+)(?::\d+)?)\s*:?\s*(.+)$"""
    )

    // PreFlightValidator style: "app/.../Foo.kt:42: unbalanced brace"
    private val genericFileLineRe = Regex(
        """(?m)([\w./-]+\.(?:kt|kts|java|xml|json)):(\d+)(?::\d+)?\s*[-:]\s*(.+)$"""
    )

    // JVM stack trace frame: at com.suarez.coder.agent.Agent.run(Agent.kt:142)
    private val stackFrameRe = Regex(
        """at\s+([\w.$]+)\.([\w$<>]+)\(([\w.-]+\.(?:kt|java)):(\d+)\)"""
    )

    private val symbolQuotedRe = Regex("""['`]([A-Za-z_][\w.]*)['`]""")
    private val symbolColonRe = Regex("""Unresolved reference:\s*([A-Za-z_][\w.]*)""")

    /**
     * Parses raw CI / `kotlinc` / `PreFlightValidator` / stack trace output into deduplicated
     * file + symbol + lineRange targets.
     */
    fun diagnose(rawLog: String, contextRadius: Int = 3): ForensicReport {
        if (rawLog.isBlank()) return ForensicReport(emptyList(), "Hata kaydı boş.")
        val targets = ArrayList<ForensicTarget>()
        val seen = HashSet<String>()

        fun addTarget(file: String, line: Int, symbol: String?, message: String) {
            val cleanFile = normalizePath(file)
            val safeLine = line.coerceAtLeast(1)
            val start = (safeLine - contextRadius).coerceAtLeast(1)
            val end = safeLine + contextRadius
            val key = "$cleanFile:$safeLine:${symbol.orEmpty()}"
            if (!seen.add(key)) return
            targets += ForensicTarget(
                file = cleanFile,
                symbol = symbol ?: extractSymbol(message),
                startLine = start,
                endLine = end,
                kind = classify(message),
                message = message.trim().take(240),
            )
        }

        for (m in kotlincRe.findAll(rawLog)) {
            val file = m.groupValues[1]
            val line = (m.groupValues[2].ifBlank { m.groupValues[3] }).toIntOrNull() ?: 1
            val msg = m.groupValues[4]
            addTarget(file, line, extractSymbol(msg), msg)
        }

        if (targets.isEmpty()) {
            for (m in stackFrameRe.findAll(rawLog)) {
                val cls = m.groupValues[1]
                val method = m.groupValues[2]
                val file = m.groupValues[3]
                val line = m.groupValues[4].toIntOrNull() ?: 1
                // Prefer project frames over framework noise
                if (cls.startsWith("com.suarez.coder") || targets.isEmpty()) {
                    val excLine = rawLog.lines().firstOrNull {
                        it.contains("Exception") || it.contains("Error") || it.contains("AssertionError")
                    }?.trim() ?: "$cls.$method"
                    addTarget(file, line, method, excLine)
                }
            }
        }

        if (targets.isEmpty()) {
            for (m in genericFileLineRe.findAll(rawLog)) {
                val file = m.groupValues[1]
                val line = m.groupValues[2].toIntOrNull() ?: 1
                val msg = m.groupValues[3]
                addTarget(file, line, extractSymbol(msg), msg)
            }
        }

        val summary = if (targets.isEmpty()) {
            rawLog.lines().map { it.trim() }.firstOrNull { it.isNotBlank() }?.take(200) ?: "Bilinmeyen hata."
        } else {
            val first = targets.first()
            "${first.file}:${first.startLine}-${first.endLine}" +
                (first.symbol?.let { " ($it)" } ?: "") +
                " — ${first.message}"
        }
        return ForensicReport(targets, summary)
    }

    /**
     * Delta-debugging (`ddmin`) narrowing over multi-file / multi-hunk edits.
     * Returns the 1-minimal subset of [hunks] that still reproduces the failure according to [reproducesError].
     */
    fun narrowDiffSubset(
        hunks: List<DiffHunk>,
        reproducesError: (List<DiffHunk>) -> Boolean,
    ): NarrowResult {
        if (hunks.isEmpty()) return NarrowResult(emptyList(), 0, 0)
        var probes = 0
        fun check(candidate: List<DiffHunk>): Boolean {
            probes++
            return reproducesError(candidate)
        }
        if (!check(hunks)) return NarrowResult(emptyList(), hunks.size, probes)

        var subset = hunks
        var n = 2
        while (subset.size >= 2) {
            val parts = split(subset, n)
            var shrunk = false
            for (p in parts) {
                if (p.isNotEmpty() && check(p)) {
                    subset = p
                    n = 2
                    shrunk = true
                    break
                }
            }
            if (shrunk) continue
            for (i in parts.indices) {
                val comp = parts.filterIndexed { idx, _ -> idx != i }.flatten()
                if (comp.isNotEmpty() && check(comp)) {
                    subset = comp
                    n = (n - 1).coerceAtLeast(2)
                    shrunk = true
                    break
                }
            }
            if (!shrunk) {
                if (n >= subset.size) break
                n = minOf(subset.size, n * 2)
            }
        }
        return NarrowResult(subset, hunks.size, probes)
    }

    /**
     * Builds a compact agent-ready feedback snippet containing ONLY the culprit targets and narrowed hunks.
     */
    fun buildFocusedFeedback(report: ForensicReport, culpritHunks: List<DiffHunk> = emptyList()): String = buildString {
        append("ADLİ HATA TEŞHİSİ:\n")
        if (report.targets.isEmpty()) {
            append("- Özet: ").append(report.rootCauseSummary).append('\n')
        } else {
            for (t in report.targets.take(6)) {
                append("- ").append(t.file).append(':').append(t.startLine).append('-').append(t.endLine)
                if (!t.symbol.isNullOrBlank()) append(" [").append(t.symbol).append(']')
                append(" (").append(t.kind.name).append("): ").append(t.message).append('\n')
            }
        }
        if (culpritHunks.isNotEmpty()) {
            append("\nSORUMLU MİNİMAL DIFF BLOKLARI (${culpritHunks.size}):\n")
            for (h in culpritHunks.take(4)) {
                append("--- ").append(h.path).append(" (L").append(h.startLine).append("-L").append(h.endLine).append(") ---\n")
                append((h.after ?: "(silindi)").take(600)).append('\n')
            }
        }
    }.trim()

    private fun normalizePath(raw: String): String {
        val clean = raw.replace('\\', '/')
        val idx = clean.indexOf("app/src/")
        return if (idx >= 0) clean.substring(idx) else clean.substringAfterLast('/')
    }

    private fun extractSymbol(msg: String): String? =
        symbolColonRe.find(msg)?.groupValues?.get(1)
            ?: symbolQuotedRe.find(msg)?.groupValues?.get(1)

    private fun classify(msg: String): ErrorKind {
        val l = msg.lowercase()
        return when {
            "unresolved reference" in l -> ErrorKind.UNRESOLVED_REFERENCE
            "type mismatch" in l || "argument type mismatch" in l -> ErrorKind.TYPE_MISMATCH
            "no value passed for parameter" in l || "missing parameter" in l -> ErrorKind.MISSING_PARAMETER
            "brace" in l || "syntax" in l || "expecting" in l || "preflight" in l -> ErrorKind.SYNTAX_OR_PREFLIGHT
            "assertionerror" in l || "expected:" in l -> ErrorKind.TEST_FAILURE
            "exception" in l || "nullpointer" in l -> ErrorKind.RUNTIME_EXCEPTION
            else -> ErrorKind.UNKNOWN
        }
    }

    private fun <T> split(items: List<T>, n: Int): List<List<T>> {
        val out = ArrayList<List<T>>(n)
        val base = items.size / n
        val rem = items.size % n
        var pos = 0
        for (i in 0 until n) {
            val sz = base + if (i < rem) 1 else 0
            out += items.subList(pos, pos + sz)
            pos += sz
        }
        return out
    }
}
