package com.suarez.coder.validate

import javax.xml.parsers.DocumentBuilderFactory

/**
 * Cheap local checks before a commit, so obviously broken edits go back to the (cheap) editor instead of
 * failing a CI build that the (expensive) agent then has to read and debug.
 * Checks compare before/after: a check only fails when the edit broke something that was fine before,
 * which avoids false alarms from heuristics on unusual files.
 */
object CodeValidator {
    private val braceExt = setOf("kt", "kts", "java", "js", "jsx", "ts", "tsx", "mjs", "cjs", "c", "cpp", "h", "hpp", "cs", "go", "rs", "swift", "dart", "scala", "groovy", "gradle")

    /** Returns a problem description, or null when the file looks fine. [before] is null for new files. */
    fun validate(path: String, before: String?, after: String): String? {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when {
            ext == "json" -> parseProblem(before, after, ::jsonError)
            ext == "xml" || ext == "svg" -> parseProblem(before, after, ::xmlError)
            ext in braceExt -> {
                val a = balance(after, ext)
                if (a == null) null
                else {
                    val b = before?.let { balance(it, ext) }
                    if (before != null && b != Triple(0, 0, 0)) null // was already "unbalanced" for our heuristic: don't judge
                    else describe(a)
                }
            }
            else -> null
        }
    }

    private fun parseProblem(before: String?, after: String, check: (String) -> String?): String? {
        val err = check(after) ?: return null
        if (before != null && check(before) != null) return null // was already invalid
        return err
    }

    private fun jsonError(t: String): String? = if (t.isBlank()) null else StrictJson.error(t)

    private fun xmlError(t: String): String? {
        if (t.isBlank()) return null
        return runCatching {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            f.newDocumentBuilder().apply { setErrorHandler(null) }.parse(t.byteInputStream())
            null
        }.getOrElse { "XML geçersiz: ${it.message?.take(120)}" }
    }

    private fun describe(b: Triple<Int, Int, Int>): String? {
        if (b == Triple(0, 0, 0)) return null
        fun f(n: Int, open: Char, close: Char) = when {
            n > 0 -> "$n fazla '$open'"
            n < 0 -> "${-n} fazla '$close'"
            else -> null
        }
        return "Parantez dengesi bozuldu: " + listOfNotNull(f(b.first, '{', '}'), f(b.second, '(', ')'), f(b.third, '[', ']')).joinToString(", ")
    }

    /**
     * Net count of {}, () and [] outside strings and comments; handles Kotlin raw strings and JS template literals
     * across lines. Returns null when a string is still open at the end (heuristic gave up).
     */
    fun balance(text: String, ext: String = "kt"): Triple<Int, Int, Int>? {
        var curly = 0; var paren = 0; var square = 0
        var i = 0
        val n = text.length
        val js = ext in setOf("js", "jsx", "ts", "tsx", "mjs", "cjs")
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> { while (i < n && text[i] != '\n') i++; continue }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = text.indexOf("*/", i + 2); if (end < 0) return null; i = end + 2; continue
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3); if (end < 0) return null; i = end + 3; continue
                }
                c == '`' && js -> {
                    // Template literal: skip text, but count braces inside ${ ... } expressions as balanced pairs.
                    i++
                    while (i < n && text[i] != '`') { if (text[i] == '\\') i++; i++ }
                    if (i >= n) return null; i++; continue
                }
                c == '"' || c == '\'' || (c == '`') -> {
                    i++
                    while (i < n && text[i] != c && text[i] != '\n') { if (text[i] == '\\') i++; i++ }
                    i++; continue
                }
                c == '{' -> curly++
                c == '}' -> curly--
                c == '(' -> paren++
                c == ')' -> paren--
                c == '[' -> square++
                c == ']' -> square--
            }
            i++
        }
        return Triple(curly, paren, square)
    }
}
