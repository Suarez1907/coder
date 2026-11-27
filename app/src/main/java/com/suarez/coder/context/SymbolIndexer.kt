package com.suarez.coder.context

import java.security.MessageDigest

data class SymbolInfo(
    val name: String,
    val kind: String,
    val startLine: Int,
    val endLine: Int,
    val signaturePreview: String
)

object SymbolIndexer {
    const val MAX_RANGE_LINES = 250
    private const val MAX_CACHE_SIZE = 128

    private val cache = object : LinkedHashMap<String, List<SymbolInfo>>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SymbolInfo>>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    private val lock = Any()
    var cacheHits: Long = 0
        private set
    var cacheMisses: Long = 0
        private set

    fun resetCache() {
        synchronized(lock) {
            cache.clear()
            cacheHits = 0
            cacheMisses = 0
        }
    }

    fun isCached(path: String, content: String): Boolean {
        val ext = extensionOf(path)
        val key = ext + ":" + computeSha256(content)
        synchronized(lock) {
            return cache.containsKey(key)
        }
    }

    fun index(path: String, content: String, sha: String? = null): List<SymbolInfo> {
        val ext = extensionOf(path)
        val key = ext + ":" + (sha ?: computeSha256(content))

        synchronized(lock) {
            val cached = cache[key]
            if (cached != null) {
                cacheHits++
                return cached
            }
            cacheMisses++
        }

        val parsed = parseSymbols(ext, content)
        synchronized(lock) {
            cache[key] = parsed
        }
        return parsed
    }

    fun formatCompact(path: String, content: String, sha: String? = null): String {
        val lines = content.lines()
        val totalLines = lines.size
        val symbols = index(path, content, sha)

        val sb = StringBuilder()
        sb.append(path).append(" (").append(totalLines).append(" satır)\n")
        if (symbols.isEmpty()) {
            sb.append("(sembol bulunamadı)")
        } else {
            symbols.forEachIndexed { idx, s ->
                sb.append("L").append(s.startLine).append("-L").append(s.endLine).append(" ").append(s.signaturePreview)
                if (idx < symbols.size - 1) sb.append("\n")
            }
        }
        return sb.toString()
    }

    fun readRange(
        content: String,
        startLine: Int,
        endLine: Int,
        maxLines: Int = MAX_RANGE_LINES
    ): String {
        val lines = content.lines()
        val total = lines.size
        if (total == 0) return ""

        val clampedStart = startLine.coerceIn(1, total)
        var clampedEnd = endLine.coerceIn(clampedStart, total)

        val requestedCount = clampedEnd - clampedStart + 1
        val truncated = requestedCount > maxLines
        if (truncated) {
            clampedEnd = clampedStart + maxLines - 1
        }

        val sb = StringBuilder()
        for (lineNo in clampedStart..clampedEnd) {
            sb.append(lineNo).append(": ").append(lines[lineNo - 1])
            if (lineNo < clampedEnd) sb.append("\n")
        }

        if (truncated) {
            sb.append("\n... (güvenli tavan: en fazla ")
                .append(maxLines)
                .append(" satır gösterildi, kalan için sonraki aralığı isteyin)")
        }
        return sb.toString()
    }

    private fun extensionOf(path: String): String {
        val idx = path.lastIndexOf('.')
        return if (idx >= 0 && idx < path.length - 1) path.substring(idx + 1).lowercase() else ""
    }

    private fun computeSha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun parseSymbols(ext: String, content: String): List<SymbolInfo> {
        return when (ext) {
            "kt", "java" -> parseKotlinOrJava(content)
            "py" -> parsePython(content)
            "gd" -> parseGDScript(content)
            "yml", "yaml" -> parseYaml(content)
            "json" -> parseJson(content)
            "xml" -> parseXml(content)
            else -> emptyList()
        }
    }

    // Kotlin / Java parser: regex-based declaration finder + brace matching
    private fun parseKotlinOrJava(content: String): List<SymbolInfo> {
        val lines = content.lines()
        val symbols = mutableListOf<SymbolInfo>()

        val declRegex = Regex(
            """^\s*(?:(?:public|private|protected|internal|open|final|abstract|sealed|data|enum|suspend|override|static|const|actual|expect)\s+)*(class|interface|object|enum\s+class|data\s+class|sealed\s+class|fun|void|[A-Z][A-Za-z0-9_<>\[\]]*)\s+([A-Za-z0-9_]+)"""
        )

        val totalLines = lines.size
        for (i in 0 until totalLines) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue
            val match = declRegex.find(line) ?: continue

            val rawKind = match.groupValues[1]
            val name = match.groupValues[2]

            // Filter out control flow or common variable statements
            if (name in setOf("if", "else", "for", "while", "return", "new", "throw", "try", "catch", "finally", "switch", "case")) continue

            val kind = when {
                rawKind.contains("class") -> "class"
                rawKind == "interface" -> "interface"
                rawKind == "object" -> "object"
                rawKind == "fun" || rawKind == "void" || line.contains("(") -> "function"
                else -> "class"
            }

            val startLine = i + 1
            val endLine = findBraceScopeEnd(lines, i)
            val signature = trimmed.take(80)

            symbols.add(
                SymbolInfo(
                    name = name,
                    kind = kind,
                    startLine = startLine,
                    endLine = endLine,
                    signaturePreview = signature
                )
            )
        }
        return symbols
    }

    private fun findBraceScopeEnd(lines: List<String>, startIdx: Int): Int {
        var depth = 0
        var foundAnyBrace = false
        var inBlockComment = false

        for (i in startIdx until lines.size) {
            val line = lines[i]
            var inString = false
            var stringChar = ' '
            var j = 0
            while (j < line.length) {
                val c = line[j]

                if (inBlockComment) {
                    if (c == '*' && j + 1 < line.length && line[j + 1] == '/') {
                        inBlockComment = false
                        j++
                    }
                    j++
                    continue
                }

                if (inString) {
                    if (c == '\\') {
                        j += 2
                        continue
                    }
                    if (c == stringChar) {
                        inString = false
                    }
                    j++
                    continue
                }

                if (c == '/' && j + 1 < line.length) {
                    if (line[j + 1] == '/') {
                        break // rest is line comment
                    } else if (line[j + 1] == '*') {
                        inBlockComment = true
                        j += 2
                        continue
                    }
                }

                if (c == '"' || c == '\'') {
                    inString = true
                    stringChar = c
                    j++
                    continue
                }

                if (c == '{') {
                    depth++
                    foundAnyBrace = true
                } else if (c == '}') {
                    depth--
                    if (depth <= 0 && foundAnyBrace) {
                        return i + 1
                    }
                }
                j++
            }
        }
        return if (foundAnyBrace) lines.size else (startIdx + 1)
    }

    // Python parser: indentation-based
    private fun parsePython(content: String): List<SymbolInfo> {
        val lines = content.lines()
        val symbols = mutableListOf<SymbolInfo>()
        val pyDecl = Regex("""^(\s*)(?:async\s+)?(def|class)\s+([A-Za-z0-9_]+)""")

        data class RawItem(val indent: Int, val kind: String, val name: String, val lineIdx: Int, val sig: String)
        val rawItems = mutableListOf<RawItem>()

        for (i in lines.indices) {
            val line = lines[i]
            val match = pyDecl.find(line) ?: continue
            val indentStr = match.groupValues[1]
            val kind = if (match.groupValues[2] == "class") "class" else "function"
            val name = match.groupValues[3]
            rawItems.add(RawItem(indentStr.length, kind, name, i, line.trim()))
        }

        for (k in rawItems.indices) {
            val item = rawItems[k]
            val startLine = item.lineIdx + 1
            var endLine = lines.size

            for (j in (item.lineIdx + 1) until lines.size) {
                val candidate = lines[j]
                val trimmed = candidate.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val candIndent = candidate.indexOfFirst { !it.isWhitespace() }
                if (candIndent <= item.indent) {
                    // end at last non-empty line before this
                    var lastNonEmpty = j - 1
                    while (lastNonEmpty > item.lineIdx && lines[lastNonEmpty].trim().isEmpty()) {
                        lastNonEmpty--
                    }
                    endLine = lastNonEmpty + 1
                    break
                }
            }

            symbols.add(
                SymbolInfo(
                    name = item.name,
                    kind = item.kind,
                    startLine = startLine,
                    endLine = maxOf(startLine, endLine),
                    signaturePreview = item.sig.take(80)
                )
            )
        }

        return symbols
    }

    // GDScript parser: indentation-based for func/class, plus class_name / signal
    private fun parseGDScript(content: String): List<SymbolInfo> {
        val lines = content.lines()
        val symbols = mutableListOf<SymbolInfo>()
        val gdDecl = Regex("""^(\s*)(func|static\s+func|class|class_name|signal)\s+([A-Za-z0-9_]+)""")

        data class RawItem(val indent: Int, val kind: String, val name: String, val lineIdx: Int, val sig: String)
        val rawItems = mutableListOf<RawItem>()

        for (i in lines.indices) {
            val line = lines[i]
            val match = gdDecl.find(line) ?: continue
            val indentStr = match.groupValues[1]
            val rawKind = match.groupValues[2]
            val name = match.groupValues[3]

            val kind = when {
                rawKind == "class_name" -> "class"
                rawKind == "class" -> "class"
                rawKind == "signal" -> "signal"
                else -> "function"
            }
            rawItems.add(RawItem(indentStr.length, kind, name, i, line.trim()))
        }

        for (k in rawItems.indices) {
            val item = rawItems[k]
            val startLine = item.lineIdx + 1
            if (item.kind == "signal" || item.sig.startsWith("class_name")) {
                symbols.add(
                    SymbolInfo(
                        name = item.name,
                        kind = item.kind,
                        startLine = startLine,
                        endLine = startLine,
                        signaturePreview = item.sig.take(80)
                    )
                )
                continue
            }

            var endLine = lines.size
            for (j in (item.lineIdx + 1) until lines.size) {
                val candidate = lines[j]
                val trimmed = candidate.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val candIndent = candidate.indexOfFirst { !it.isWhitespace() }
                if (candIndent <= item.indent) {
                    var lastNonEmpty = j - 1
                    while (lastNonEmpty > item.lineIdx && lines[lastNonEmpty].trim().isEmpty()) {
                        lastNonEmpty--
                    }
                    endLine = lastNonEmpty + 1
                    break
                }
            }

            symbols.add(
                SymbolInfo(
                    name = item.name,
                    kind = item.kind,
                    startLine = startLine,
                    endLine = maxOf(startLine, endLine),
                    signaturePreview = item.sig.take(80)
                )
            )
        }

        return symbols
    }

    // YAML: top-level keys (indent 0)
    private fun parseYaml(content: String): List<SymbolInfo> {
        val lines = content.lines()
        val symbols = mutableListOf<SymbolInfo>()
        val topKeyRegex = Regex("""^([A-Za-z0-9_.-]+)\s*:""")

        data class RawItem(val name: String, val lineIdx: Int, val sig: String)
        val rawItems = mutableListOf<RawItem>()

        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("#") || line.trim().isEmpty()) continue
            if (line.first().isWhitespace()) continue
            val match = topKeyRegex.find(line) ?: continue
            val name = match.groupValues[1]
            rawItems.add(RawItem(name, i, line.trim()))
        }

        for (k in rawItems.indices) {
            val item = rawItems[k]
            val startLine = item.lineIdx + 1
            val nextIdx = if (k + 1 < rawItems.size) rawItems[k + 1].lineIdx else lines.size
            var endLine = nextIdx
            while (endLine > startLine && lines[endLine - 1].trim().isEmpty()) {
                endLine--
            }
            symbols.add(
                SymbolInfo(
                    name = item.name,
                    kind = "key",
                    startLine = startLine,
                    endLine = maxOf(startLine, endLine),
                    signaturePreview = item.sig.take(80)
                )
            )
        }

        return symbols
    }

    // JSON: top-level object keys
    private fun parseJson(content: String): List<SymbolInfo> {
        val lines = content.lines()
        val symbols = mutableListOf<SymbolInfo>()
        val keyRegex = Regex("""^\s*"([A-Za-z0-9_.-]+)"\s*:""")

        for (i in lines.indices) {
            val line = lines[i]
            val match = keyRegex.find(line) ?: continue
            val name = match.groupValues[1]

            // find where the value ends if it's an object/array, otherwise single line
            val startLine = i + 1
            val trimmed = line.trim()
            val endLine = if (trimmed.endsWith("{") || trimmed.endsWith("[")) {
                findBraceScopeEnd(lines, i)
            } else {
                startLine
            }

            symbols.add(
                SymbolInfo(
                    name = name,
                    kind = "key",
                    startLine = startLine,
                    endLine = endLine,
                    signaturePreview = trimmed.take(80)
                )
            )
        }

        return symbols
    }

    // XML: root and first-level tags
    private fun parseXml(content: String): List<SymbolInfo> {
        val lines = content.lines()
        val symbols = mutableListOf<SymbolInfo>()
        val tagRegex = Regex("""<([A-Za-z0-9_.:-]+)([^>]*)>""")

        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith("<?") || trimmed.startsWith("<!--")) continue
            val match = tagRegex.find(line) ?: continue

            val tagName = match.groupValues[1]
            if (tagName.startsWith("/")) continue
            val attrs = match.groupValues[2]

            var displayName = tagName
            val nameAttr = Regex("""(?:android:name|name|id)="(?:@\+?id/)?([^"]+)""").find(attrs)
            if (nameAttr != null) {
                displayName = "$tagName (${nameAttr.groupValues[1]})"
            }

            val startLine = i + 1
            // Check if self-closing
            val isSelfClosing = match.value.endsWith("/>") || trimmed.endsWith("/>")
            var endLine = startLine
            if (!isSelfClosing) {
                val closeTag = "</$tagName>"
                for (j in i until lines.size) {
                    if (lines[j].contains(closeTag)) {
                        endLine = j + 1
                        break
                    }
                }
            }

            symbols.add(
                SymbolInfo(
                    name = displayName,
                    kind = "tag",
                    startLine = startLine,
                    endLine = maxOf(startLine, endLine),
                    signaturePreview = trimmed.take(80)
                )
            )
        }

        return symbols
    }
}
