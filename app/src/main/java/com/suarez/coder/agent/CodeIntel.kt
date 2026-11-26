package com.suarez.coder.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The cloud-built code index: definitions (ctags) and, per defined name, the lines that use it. */
data class CodeIndex(val repo: String, val sha: String, val builtAt: Long, val defs: Map<String, List<Def>>, val refs: Map<String, List<String>>) {
    data class Def(val path: String, val line: Int, val kind: String, val scope: String)

    /** Files that define or use [name]. */
    fun filesFor(name: String): Set<String> = (defs[name].orEmpty().map { it.path } + refs[name].orEmpty().map { it.substringBeforeLast(':') }).toSet()

    /** Names defined in [path] (what a change to that file can affect). */
    fun definedIn(path: String): List<String> = defs.filter { (_, ds) -> ds.any { it.path == path } }.keys.toList()

    companion object {
        fun parse(repo: String, json: String, builtAt: Long = System.currentTimeMillis()): CodeIndex {
            val o = JSONObject(json)
            val defs = HashMap<String, List<Def>>()
            o.optJSONObject("defs")?.let { d ->
                for (k in d.keys()) {
                    val a = d.getJSONArray(k)
                    defs[k] = (0 until a.length()).map { i -> a.getJSONArray(i).let { e -> Def(e.optString(0), e.optInt(1), e.optString(2), e.optString(3)) } }
                }
            }
            val refs = HashMap<String, List<String>>()
            o.optJSONObject("refs")?.let { r -> for (k in r.keys()) r.getJSONArray(k).let { a -> refs[k] = (0 until a.length()).map { a.getString(it) } } }
            return CodeIndex(repo, o.optString("sha"), builtAt, defs, refs)
        }
    }
}

class CodeIndexStore(private val dir: File) {
    private val mem = HashMap<String, CodeIndex>()
    private fun f(repo: String) = File(dir, repo.replace('/', '_') + ".json")
    private fun meta(repo: String) = File(dir, repo.replace('/', '_') + ".meta")

    @Synchronized fun get(repo: String): CodeIndex? = mem[repo] ?: runCatching {
        CodeIndex.parse(repo, f(repo).readText(), meta(repo).readText().trim().toLong())
    }.getOrNull()?.also { mem[repo] = it }

    @Synchronized fun put(repo: String, json: String) {
        dir.mkdirs()
        f(repo).writeText(json); meta(repo).writeText(System.currentTimeMillis().toString())
        mem.remove(repo)
    }
}

/** One symbol's code (signature, body, its doc comment) instead of the whole file. */
object SymbolExtractor {
    data class Range(val start: Int, val end: Int) // 1-based, inclusive

    fun find(text: String, name: String, hint: Int? = null): Range? {
        val lines = text.lines()
        val cands = lines.indices.filter { References.isDefinition(lines[it], name) }
        val def = (if (hint != null) cands.minByOrNull { kotlin.math.abs(it + 1 - hint) } else cands.firstOrNull()) ?: return null
        var start = def
        while (start > 0 && lines[start - 1].trim().let { it.startsWith("@") || it.startsWith("/**") || it.startsWith("*") || it.startsWith("*/") || it.startsWith("//") || it.startsWith("#") && !it.startsWith("#!") }) start--
        val end = endOf(lines, def)
        return Range(start + 1, end + 1)
    }

    private fun indent(l: String) = l.length - l.trimStart().length

    /** Python: the indented block; brace languages: matching braces; expression bodies: their continuation lines. */
    fun endOf(lines: List<String>, def: Int): Int {
        val first = lines[def]
        val base = indent(first)
        if (first.trimEnd().endsWith(":") && Regex("^\\s*(async\\s+)?(def|class)\\b").containsMatchIn(first)) {
            var i = def + 1; var last = def
            while (i < lines.size) {
                val l = lines[i]
                if (l.isNotBlank()) { if (indent(l) <= base) break; last = i }
                i++
            }
            return last
        }
        var depth = 0; var opened = false
        val stop = minOf(lines.size, def + MAX_LINES)
        for (i in def until stop) {
            val l = stripStrings(lines[i])
            for (c in l) { if (c == '{') { depth++; opened = true } else if (c == '}') depth-- }
            if (opened && depth <= 0) return i
            if (!opened) {
                val next = lines.getOrNull(i + 1) ?: return i
                val cont = l.trimEnd().let { it.endsWith("=") || it.endsWith("(") || it.endsWith(",") || it.endsWith("->") || it.endsWith("+") || it.endsWith("&&") || it.endsWith("||") }
                val deeper = next.isNotBlank() && indent(next) > base
                if (!cont && !deeper && !next.trim().startsWith("{") && !next.trim().startsWith(")")) return i
            }
        }
        return stop - 1
    }

    private fun stripStrings(l: String) = l.replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\"").replace(Regex("'(\\\\.|[^'\\\\])'"), "''").substringBefore("//")

    const val MAX_LINES = 400
}

/** Tests that exercise the changed files: changed tests, naming conventions, and (with the index) tests that use them. */
object TestSelector {
    private val TEST = Regex("(^|/)(test|tests|__tests__|spec)/|(Test|Tests|Spec)\\.(kt|java|kts)$|\\.(test|spec)\\.[cm]?[jt]sx?$|(^|/)test_[^/]+\\.py$|_test\\.py$")

    fun isTest(p: String) = TEST.containsMatchIn(p) && !p.contains("/androidTest/")

    fun select(changed: List<String>, tree: List<String>, index: CodeIndex? = null, max: Int = 15): List<String> {
        val out = LinkedHashSet<String>()
        changed.filter { isTest(it) && it in tree }.forEach { out += it }
        for (c in changed.filter { !isTest(it) }) {
            val base = c.substringAfterLast('/').substringBeforeLast('.')
            val names = setOf("${base}Test", "${base}Tests", "Test$base", "$base.test", "$base.spec", "test_$base", "${base}_test")
            tree.filter { isTest(it) && it.substringAfterLast('/').substringBeforeLast('.') in names }.forEach { out += it }
            index?.definedIn(c)?.take(20)?.forEach { n -> index.refs[n].orEmpty().map { it.substringBeforeLast(':') }.filter { isTest(it) && it in tree }.forEach { out += it } }
        }
        return out.take(max)
    }

    /** Gradle: module and fully qualified test classes of src/test sources; null when none. */
    fun gradle(tests: List<String>): Pair<String, List<String>>? {
        val jvm = tests.mapNotNull { p ->
            val m = Regex("^(?:(.+)/)?src/test/(?:java|kotlin)/(.+)\\.(kt|java)$").find(p) ?: return@mapNotNull null
            (m.groupValues[1]) to m.groupValues[2].replace('/', '.')
        }
        if (jvm.isEmpty()) return null
        val module = jvm.groupingBy { it.first }.eachCount().maxBy { it.value }.key
        return module.replace('/', ':') to jvm.filter { it.first == module }.map { it.second }.filter { Regex("^[\\w.$]+$").matches(it) }
    }

    fun files(tests: List<String>) = tests.filter { Regex("\\.(py|[cm]?[jt]sx?)$").containsMatchIn(it) && Regex("^[\\w./@-]+$").matches(it) }
}

/** Small deliberate bugs in changed code; a test suite worth its name notices them. */
object Mutator {
    data class Mutant(val id: String, val path: String, val line: Int, val search: String, val replace: String, val desc: String)

    private val OPS = listOf(
        Regex(" == ") to " != ", Regex(" != ") to " == ", Regex(" <= ") to " > ", Regex(" >= ") to " < ",
        Regex(" < ") to " >= ", Regex(" > ") to " <= ", Regex(" && ") to " || ", Regex(" \\|\\| ") to " && ",
        Regex("\\btrue\\b") to "false", Regex("\\bfalse\\b") to "true", Regex(" \\+ 1\\b") to " - 1", Regex(" - 1\\b") to " + 1",
    )

    /** Up to [max] mutants on lines [from]..[to] (1-based), each on a line that is unique in the file. */
    fun generate(path: String, text: String, from: Int, to: Int, max: Int = 6): List<Mutant> {
        val lines = text.lines()
        val counts = lines.groupingBy { it }.eachCount()
        val out = ArrayList<Mutant>()
        for (i in (from - 1).coerceAtLeast(0) until to.coerceAtMost(lines.size)) {
            val l = lines[i]
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("*") || t.startsWith("#") || t.startsWith("import ") || t.startsWith("package ") || counts[l] != 1) continue
            val code = l.substringBefore("//")
            for ((re, rep) in OPS) {
                val m = re.find(code) ?: continue
                if (code.substring(0, m.range.first).count { it == '"' } % 2 == 1) continue // inside a string
                val mutated = l.substring(0, m.range.first) + rep + l.substring(m.range.last + 1)
                out += Mutant("m${out.size + 1}", path, i + 1, l, mutated, "satır ${i + 1}: `${m.value.trim()}` → `${rep.trim()}`")
                break
            }
            if (out.size >= max) break
        }
        return out
    }

    fun toJson(ms: List<Mutant>) = JSONArray().also { a -> ms.forEach { a.put(JSONObject().put("id", it.id).put("path", it.path).put("search", it.search).put("replace", it.replace)) } }.toString()

    /** Mutants the tests caught, the ones they missed (descriptions), the ones that did not compile, or the run's error. */
    data class Score(val killed: Int, val survived: List<String>, val error: String?, val invalid: Int = 0)

    fun results(json: String, ms: List<Mutant>): Score {
        val o = JSONObject(json)
        if (o.has("error")) return Score(0, emptyList(), o.getString("error"))
        val a = o.getJSONArray("results")
        val st = (0 until a.length()).associate { a.getJSONObject(it).let { r -> r.getString("id") to r.getString("status") } }
        return Score(st.count { it.value == "killed" }, ms.filter { st[it.id] == "survived" }.map { it.desc + " (${it.path})" }, null,
            st.count { it.value == "invalid" })
    }
}
