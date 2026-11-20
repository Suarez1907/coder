package com.suarez.coder.repomap

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a source file into a compact outline: types, function signatures and important fields, without bodies.
 * Lightweight regex/brace scanning; good enough for a map, not a compiler.
 */
object RepoSkeletonExtractor {
    const val MAX_LINES_PER_FILE = 60

    private enum class Kind { TYPE, FUNC, FIELD }

    fun extract(path: String, text: String): List<String> {
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        val out = when {
            name.endsWith(".gradle.kts") || ext == "gradle" -> gradle(text)
            ext == "kt" || ext == "kts" -> braces(text, ::kotlinDecl)
            ext == "java" -> braces(text, ::javaDecl)
            ext in setOf("js", "jsx", "ts", "tsx", "mjs", "cjs") -> braces(text, ::jsDecl)
            ext == "py" -> python(text)
            ext == "xml" -> xml(name, text)
            ext == "json" -> json(name, text)
            ext == "md" -> text.lines().filter { Regex("^#{1,3} ").containsMatchIn(it) }.take(15)
            else -> emptyList()
        }
        return if (out.size > MAX_LINES_PER_FILE) out.take(MAX_LINES_PER_FILE) + "… (${out.size - MAX_LINES_PER_FILE} satır daha)" else out
    }

    // ---- Brace languages (Kotlin, Java, JS/TS) ----

    private val kotlinMods = "(?:(?:public|protected|internal|open|abstract|final|override|suspend|inline|data|sealed|enum|annotation|" +
        "companion|const|lateinit|inner|value|operator|infix|tailrec|external|actual|expect)\\s+)*"
    private val kotlinTypeRe = Regex("^(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*${kotlinMods}(?:class|interface|object)\\b")
    private val kotlinFunRe = Regex("^(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*${kotlinMods}(?:fun|constructor)\\b")
    private val kotlinFieldRe = Regex("^(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*${kotlinMods}(?:val|var|typealias)\\b")

    private fun kotlinDecl(t: String, insideType: Boolean): Kind? {
        if (t.startsWith("private ")) return if (kotlinTypeRe.containsMatchIn(t.removePrefix("private "))) Kind.TYPE else null
        return when {
            kotlinTypeRe.containsMatchIn(t) -> Kind.TYPE
            kotlinFunRe.containsMatchIn(t) -> Kind.FUNC
            kotlinFieldRe.containsMatchIn(t) -> Kind.FIELD
            else -> null
        }
    }

    private val javaKeywords = setOf("return", "new", "if", "for", "while", "switch", "catch", "throw", "else", "do", "try", "case")
    private val javaTypeRe = Regex("^(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*(?:class|interface|enum|record|@interface)\\s+\\w+")
    private val javaMethodRe = Regex("^(?:(?:public|protected|static|final|abstract|synchronized|native|default|strictfp)\\s+)*(?:<[^>]+>\\s+)?[\\w<>\\[\\],.?]+(?:\\s+[\\w<>\\[\\],.?]+)?\\s+\\w+\\s*\\(")
    private val javaCtorRe = Regex("^(?:public|protected)\\s+\\w+\\s*\\(")
    private val javaFieldRe = Regex("^(?:(?:public|protected|static|final)\\s+)+[\\w<>\\[\\],.?]+\\s+\\w+\\s*(?:=|;)")

    private fun javaDecl(t: String, insideType: Boolean): Kind? {
        if (t.startsWith("@")) return null
        val first = t.substringBefore(' ').substringBefore('(')
        if (first in javaKeywords) return null
        return when {
            javaTypeRe.containsMatchIn(t) -> Kind.TYPE
            t.startsWith("private ") -> null
            javaCtorRe.containsMatchIn(t) || (insideType && javaMethodRe.containsMatchIn(t)) -> Kind.FUNC
            insideType && javaFieldRe.containsMatchIn(t) -> Kind.FIELD
            else -> null
        }
    }

    private val jsTypeRe = Regex("^(?:export\\s+)?(?:default\\s+)?(?:declare\\s+)?(?:abstract\\s+)?(?:class|interface|enum)\\s+[\\w$]+")
    private val jsTypeAliasRe = Regex("^(?:export\\s+)?(?:declare\\s+)?type\\s+[\\w$]+")
    private val jsFunRe = Regex("^(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\*?\\s*[\\w$]*\\s*[<(]")
    private val jsArrowRe = Regex("^(?:export\\s+)?(?:const|let|var)\\s+[\\w$]+\\s*(?::[^=]+)?=\\s*(?:async\\s+)?(?:\\([^)]*\\)|[\\w$]+)\\s*(?::[^=]+)?=>")
    private val jsExportRe = Regex("^export\\s+(?:const|let|var)\\s+[\\w$]+")
    private val jsMethodRe = Regex("^(?:(?:public|private|protected|static|readonly|async|override|get|set)\\s+)*#?[\\w$]+\\s*(?:<[^>]*>)?\\s*\\(")
    private val jsKeywords = setOf("if", "for", "while", "switch", "catch", "return", "function", "else", "do", "try", "await", "new", "super", "this")

    private fun jsDecl(t: String, insideType: Boolean): Kind? = when {
        jsTypeRe.containsMatchIn(t) -> Kind.TYPE
        jsFunRe.containsMatchIn(t) || jsArrowRe.containsMatchIn(t) -> Kind.FUNC
        jsTypeAliasRe.containsMatchIn(t) || jsExportRe.containsMatchIn(t) -> Kind.FIELD
        insideType && jsMethodRe.containsMatchIn(t) && t.substringBefore('(').trim().split(' ').last().trimStart('#') !in jsKeywords -> Kind.FUNC
        else -> null
    }

    private fun stripStringsAndComments(line: String, inBlock: BooleanArray): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < line.length) {
            val c = line[i]
            if (inBlock[0]) {
                if (c == '*' && i + 1 < line.length && line[i + 1] == '/') { inBlock[0] = false; i += 2 } else i++
                continue
            }
            if (quote != null) {
                if (c == '\\') { i += 2; continue }
                if (c == quote) { quote = null; sb.append(c) }
                i++; continue
            }
            when {
                c == '/' && i + 1 < line.length && line[i + 1] == '/' -> return sb.toString()
                c == '/' && i + 1 < line.length && line[i + 1] == '*' -> { inBlock[0] = true; i += 2 }
                c == '"' || c == '\'' || c == '`' -> { quote = c; sb.append(c); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /** Cuts a declaration to its signature: no body, no initializer. Braces inside parameter lists are kept. */
    private fun signature(t: String, kind: Kind): String {
        var s = t
        // Cut at the first '{' outside parentheses (the body), not at destructuring/object params.
        var depth = 0
        for ((i, c) in s.withIndex()) {
            if (c == '(') depth++ else if (c == ')') depth--
            else if (c == '{' && depth <= 0) { s = s.substring(0, i); break }
        }
        if (kind == Kind.FUNC && s.contains("=>")) return s.substring(0, s.indexOf("=>") + 2).trim()
        if (kind == Kind.FIELD) {
            Regex("\\s=(?!=|>)").find(s)?.let { s = s.substring(0, it.range.first) }
            val by = s.indexOf(" by ")
            if (by > 0) s = s.substring(0, by)
        } else if (kind == Kind.FUNC) {
            // Expression body: '=' after the parameter list closes.
            val open = s.indexOf('(')
            if (open >= 0) {
                var d = 0
                var close = -1
                for (i in open until s.length) {
                    if (s[i] == '(') d++ else if (s[i] == ')') { d--; if (d == 0) { close = i; break } }
                }
                if (close > 0) Regex("\\s=(?!=|>)").find(s, close)?.let { s = s.substring(0, it.range.first) }
            }
        }
        return s.trim().removeSuffix(";").trim()
    }

    private fun braces(text: String, decl: (String, Boolean) -> Kind?): List<String> {
        val out = ArrayList<String>()
        val stack = ArrayList<Boolean>() // true = type scope
        val inBlock = booleanArrayOf(false)
        var pendingType = 0 // lines left in which a '{' opens the last type declaration
        var sig: StringBuilder? = null
        var sigKind = Kind.FUNC
        var sigIndent = 0
        var depthParens = 0
        for (raw in text.lines()) {
            val line = stripStringsAndComments(raw, inBlock)
            val t = line.trim()
            if (sig != null) {
                sig.append(' ').append(t)
                depthParens += t.count { it == '(' } - t.count { it == ')' }
                if (depthParens <= 0 || t.contains('{') || sig.length > 300) {
                    out += "  ".repeat(sigIndent) + signature(sig.toString(), sigKind)
                    sig = null
                }
            } else if (t.isNotEmpty() && stack.all { it }) {
                val kind = decl(t, stack.isNotEmpty())
                if (kind != null) {
                    val parens = t.count { it == '(' } - t.count { it == ')' }
                    if (parens > 0 && !t.contains('{')) {
                        sig = StringBuilder(t); sigKind = kind; sigIndent = stack.size; depthParens = parens
                    } else {
                        out += "  ".repeat(stack.size) + signature(t, kind)
                    }
                    if (kind == Kind.TYPE) pendingType = 3
                }
            }
            var opened = false
            for (c in line) {
                if (c == '{') {
                    stack += (pendingType > 0 && !opened)
                    if (pendingType > 0) pendingType = 0
                    opened = true
                } else if (c == '}' && stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
            if (!opened && pendingType > 0 && t.isNotEmpty()) pendingType--
        }
        return out
    }

    // ---- Python ----

    private fun python(text: String): List<String> {
        val out = ArrayList<String>()
        var funcIndent = -1
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val indent = l.takeWhile { it == ' ' || it == '\t' }.length
            val t = l.trim()
            if (t.isNotEmpty() && funcIndent >= 0 && indent <= funcIndent) funcIndent = -1
            val m = Regex("^(async\\s+def|def|class)\\s+\\w+").find(t)
            if (m != null && funcIndent < 0) {
                var sig = t
                var j = i
                while (sig.count { it == '(' } > sig.count { it == ')' } && j + 1 < lines.size && j - i < 8) { j++; sig += " " + lines[j].trim() }
                out += " ".repeat(indent) + sig.removeSuffix(":").trim()
                if (!t.startsWith("class")) funcIndent = indent
                i = j + 1
                continue
            }
            if (indent == 0 && Regex("^[A-Z_][A-Z0-9_]+\\s*(:[^=]+)?=").containsMatchIn(t)) out += t.substringBefore('=').trim()
            i++
        }
        return out
    }

    // ---- XML, JSON, Gradle ----

    private fun xml(name: String, text: String): List<String> {
        if (name == "AndroidManifest.xml") {
            val out = ArrayList<String>()
            Regex("<manifest[^>]*package=\"([^\"]+)\"").find(text)?.let { out += "package ${it.groupValues[1]}" }
            Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(text).forEach { out += "permission ${it.groupValues[1].substringAfterLast('.')}" }
            Regex("<(activity|service|receiver|provider)[^>]*android:name=\"([^\"]+)\"").findAll(text).forEach { out += "${it.groupValues[1]} ${it.groupValues[2]}" }
            return out
        }
        val values = Regex("<(string|color|dimen|style|bool|integer|plurals|string-array)\\s+name=\"([^\"]+)\"").findAll(text)
            .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
        if (values.isNotEmpty()) {
            return values.map { (tag, names) -> "$tag: " + names.take(40).joinToString(", ") + if (names.size > 40) " … (+${names.size - 40})" else "" }
        }
        val root = Regex("<([A-Za-z][\\w.]*)[\\s>]").findAll(text).map { it.groupValues[1] }.firstOrNull { it != "xml" && !it.startsWith("?") }
        val ids = Regex("<([\\w.]+)[^>]*?android:id=\"@\\+id/(\\w+)\"").findAll(text).map { "${it.groupValues[1].substringAfterLast('.')}#${it.groupValues[2]}" }.toList()
        return listOfNotNull(root?.let { "<$it>" }) + if (ids.isNotEmpty()) listOf("ids: " + ids.joinToString(", ")) else emptyList()
    }

    private fun json(name: String, text: String): List<String> {
        val t = text.trim()
        return runCatching {
            if (t.startsWith("[")) return listOf("[dizi, ${JSONArray(t).length()} öğe]")
            val o = JSONObject(t)
            if (name == "package.json") {
                listOfNotNull(
                    o.optString("name").takeIf { it.isNotBlank() }?.let { "name: $it" },
                    o.optJSONObject("scripts")?.keys()?.asSequence()?.toList()?.let { "scripts: ${it.joinToString(", ")}" },
                    o.optJSONObject("dependencies")?.keys()?.asSequence()?.toList()?.let { "dependencies: ${it.joinToString(", ")}" },
                    o.optJSONObject("devDependencies")?.keys()?.asSequence()?.toList()?.let { "devDependencies: ${it.joinToString(", ")}" },
                )
            } else {
                val keys = o.keys().asSequence().toList()
                listOf("keys: " + keys.take(30).joinToString(", ") + if (keys.size > 30) " … (+${keys.size - 30})" else "")
            }
        }.getOrDefault(emptyList())
    }

    private val gradleRe = Regex("^\\s*(id\\(|id |kotlin\\(|namespace|applicationId|minSdk|targetSdk|compileSdk|implementation|api\\(|kapt|ksp\\(|testImplementation)")

    private fun gradle(text: String) = text.lines().filter { gradleRe.containsMatchIn(it) }.map { it.trim() }.take(30)
}
