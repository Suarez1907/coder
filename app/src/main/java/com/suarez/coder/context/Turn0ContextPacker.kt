package com.suarez.coder.context

import com.suarez.coder.decision.RouterLogic

/**
 * Deterministic Turn-0 context pack. Built before the coding model is called, so the search loop that
 * re-reads the whole history does not start. The pack is appended to the first task message only.
 * It is never written into the stable system prefix, which keeps the KV cache prefix intact.
 */
object Turn0ContextPacker {
    const val HEADER = "[TURN-0 BAĞLAM PAKETİ]"
    const val NOTE = "Gerekli temel semboller aşağıda verildi; ek dosya gerekmiyorsa doğrudan düzenlemeye geç."
    const val DEFAULT_CHAR_CAP = 14_000
    const val LARGE_FILE_LINES = 250
    const val MIN_FILES = 3
    const val MAX_FILES = 6
    const val MAX_RULES = 5

    enum class TaskKind { QUESTION, WRITE }

    data class Source(val path: String, val text: String)

    data class Request(
        val task: String,
        val taskKind: TaskKind,
        val sources: List<Source>,
        val graph: RepoDependencyGraph = RepoDependencyGraph(),
        /** Highest-first paths from Decision / Jev. Empty means the local $0 ranker decides. */
        val ranked: List<String> = emptyList(),
        val rules: List<String> = emptyList(),
        val charCap: Int = DEFAULT_CHAR_CAP,
    )

    data class Pack(
        val used: Boolean,
        val block: String,
        val charsPacked: Int,
        val paths: List<String>,
    )

    fun pack(req: Request): Pack {
        if (req.taskKind == TaskKind.QUESTION) return Pack(false, "", 0, emptyList())
        val byPath = LinkedHashMap<String, Source>()
        req.sources.forEach { s ->
            val key = s.path.trim().removePrefix("./")
            if (key.isNotBlank() && s.text.isNotEmpty()) byPath.putIfAbsent(key, s.copy(path = key))
        }
        if (byPath.isEmpty()) return Pack(false, "", 0, emptyList())
        val selected = select(req.task, byPath.keys.toList(), req.ranked)
        if (selected.isEmpty()) return Pack(false, "", 0, emptyList())
        val targets = selected.mapNotNull { byPath[it] }
        val symbols = StringBuilder()
        val calls = StringBuilder()
        val tests = StringBuilder()
        val usedPaths = ArrayList<String>()
        for (file in targets) {
            if (isTestPath(file.path)) continue
            usedPaths += file.path
            appendTarget(symbols, file, req.task, req.graph)
            appendCalls(calls, file, req.task, req.graph, byPath)
        }
        val testFiles = targets.filter { isTestPath(it.path) } +
            selected.flatMap { testCompanions(it, byPath) }.distinctBy { it.path }
        for (test in testFiles.distinctBy { it.path }.take(3)) {
            if (test.path !in usedPaths) usedPaths += test.path
            appendTest(tests, test, req.task)
        }
        val ruleLines = req.rules.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_RULES)
        val rules = if (ruleLines.isEmpty()) "" else ruleLines.joinToString("\n", prefix = "## Kurallar\n") { "- $it" }
        val header = "$HEADER\n$NOTE"
        val block = assemble(
            req.charCap.coerceAtLeast(header.length.coerceAtMost(req.charCap)),
            header,
            symbols.toString().trimEnd(),
            calls.toString().trimEnd(),
            tests.toString().trimEnd(),
            rules,
        )
        if (block.isBlank()) return Pack(false, "", 0, emptyList())
        return Pack(true, block, block.length, usedPaths.distinct())
    }

    /**
     * Appends [pack] to the task text. [system] is returned as the same instance: the pack is not copied into it.
     */
    fun attach(system: String, userText: String, pack: Pack): Pair<String, String> {
        if (!pack.used || pack.block.isEmpty()) return system to userText
        val user = if (userText.contains(HEADER)) userText else userText.trimEnd() + "\n\n" + pack.block
        return system to user
    }

    fun select(task: String, paths: List<String>, ranked: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val known = paths.toSet()
        val local = RouterLogic.rank(task, paths)
        val preferred = ranked.map { it.trim().removePrefix("./") }.filter { it in known }
        val merged = (preferred + local).distinct()
        val code = merged.filter { codeExt(it) in CODE_EXT || isTestPath(it) }
        val pool = if (code.isNotEmpty()) code else merged
        val cap = MAX_FILES.coerceAtMost(pool.size)
        val floor = MIN_FILES.coerceAtMost(pool.size)
        val n = cap.coerceAtLeast(floor)
        return pool.take(n)
    }

    private fun appendTarget(out: StringBuilder, file: Source, task: String, graph: RepoDependencyGraph) {
        val lines = file.text.lines()
        if (out.isEmpty()) out.append("## Hedef semboller\n")
        if (lines.size <= LARGE_FILE_LINES) {
            fence(out, file.path, 1, lines.size, file.text.trimEnd())
            return
        }
        val symbols = graph.symbolsOf(file.path).ifEmpty { SymbolParse.symbols(file.path, file.text) }
        val picked = rankSymbols(task, symbols).take(4)
        if (picked.isEmpty()) {
            val head = lines.take(80).joinToString("\n")
            fence(out, file.path, 1, minOf(80, lines.size), head)
            return
        }
        for (sym in picked) {
            val range = bodyRange(lines, sym.line)
            val body = lines.subList(range.first - 1, range.last).joinToString("\n")
            fence(out, "${file.path}:${sym.kind} ${sym.name}", range.first, range.last, body)
        }
    }

    private fun appendCalls(
        out: StringBuilder,
        file: Source,
        task: String,
        graph: RepoDependencyGraph,
        byPath: Map<String, Source>,
    ) {
        val symbols = graph.symbolsOf(file.path).ifEmpty { SymbolParse.symbols(file.path, file.text) }
        val picked = rankSymbols(task, symbols).take(4).ifEmpty { symbols.take(2) }
        for (sym in picked) {
            val hits = graph.impact(file.path, sym.name).take(6)
            for (hit in hits) {
                val src = byPath[hit.path]?.text ?: graph.source(hit.path) ?: continue
                val line = src.lines().getOrNull(hit.line - 1) ?: continue
                if (out.isEmpty()) out.append("## Çağrı noktaları\n")
                out.append("### ").append(hit.path).append(':').append(hit.line).append(' ').append(hit.kind)
                    .append(' ').append(hit.symbol).append('\n')
                out.append(line.trimEnd()).append("\n\n")
            }
        }
    }

    private fun appendTest(out: StringBuilder, test: Source, task: String) {
        val lines = test.text.lines()
        if (out.isEmpty()) out.append("## Test\n")
        if (lines.size <= 80) {
            fence(out, test.path, 1, lines.size, test.text.trimEnd())
            return
        }
        val symbols = SymbolParse.symbols(test.path, test.text)
        val picked = rankSymbols(task, symbols).take(3).ifEmpty { symbols.take(2) }
        if (picked.isEmpty()) {
            fence(out, test.path, 1, 60, lines.take(60).joinToString("\n"))
            return
        }
        for (sym in picked) {
            val range = bodyRange(lines, sym.line)
            fence(out, test.path, range.first, range.last, lines.subList(range.first - 1, range.last).joinToString("\n"))
        }
    }

    private fun fence(out: StringBuilder, title: String, start: Int, end: Int, body: String) {
        out.append("### ").append(title)
        if (!title.contains(':')) out.append(':').append(start).append('-').append(end)
        out.append('\n').append(body.trimEnd()).append("\n\n")
    }

    private fun rankSymbols(task: String, symbols: List<RepoDependencyGraph.Symbol>): List<RepoDependencyGraph.Symbol> {
        if (symbols.isEmpty()) return emptyList()
        val words = Regex("[\\p{L}\\p{N}_]{3,}").findAll(task).map { it.value.lowercase() }.toSet()
        return symbols.sortedByDescending { sym ->
            val n = sym.name.lowercase()
            var s = 0
            if (task.contains(sym.name)) s += 50
            s += words.count { n.contains(it) || it.contains(n) } * 10
            if (sym.kind in setOf("fun", "def", "func", "method")) s += 2
            s
        }
    }

    private fun testCompanions(path: String, byPath: Map<String, Source>): List<Source> {
        val base = path.substringAfterLast('/').substringBeforeLast('.')
        if (base.isBlank()) return emptyList()
        return byPath.values.filter { src ->
            src.path != path && isTestPath(src.path) && src.path.substringAfterLast('/').contains(base, ignoreCase = true)
        }
    }

    fun isTestPath(path: String): Boolean {
        val name = path.substringAfterLast('/')
        val stem = name.substringBeforeLast('.')
        return "/test/" in path || "/androidTest/" in path ||
            name.endsWith("Test.kt") || name.endsWith("Test.java") || name.endsWith("_test.py") ||
            name.startsWith("test_") || stem.endsWith("_test")
    }

    private fun bodyRange(lines: List<String>, startLine: Int): IntRange {
        if (lines.isEmpty()) return 1..1
        val def = (startLine - 1).coerceIn(0, lines.lastIndex)
        var start = def
        while (start > 0) {
            val prev = lines[start - 1].trim()
            if (prev.startsWith("@") || prev.startsWith("//") || prev.startsWith("#") || prev.startsWith("/*") ||
                prev.startsWith("*") || prev.startsWith("*/")
            ) start-- else break
        }
        val end = endOf(lines, def)
        return (start + 1)..(end + 1)
    }

    private fun endOf(lines: List<String>, def: Int): Int {
        val first = lines[def]
        val base = first.length - first.trimStart().length
        if (first.trimEnd().endsWith(":") && Regex("^\\s*(async\\s+)?(def|class|func)\\b").containsMatchIn(first)) {
            var i = def + 1
            var last = def
            while (i < lines.size) {
                val l = lines[i]
                if (l.isNotBlank()) {
                    val ind = l.length - l.trimStart().length
                    if (ind <= base) break
                    last = i
                }
                i++
            }
            return last
        }
        var depth = 0
        var opened = false
        val stop = minOf(lines.size, def + 220)
        for (i in def until stop) {
            val raw = stripStrings(lines[i])
            for (c in raw) {
                if (c == '{') { depth++; opened = true } else if (c == '}') depth--
            }
            if (opened && depth <= 0) return i
        }
        return (def + 40).coerceAtMost(lines.lastIndex)
    }

    private fun stripStrings(line: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"' || c == '\'') {
                val q = c
                sb.append(' ')
                i++
                while (i < line.length && line[i] != q) {
                    if (line[i] == '\\') i++
                    sb.append(' ')
                    i++
                }
                if (i < line.length) { sb.append(' '); i++ }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun assemble(cap: Int, header: String, symbols: String, calls: String, tests: String, rules: String): String {
        val limit = cap.coerceAtLeast(0)
        if (limit == 0) return ""
        val out = StringBuilder()
        fun add(section: String, truncate: Boolean): Boolean {
            if (section.isBlank()) return true
            val sep = if (out.isEmpty()) "" else "\n"
            val room = limit - out.length - sep.length
            if (room <= 0) return false
            if (section.length <= room) {
                out.append(sep).append(section)
                return true
            }
            if (!truncate) return false
            val cut = section.take(room).trimEnd()
            if (cut.isNotEmpty()) out.append(sep).append(cut)
            return false
        }
        add(header.take(limit), true)
        // Priority: target symbols, call sites, tests, rules.
        // The first section that does not fit is cut to the remaining room; nothing after it is added.
        if (!add(symbols, true)) return out.toString()
        if (!add(calls, false)) return out.toString()
        if (!add(tests, false)) return out.toString()
        add(rules, false)
        return out.toString()
    }

    private fun codeExt(path: String) = path.substringAfterLast('.', "").lowercase()

    private val CODE_EXT = setOf("kt", "kts", "java", "py", "gd")
}
