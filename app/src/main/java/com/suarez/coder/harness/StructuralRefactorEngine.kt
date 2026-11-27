package com.suarez.coder.harness

import com.suarez.coder.context.RepoDependencyGraph
import com.suarez.coder.context.SymbolParse
import com.suarez.coder.security.SecurityScanner

/**
 * Mechanical multi-file edits with no model call. Every change is staged, then checked by
 * [PreFlightValidator], [SecurityScanner] and the cross-file signature gate. A conflict returns the
 * exact lines and commits nothing. A success is one edit list, which the caller pushes as one commit.
 */
class StructuralRefactorEngine(
    private val graph: RepoDependencyGraph,
    private val read: (String) -> String? = { graph.source(it) },
    private val ownKeys: List<String> = emptyList(),
    private val scanner: (String, String?, String?) -> List<SecurityScanner.Finding> = { path, before, after ->
        SecurityScanner.scan(path, before, after, ownKeys)
    },
) {
    data class Outcome(
        val ok: Boolean,
        val summary: String,
        val edits: List<StagedTransaction.Edit>,
        val filesTouched: Int,
        val savedTokens: Long,
        val lines: List<String>,
    )

    fun rename(oldName: String, newName: String, scopePaths: List<String>?, includeStrings: Boolean): Outcome {
        val old = oldName.trim()
        val neu = newName.trim()
        if (!ident(old) || !ident(neu)) return fail("Geçersiz sembol adı.", emptyList())
        if (old == neu) return fail("Eski ve yeni ad aynı.", emptyList())
        val files = candidates(scopePaths)
        val clashes = ArrayList<String>()
        val touched = ArrayList<Pair<String, String>>()
        for (path in files) {
            val text = textOf(path) ?: continue
            SymbolParse.symbols(path, text).firstOrNull { it.name == neu }?.let {
                clashes += "$path:${it.line} $neu zaten tanımlı"
            }
            if (!CodeLex.containsWord(text, old, includeStrings, extOf(path))) continue
            val next = CodeLex.replaceWord(text, extOf(path), old, neu, includeStrings)
            if (next != text) touched += path to next
        }
        if (clashes.isNotEmpty()) return fail("Yeni ad çakışıyor; hiçbir dosya yazılmadı.", clashes)
        if (touched.isEmpty()) return fail("'$old' kod içinde bulunamadı; hiçbir dosya yazılmadı.", emptyList())
        val edits = touched.map { (path, next) -> StagedTransaction.Edit(path, textOf(path), next) }
        return gate(edits, "code-mod: $old -> $neu")
    }

    fun updateImports(oldPrefix: String, newPrefix: String, scopePaths: List<String>?): Outcome {
        val old = oldPrefix.trim().trim('.')
        val neu = newPrefix.trim().trim('.')
        if (old.isBlank() || neu.isBlank()) return fail("Ön ek boş olamaz.", emptyList())
        if (old == neu) return fail("Eski ve yeni ön ek aynı.", emptyList())
        val edits = ArrayList<StagedTransaction.Edit>()
        for (path in candidates(scopePaths)) {
            val text = textOf(path) ?: continue
            val next = CodeLex.replaceImports(text, old, neu)
            if (next != text) edits += StagedTransaction.Edit(path, text, next)
        }
        if (edits.isEmpty()) return fail("Güncellenecek import yok; hiçbir dosya yazılmadı.", emptyList())
        return gate(edits, "code-mod: import $old -> $neu")
    }

    fun move(fromPath: String, toPath: String, updateReferences: Boolean): Outcome {
        val from = norm(fromPath)
        val to = norm(toPath)
        if (from.isBlank() || to.isBlank()) return fail("Yol boş olamaz.", emptyList())
        if (from == to) return fail("Kaynak ve hedef aynı.", emptyList())
        val original = textOf(from) ?: return fail("$from bulunamadı; hiçbir dosya yazılmadı.", listOf("$from:1"))
        if (textOf(to) != null) return fail("$to dolu; hiçbir dosya yazılmadı.", listOf("$to:1 hedef yol dolu"))
        val ext = extOf(from)
        var moved = original
        val oldPkg = SymbolParse.packageOf(original).ifBlank { packageFor(from) }
        val newPkg = if (ext == "kt" || ext == "kts" || ext == "java") packageFor(to) else null
        if (!newPkg.isNullOrBlank() && newPkg != oldPkg) moved = CodeLex.withPackage(moved, newPkg)
        val edits = ArrayList<StagedTransaction.Edit>()
        edits += StagedTransaction.Edit(from, original, null)
        edits += StagedTransaction.Edit(to, null, moved)
        if (updateReferences) {
            for (path in candidates(null)) {
                if (path == from || path == to) continue
                val text = textOf(path) ?: continue
                var next = text
                if (!oldPkg.isNullOrBlank() && !newPkg.isNullOrBlank() && oldPkg != newPkg) {
                    next = CodeLex.replaceImports(next, oldPkg, newPkg)
                }
                next = CodeLex.replacePath(next, from, to)
                if (next != text) edits += StagedTransaction.Edit(path, text, next)
            }
        }
        val relocated = SymbolParse.symbols(from, original).map { it.name }.toSet()
        return gate(edits, "code-mod: move $from -> $to", relocated, oldPkg, newPkg)
    }

    /** One commit callback. It is not called when the gates fail. */
    fun commit(outcome: Outcome, push: (List<StagedTransaction.Edit>) -> Unit): Outcome {
        if (!outcome.ok) return outcome
        push(outcome.edits)
        return outcome
    }

    private fun gate(
        edits: List<StagedTransaction.Edit>,
        message: String,
        relocated: Set<String> = emptySet(),
        oldPkg: String? = null,
        newPkg: String? = null,
    ): Outcome {
        val tx = StagedTransaction()
        edits.forEach { tx.stage(it.path, it.before, it.after) }
        val lines = ArrayList<String>()
        for (e in tx.edits()) {
            if (e.after == null) continue
            PreFlightValidator.check(e.path, e.before, e.after).forEach { lines += "${e.path}: ${it.rule} ${it.detail}" }
            scanner(e.path, e.before, e.after).forEach { lines += "${it.path}:${it.line} ${it.kind}" }
        }
        val sig = PreFlightValidator.signatureGate(graph, tx)
        if (sig.blocked) {
            for (site in sig.sites) {
                if (site.symbol in relocated && site.reason == "silindi") {
                    val after = tx.edit(site.path)?.after ?: textOf(site.path)
                    val stale = after == null || (
                        !oldPkg.isNullOrBlank() && !newPkg.isNullOrBlank() && oldPkg != newPkg &&
                            CodeLex.replaceImports(after, oldPkg, newPkg) != after
                        )
                    if (stale) lines += "${site.site} ${site.symbol} (import)"
                    continue
                }
                val after = tx.edit(site.path)?.after
                val stillCode = after != null && CodeLex.containsWord(after, site.symbol, includeStrings = false, extOf(site.path))
                val missing = tx.edit(site.path) == null
                if (stillCode || missing) lines += "${site.site} ${site.symbol} (${site.reason})"
            }
        }
        if (lines.isNotEmpty()) return fail(message + " durdu; hiçbir dosya yazılmadı.", lines.distinct())
        val changedChars = edits.sumOf { (it.after?.length ?: 0) + (it.before?.length ?: 0) }
        val files = edits.map { it.path }.distinct().size
        val saved = (changedChars / 4L).coerceAtLeast(files * 400L)
        val summary = "$message\n${edits.size} değişiklik, $files dosya.\n[code-mod files=$files savedTokens=$saved]"
        return Outcome(true, summary, edits, files, saved, emptyList())
    }

    private fun fail(summary: String, lines: List<String>) = Outcome(
        false,
        if (lines.isEmpty()) summary else summary + "\n" + lines.joinToString("\n"),
        emptyList(),
        0,
        0,
        lines,
    )

    private fun candidates(scope: List<String>?): List<String> {
        val all = graph.indexedPaths().filter { extOf(it) in CODE_EXT }.sorted()
        val scopes = scope?.map { norm(it) }?.filter { it.isNotBlank() }.orEmpty()
        if (scopes.isEmpty()) return all
        return all.filter { path -> scopes.any { s -> path == s || path.startsWith(s.trimEnd('/') + "/") } }
    }

    private fun textOf(path: String): String? = graph.source(path) ?: read(norm(path))

    private fun ident(name: String) = Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)

    companion object {
        private val CODE_EXT = setOf("kt", "kts", "java", "py", "gd")

        fun extOf(path: String) = path.substringAfterLast('.', "").lowercase()

        fun norm(path: String) = path.trim().removePrefix("./").trim('/')

        /** `src/main/kotlin/com/app/Foo.kt` -> `com.app`. */
        fun packageFor(path: String): String? {
            val p = path.replace('\\', '/')
            val markers = listOf(
                "src/main/java/", "src/main/kotlin/", "src/test/java/", "src/test/kotlin/",
                "src/androidTest/java/", "src/androidTest/kotlin/",
            )
            for (m in markers) {
                val i = p.indexOf(m)
                if (i >= 0) {
                    val rest = p.substring(i + m.length).substringBeforeLast('/', "")
                    if (rest.isNotBlank()) return rest.replace('/', '.')
                }
            }
            return null
        }
    }
}

/** Identifier edits that skip comments and, unless asked, string literals. */
object CodeLex {
    fun replaceWord(text: String, ext: String, old: String, new: String, includeStrings: Boolean): String =
        mapRegions(text, ext) { kind, chunk ->
            if (kind == Kind.CODE || (includeStrings && kind == Kind.STRING)) swap(chunk, old, new) else chunk
        }

    fun containsWord(text: String, name: String, includeStrings: Boolean, ext: String): Boolean {
        var hit = false
        mapRegions(text, ext) { kind, chunk ->
            if (!hit && (kind == Kind.CODE || (includeStrings && kind == Kind.STRING)) && swap(chunk, name, name) != chunk) {
                // swap with the same word is identity; detect with the regex instead
            }
            if (!hit && (kind == Kind.CODE || (includeStrings && kind == Kind.STRING)) && word(name).containsMatchIn(chunk)) hit = true
            chunk
        }
        return hit
    }

    fun replaceImports(text: String, oldPrefix: String, newPrefix: String): String {
        val old = oldPrefix.trim().trim('.', '/', '"')
        val neu = newPrefix.trim().trim('.', '/', '"')
        if (old.isBlank() || old == neu) return text
        return mapLines(text) { line ->
            val t = line.trimStart()
            val importish = t.startsWith("import ") || t.startsWith("from ") || t.startsWith("extends ") ||
                t.contains("preload(") || t.contains("load(") || t.contains("res://")
            if (!importish) line else replacePrefix(replacePrefix(line, old, neu), old.replace('.', '/'), neu.replace('.', '/'))
        }
    }

    fun replacePath(text: String, from: String, to: String): String {
        if (from.isBlank() || from == to) return text
        return mapLines(text) { line ->
            if (!line.contains(from)) line else line.replace(from, to)
        }
    }

    fun withPackage(text: String, pkg: String): String {
        val decl = "package $pkg"
        val lines = splitKeep(text)
        val idx = lines.indexOfFirst { it.trim().startsWith("package ") }
        if (idx >= 0) {
            lines[idx] = decl
            return join(lines, text.endsWith("\n"))
        }
        return decl + "\n\n" + text
    }

    private enum class Kind { CODE, STRING, COMMENT }

    private fun mapRegions(text: String, ext: String, transform: (Kind, String) -> String): String {
        val hash = ext in setOf("py", "gd")
        val out = StringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            val start = i
            val kind: Kind
            when {
                !hash && i + 1 < n && text[i] == '/' && text[i + 1] == '/' -> {
                    kind = Kind.COMMENT
                    i = text.indexOf('\n', i).let { if (it < 0) n else it }
                }
                hash && text[i] == '#' -> {
                    kind = Kind.COMMENT
                    i = text.indexOf('\n', i).let { if (it < 0) n else it }
                }
                !hash && i + 1 < n && text[i] == '/' && text[i + 1] == '*' -> {
                    kind = Kind.COMMENT
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) n else end + 2
                }
                text.startsWith("\"\"\"", i) || text.startsWith("'''", i) -> {
                    kind = Kind.STRING
                    val q = text.substring(i, i + 3)
                    val end = text.indexOf(q, i + 3)
                    i = if (end < 0) n else end + 3
                }
                text[i] == '"' || text[i] == '\'' -> {
                    kind = Kind.STRING
                    val q = text[i]
                    i++
                    while (i < n) {
                        if (text[i] == '\\') { i += 2; continue }
                        if (text[i] == q) { i++; break }
                        if (text[i] == '\n') break
                        i++
                    }
                }
                else -> {
                    kind = Kind.CODE
                    i++
                    while (i < n && !special(text, i, hash)) i++
                }
            }
            out.append(transform(kind, text.substring(start, i.coerceAtMost(n))))
        }
        return out.toString()
    }

    private fun special(text: String, i: Int, hash: Boolean): Boolean {
        val c = text[i]
        if (!hash && c == '/' && i + 1 < text.length && (text[i + 1] == '/' || text[i + 1] == '*')) return true
        if (hash && c == '#') return true
        if (c == '"' || c == '\'') return true
        return false
    }

    private fun word(name: String) = Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])")

    private fun swap(chunk: String, old: String, neu: String) = word(old).replace(chunk, neu)

    private fun replacePrefix(line: String, old: String, neu: String): String {
        if (old.isBlank() || old == neu) return line
        // `com.app` must not match again inside an already rewritten `com.app.feature`.
        val already = if (neu.startsWith(old)) Regex.escape(neu.removePrefix(old)) else ""
        val re = Regex("(?<![A-Za-z0-9_])" + Regex.escape(old) + (if (already.isEmpty()) "" else "(?!$already)") + "(?![A-Za-z0-9_])")
        return re.replace(line, neu)
    }

    private fun mapLines(text: String, f: (String) -> String): String {
        val lines = splitKeep(text)
        for (i in lines.indices) lines[i] = f(lines[i])
        return join(lines, text.endsWith("\n"))
    }

    private fun splitKeep(text: String): MutableList<String> {
        if (text.isEmpty()) return mutableListOf()
        val parts = text.split('\n')
        return if (text.endsWith("\n")) parts.dropLast(1).toMutableList() else parts.toMutableList()
    }

    private fun join(lines: List<String>, newline: Boolean): String {
        val body = lines.joinToString("\n")
        return if (newline) body + "\n" else body
    }
}
