package com.suarez.coder.harness

import org.json.JSONArray
import org.json.JSONObject

/**
 * Local checks that run before a commit: unbalanced brackets (strings and comments ignored), broken JSON,
 * tab-indented YAML, leftover merge conflicts, and an edit that wipes a file.
 * A finding is a problem in the edit. Braces that only appear inside a string or a comment are not findings.
 */
object PreFlightValidator {
    data class Finding(val rule: String, val detail: String)

    fun check(path: String, before: String?, after: String?): List<Finding> {
        if (after == null) return emptyList()
        val ext = path.substringAfterLast('.', "").lowercase()
        val out = ArrayList<Finding>()
        if (isAccidentalDelete(before, after)) out += Finding("accidental_delete", "Dosya içeriği neredeyse tamamen silindi.")
        if (hasConflict(after)) out += Finding("merge_conflict", "Merge conflict kalıntısı var.")
        if (ext == "json" && after.isNotBlank() && !validJson(after)) out += Finding("broken_json", "JSON ayrıştırılamadı.")
        if ((ext == "yml" || ext == "yaml") && hasYamlTab(after)) out += Finding("yaml_tab", "YAML girintisi sekme kullanıyor.")
        if (ext in BRACE_EXT && unbalanced(after, ext)) out += Finding("unbalanced_brackets", "Parantez veya küme dengesiz.")
        return out
    }

    fun isAccidentalDelete(before: String?, after: String?): Boolean {
        if (before == null || after == null) return false
        val b = before.trim()
        val a = after.trim()
        return b.length >= 80 && a.length * 20 < b.length
    }

    fun hasConflict(text: String) = text.lineSequence().any { line ->
        val t = line.trimStart()
        t.startsWith("<<<<<<<") || t.startsWith(">>>>>>>") || t.startsWith("=======")
    }

    fun hasYamlTab(text: String) = text.lineSequence().any { line ->
        line.isNotBlank() && line.takeWhile { it == ' ' || it == '\t' }.contains('\t')
    }

    fun validJson(text: String): Boolean = runCatching {
        val t = text.trim()
        if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
        true
    }.getOrDefault(false)

    /** True when brackets outside strings and comments do not balance. */
    fun unbalanced(text: String, ext: String = "kt"): Boolean {
        val hashComment = ext in setOf("py", "gd", "yml", "yaml", "sh")
        val stack = ArrayDeque<Char>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' && !hashComment -> {
                    while (i < n && text[i] != '\n') i++
                    continue
                }
                c == '#' && hashComment -> {
                    while (i < n && text[i] != '\n') i++
                    continue
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    if (end < 0) return true
                    i = end + 2
                    continue
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    if (end < 0) return true
                    i = end + 3
                    continue
                }
                c == '"' || c == '\'' -> {
                    val q = c
                    i++
                    while (i < n) {
                        if (text[i] == '\\') { i += 2; continue }
                        if (text[i] == q) { i++; break }
                        if (text[i] == '\n') break
                        i++
                    }
                    continue
                }
                c == '{' || c == '(' || c == '[' -> stack.addLast(c)
                c == '}' || c == ')' || c == ']' -> {
                    if (stack.isEmpty()) return true
                    val open = stack.removeLast()
                    val ok = (open == '{' && c == '}') || (open == '(' && c == ')') || (open == '[' && c == ']')
                    if (!ok) return true
                }
            }
            i++
        }
        return stack.isNotEmpty()
    }

    private val BRACE_EXT = setOf("kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "gd", "c", "cpp", "h", "cs", "go", "rs", "swift")
}
