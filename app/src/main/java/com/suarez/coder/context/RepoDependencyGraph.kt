package com.suarez.coder.context

/**
 * Lightweight call/import graph for .kt, .java, .py and .gd. Only files whose blob SHA changed are reparsed.
 * The graph is local: building or querying it never calls a model.
 */
class RepoDependencyGraph {
    data class Symbol(
        val name: String,
        val kind: String,
        val line: Int,
        val requiredParams: Int,
    )

    data class Indexed(val sha: String, val pkg: String, val symbols: List<Symbol>)

    data class Impact(val path: String, val line: Int, val symbol: String, val kind: String)

    private val files = LinkedHashMap<String, Indexed>()
    private val texts = LinkedHashMap<String, String>()

    fun indexedPaths(): Set<String> = files.keys.toSet()

    /** Indexed source, or null when this path was never parsed. */
    fun source(path: String): String? {
        val key = resolve(path) ?: return null
        return texts[key]
    }

    fun sources(): Map<String, String> = texts.toMap()

    fun remove(path: String) {
        val key = resolve(path) ?: path.trim().removePrefix("./")
        files.remove(key)
        texts.remove(key)
    }

    fun hasIndex(): Boolean = files.isNotEmpty()

    fun needs(path: String, sha: String): Boolean = files[path]?.sha != sha

    /** @return true when the file was reparsed. Same SHA keeps the previous symbols. */
    fun update(path: String, sha: String, text: String): Boolean {
        val key = path.trim().removePrefix("./")
        if (key.isBlank()) return false
        val prev = files[key]
        if (prev != null && prev.sha == sha) return false
        files[key] = Indexed(sha, SymbolParse.packageOf(text), SymbolParse.symbols(key, text))
        texts[key] = text
        return true
    }

    fun retain(paths: Set<String>) {
        val keep = paths.map { it.trim().removePrefix("./") }.toSet()
        files.keys.toList().filter { it !in keep }.forEach { files.remove(it); texts.remove(it) }
    }

    fun symbol(path: String, name: String): Symbol? = files[resolve(path) ?: path]?.symbols?.firstOrNull { it.name == name }

    fun symbolsOf(path: String): List<Symbol> {
        val key = resolve(path) ?: return emptyList()
        return files[key]?.symbols.orEmpty()
    }

    fun impact(path: String, symbolName: String?): List<Impact> {
        val key = resolve(path) ?: return emptyList()
        val file = files[key] ?: return emptyList()
        val symbols = file.symbols.filter { symbolName.isNullOrBlank() || it.name == symbolName }
        val out = ArrayList<Impact>()
        for ((other, _) in files) {
            if (other == key) continue
            val text = texts[other] ?: continue
            for (sym in symbols) {
                for (line in SymbolParse.referenceLines(text, sym.name, sym.kind, file.pkg)) {
                    val raw = text.lines().getOrNull(line - 1)?.trim().orEmpty()
                    val kind = if (raw.startsWith("import ") || raw.startsWith("from ") || raw.startsWith("extends ")) "import" else "call"
                    out += Impact(other, line, sym.name, kind)
                }
            }
        }
        return out.distinctBy { "${it.path}:${it.line}:${it.symbol}:${it.kind}" }
    }

    fun formatImpact(path: String, symbolName: String?): String {
        val hits = impact(path, symbolName)
        if (hits.isEmpty()) return "Etki yok: ${symbolName ?: path} için doğrudan çağıran veya import bulunamadı."
        val head = symbolName?.takeIf { it.isNotBlank() } ?: path
        return hits.joinToString("\n", prefix = "$head\n") { h -> "- ${h.path}:${h.line} ${h.kind} ${h.symbol}" }
    }

    private fun resolve(path: String): String? {
        val key = path.trim().removePrefix("./")
        if (key in files) return key
        return files.keys.firstOrNull { it.endsWith("/$key") }
    }
}

/** Declaration and reference scan. Not a full parser: strings and comments are skipped only when they are obvious. */
object SymbolParse {
    private val FUN_KINDS = setOf("fun", "def", "func", "method")
    private val KT_DECL = Regex(
        """^(?:(?:public|private|internal|protected|open|abstract|suspend|inline|operator|override|final|data|sealed|enum|annotation|tailrec|external|actual|expect|const|value|inner|companion)\s+)*(class|interface|object|fun)\s+(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)""",
    )
    private val JAVA_TYPE = Regex(
        """^(?:(?:public|private|protected|abstract|final|static|sealed)\s+)*(class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""",
    )
    private val JAVA_METHOD = Regex(
        """^(?:(?:public|private|protected|static|final|synchronized|native|abstract|default)\s+)+[\w.<>,?\s\[\]]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""",
    )
    private val PY_DEF = Regex("""^(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    private val PY_CLASS = Regex("""^class\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val GD_FUNC = Regex("""^func\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    private val GD_CLASS = Regex("""^class_name\s+([A-Za-z_][A-Za-z0-9_]*)""")

    fun packageOf(text: String): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("package ") } ?: return ""
        return line.removePrefix("package ").substringBefore("//").trim().trimEnd(';').trim()
    }

    fun symbols(path: String, text: String): List<RepoDependencyGraph.Symbol> {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "py" -> python(text)
            "gd" -> gdscript(text)
            "java" -> jvm(text, java = true)
            "kt", "kts" -> jvm(text, java = false)
            else -> emptyList()
        }
    }

    fun referenceLines(text: String, name: String, kind: String, pkg: String): List<Int> {
        if (name.isBlank()) return emptyList()
        val word = Regex("""\b${Regex.escape(name)}\b""")
        val call = Regex("""\b${Regex.escape(name)}\s*\(""")
        val out = ArrayList<Int>()
        text.lines().forEachIndexed { i, line ->
            val t = line.trim()
            if (t.startsWith("//") || t.startsWith("#") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
            val importish = t.startsWith("import ") || t.startsWith("from ") || t.startsWith("extends ")
            val star = pkg.isNotBlank() && (t.contains("$pkg.*") || t.contains("$pkg import") || t.startsWith("import $pkg"))
            val hit = when {
                importish && (word.containsMatchIn(t) || star && kind !in FUN_KINDS) -> true
                kind in FUN_KINDS && call.containsMatchIn(line) -> true
                kind !in FUN_KINDS && word.containsMatchIn(line) && !t.startsWith("class ") && !t.startsWith("fun ") &&
                    !t.startsWith("def ") && !t.startsWith("func ") && !t.startsWith("class_name ") -> true
                else -> false
            }
            if (hit) out += i + 1
        }
        return out
    }

    fun mentions(text: String, name: String): Boolean = Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text)

    /** Argument counts of each `name(` call. A call that cannot be closed counts as -1. */
    fun callArgCounts(text: String, name: String): List<Int> {
        val needle = Regex("""\b${Regex.escape(name)}\s*\(""")
        val out = ArrayList<Int>()
        var from = 0
        while (from < text.length) {
            val m = needle.find(text, from) ?: break
            val open = text.indexOf('(', m.range.first)
            val close = closeParen(text, open)
            if (close < 0) {
                out += -1
                break
            }
            val inner = text.substring(open + 1, close)
            out += if (inner.isBlank()) 0 else splitArgs(inner).size
            from = close + 1
        }
        return out
    }

    private fun python(text: String): List<RepoDependencyGraph.Symbol> = scanLines(text) { line, no ->
        val def = PY_DEF.find(line.trim())
        val cls = PY_CLASS.find(line.trim())
        when {
            def != null -> RepoDependencyGraph.Symbol(def.groupValues[1], "def", no, requiredParams(signatureFrom(text, lineIndex(text, no)), skipSelf = true))
            cls != null && line.takeWhile { it == ' ' }.length == 0 -> RepoDependencyGraph.Symbol(cls.groupValues[1], "class", no, -1)
            else -> null
        }
    }

    private fun gdscript(text: String): List<RepoDependencyGraph.Symbol> = scanLines(text) { line, no ->
        val fn = GD_FUNC.find(line.trim())
        val cls = GD_CLASS.find(line.trim())
        when {
            fn != null -> RepoDependencyGraph.Symbol(fn.groupValues[1], "func", no, requiredParams(signatureFrom(text, lineIndex(text, no)), skipSelf = false))
            cls != null -> RepoDependencyGraph.Symbol(cls.groupValues[1], "class", no, -1)
            else -> null
        }
    }

    private fun jvm(text: String, java: Boolean): List<RepoDependencyGraph.Symbol> {
        val lines = text.lines()
        var depth = 0
        val out = ArrayList<RepoDependencyGraph.Symbol>()
        for (i in lines.indices) {
            val raw = lines[i]
            val t = raw.trim()
            if (depth <= 1 && !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")) {
                if (java) {
                    val type = JAVA_TYPE.find(t)
                    val method = if (type == null) JAVA_METHOD.find(t) else null
                    when {
                        type != null -> out += RepoDependencyGraph.Symbol(type.groupValues[2], type.groupValues[1], i + 1, -1)
                        method != null && method.groupValues[1] !in setOf("if", "for", "while", "switch", "catch") ->
                            out += RepoDependencyGraph.Symbol(method.groupValues[1], "method", i + 1, requiredParams(signatureFrom(text, i), skipSelf = false))
                    }
                } else {
                    val m = KT_DECL.find(t)
                    if (m != null) {
                        val kind = m.groupValues[1]
                        val name = m.groupValues[2].trim('`')
                        val params = if (kind == "fun") requiredParams(signatureFrom(text, i), skipSelf = false) else -1
                        out += RepoDependencyGraph.Symbol(name, kind, i + 1, params)
                    }
                }
            }
            depth = (depth + depthDelta(stripLine(raw))).coerceAtLeast(0)
        }
        return out
    }

    private fun scanLines(text: String, block: (String, Int) -> RepoDependencyGraph.Symbol?): List<RepoDependencyGraph.Symbol> {
        val out = ArrayList<RepoDependencyGraph.Symbol>()
        text.lines().forEachIndexed { i, line -> block(line, i + 1)?.let { out += it } }
        return out
    }

    private fun lineIndex(text: String, oneBased: Int): Int = oneBased - 1

    private fun signatureFrom(text: String, lineIdx: Int): String {
        val lines = text.lines()
        if (lineIdx !in lines.indices) return ""
        val sb = StringBuilder()
        var depth = 0
        var started = false
        for (i in lineIdx until minOf(lines.size, lineIdx + 8)) {
            sb.append(lines[i]).append('\n')
            for (c in lines[i]) {
                if (c == '(') { depth++; started = true }
                if (c == ')') depth--
            }
            if (started && depth <= 0) break
        }
        return sb.toString()
    }

    fun requiredParams(signature: String, skipSelf: Boolean): Int {
        val open = signature.indexOf('(')
        if (open < 0) return 0
        val close = closeParen(signature, open)
        if (close < 0) return 0
        val inner = signature.substring(open + 1, close).trim()
        if (inner.isBlank()) return 0
        var parts = splitArgs(inner).map { it.trim() }.filter { it.isNotBlank() }
        if (skipSelf && parts.firstOrNull()?.substringBefore(':')?.substringBefore('=')?.trim() in setOf("self", "cls")) {
            parts = parts.drop(1)
        }
        return parts.count { !hasDefault(it) }
    }

    private fun hasDefault(param: String): Boolean {
        var depth = 0
        var quote = '\u0000'
        var i = 0
        while (i < param.length) {
            val c = param[i]
            if (quote != '\u0000') {
                if (c == '\\') { i += 2; continue }
                if (c == quote) quote = '\u0000'
            } else when (c) {
                '"', '\'' -> quote = c
                '(', '[', '{', '<' -> depth++
                ')', ']', '}', '>' -> depth--
                '=' -> if (depth == 0) return true
            }
            i++
        }
        return false
    }

    private fun splitArgs(inner: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var quote = '\u0000'
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (quote != '\u0000') {
                sb.append(c)
                if (c == '\\' && i + 1 < inner.length) { sb.append(inner[i + 1]); i += 2; continue }
                if (c == quote) quote = '\u0000'
            } else when (c) {
                '"', '\'' -> { quote = c; sb.append(c) }
                '(', '[', '{', '<' -> { depth++; sb.append(c) }
                ')', ']', '}', '>' -> { depth--; sb.append(c) }
                ',' -> if (depth == 0) { out += sb.toString(); sb.clear() } else sb.append(c)
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }

    private fun closeParen(text: String, open: Int): Int {
        var depth = 0
        var quote = '\u0000'
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (quote != '\u0000') {
                if (c == '\\') { i += 2; continue }
                if (c == quote) quote = '\u0000'
            } else when (c) {
                '"', '\'' -> quote = c
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    private fun stripLine(line: String): String {
        val c = line.indexOf("//")
        return if (c >= 0) line.substring(0, c) else line
    }

    private fun depthDelta(line: String): Int = line.count { it == '{' } - line.count { it == '}' }
}
