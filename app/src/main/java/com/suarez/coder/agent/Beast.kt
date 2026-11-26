package com.suarez.coder.agent

import com.suarez.coder.llm.ModelRef
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The user's ordered list of stronger models: attempts climb it one by one. */
object Escalation {
    data class Step(val ref: ModelRef, val role: String)

    /**
     * Repair attempts for one file group: the editor first, then the chain in the user's order (one attempt each, the
     * last model keeps the remaining attempts). With one escalation model the last two attempts use it (as before).
     */
    fun ladder(max: Int, editor: ModelRef, chain: List<ModelRef>, early: Boolean = false): List<Step> {
        val n = max.coerceAtLeast(1)
        val c = chain.filter { it.key != editor.key }.distinctBy { it.key }
        if (c.isEmpty() || n < 3) return List(n) { Step(editor, "editor") }
        val editorTries = if (early) 1 else (n - maxOf(2, c.size)).coerceAtLeast(1)
        val rest = n - editorTries
        val used = c.take(rest)
        val out = ArrayList<Step>()
        repeat(editorTries) { out += Step(editor, "editor") }
        for (i in 0 until rest) out += Step(used[minOf(i, used.size - 1)], "escalation")
        return out
    }

    /** The model after [current] in the chain, or null at the top. */
    fun next(chain: List<ModelRef>, current: ModelRef): ModelRef? {
        val c = chain.distinctBy { it.key }
        val i = c.indexOfFirst { it.key == current.key }
        return if (i < 0) c.firstOrNull { it.key != current.key } else c.getOrNull(i + 1)
    }

    fun parse(text: String): List<String> = text.lines().map { it.trim() }.filter { it.contains('/') }.distinct()
}

/** CI error signatures that ignore line numbers, hashes and temp paths, so "the same error again" is recognized. */
object ErrorSig {
    fun normalize(sig: String): String = sig.lowercase()
        .replace(Regex("[0-9a-f]{7,40}"), "#")
        .replace(Regex(":\\d+(:\\d+)?"), ":n")
        .replace(Regex("\\d+"), "n")
        .replace(Regex("/tmp/\\S+|/home/runner/\\S+"), "/p")
        .replace(Regex("\\s+"), " ").trim()

    fun similar(a: String, b: String): Boolean {
        val x = normalize(a); val y = normalize(b)
        if (x.isBlank() || y.isBlank()) return false
        if (x == y) return true
        val wx = x.split(' ', '|', ':', '(', ')', '.').filter { it.length > 2 }.toSet()
        val wy = y.split(' ', '|', ':', '(', ')', '.').filter { it.length > 2 }.toSet()
        if (wx.isEmpty() || wy.isEmpty()) return false
        return wx.intersect(wy).size.toDouble() / wx.union(wy).size >= 0.6
    }
}

/** CI error → how it was fixed, per repo (filled when a red CI turns green in the same run). */
class FixMemory(private val file: File) {
    data class Fix(val repo: String, val signature: String, val fix: String, val at: Long)

    @Synchronized fun all(): List<Fix> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Fix(o.getString("repo"), o.getString("sig"), o.getString("fix"), o.optLong("at")) } }
    }.getOrDefault(emptyList())

    @Synchronized fun record(f: Fix) {
        val list = (all().filterNot { it.repo.equals(f.repo, true) && ErrorSig.normalize(it.signature) == ErrorSig.normalize(f.signature) } + f).takeLast(MAX)
        file.parentFile?.mkdirs()
        file.writeText(JSONArray().also { a -> list.forEach { a.put(JSONObject().put("repo", it.repo).put("sig", it.signature).put("fix", it.fix).put("at", it.at)) } }.toString())
    }

    /** Same repo first, then any repo (common toolchain errors repeat across projects). */
    fun lookup(repo: String, signature: String, k: Int = 2): List<Fix> {
        val all = all().filter { ErrorSig.similar(it.signature, signature) }
        return (all.filter { it.repo.equals(repo, true) }.sortedByDescending { it.at } + all.filter { !it.repo.equals(repo, true) }.sortedByDescending { it.at })
            .take(k)
    }

    fun hint(found: List<Fix>): String? = if (found.isEmpty()) null else
        "FIX MEMORY: this error was seen before; what fixed it:\n" + found.joinToString("\n") { "- (${it.repo}) ${it.fix.take(500)}" } +
            "\nCheck that it applies here before reusing it."

    companion object { const val MAX = 200 }
}

/** A cheap model reads the architect's plan before any code is written. */
object PlanCritic {
    const val SYSTEM = "You review a code-change PLAN before it is implemented. You see the plan and a repo map (paths, types, " +
        "signatures). Report only concrete gaps that would make the change fail or break other code: a caller or implementation of a " +
        "changed signature that is not in the plan, a missing registration (manifest, DI, navigation, routes, exports), a needed " +
        "import/dependency/resource, a test that must change, a step that contradicts the code. No style or design opinions. " +
        "JSON only: {\"ok\":true} or {\"ok\":false,\"issues\":[\"...\"],\"add_files\":[{\"path\":\"...\",\"action\":\"modify|create\"," +
        "\"instructions\":\"...\"}]} (at most 4 issues, add_files only for files that must change)."

    data class Verdict(val ok: Boolean, val issues: List<String>, val addFiles: List<PlanFile>)

    fun parse(reply: String): Verdict? {
        val s = reply.substringAfter('{', "").substringBeforeLast('}', "")
        if (s.isEmpty()) return null
        val o = runCatching { JSONObject("{$s}") }.getOrNull() ?: return null
        val issues = o.optJSONArray("issues")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty().take(4)
        val add = o.optJSONArray("add_files")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val f = a.optJSONObject(i) ?: return@mapNotNull null
                val path = f.optString("path").trim().trimStart('/').takeIf { it.isNotBlank() && !it.contains("..") } ?: return@mapNotNull null
                val action = f.optString("action").takeIf { it == "create" } ?: "modify"
                PlanFile(path, action, f.optString("instructions").ifBlank { "Bkz. eleştirmen notu." })
            }
        }.orEmpty().take(3)
        return Verdict(o.optBoolean("ok", issues.isEmpty() && add.isEmpty()) && issues.isEmpty() && add.isEmpty(), issues, add)
    }
}

/** find_references: lines that use a symbol (whole word), with line numbers. */
object References {
    fun lines(text: String, symbol: String, max: Int = 12): List<Pair<Int, String>> {
        val re = Regex("(?<![\\w$])" + Regex.escape(symbol) + "(?![\\w$])")
        return text.lines().withIndex().filter { re.containsMatchIn(it.value) }.take(max).map { it.index + 1 to it.value.trim().take(200) }
    }

    /** Definition-looking lines rank first. */
    fun isDefinition(line: String, symbol: String) =
        Regex("\\b(fun|class|interface|object|val|var|def|function|const|let|type|enum|struct|func)\\s+(<[^>]*>\\s*)?(\\w+\\.)?" + Regex.escape(symbol) + "\\b").containsMatchIn(line)
}

/** Tasks queued for unattended runs (charging + Wi-Fi), each on its own branch with a PR. */
data class QueuedTask(
    val id: String,
    val repo: String,
    val text: String,
    val state: State = State.QUEUED,
    val note: String = "",
    val sessionId: String = "",
    val source: String = "user",
    val at: Long = System.currentTimeMillis(),
) {
    enum class State(val label: String) { QUEUED("Sırada"), RUNNING("Çalışıyor"), DONE("Bitti"), FAILED("Başarısız") }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("repo", repo).put("text", text).put("state", state.name)
        .put("note", note).put("session", sessionId).put("source", source).put("at", at)

    /** Queue runs may only write to their own branch prefix (and open PRs from it); never merge. */
    val branchPrefix get() = "coder/q-" + id.takeLast(6)

    companion object {
        fun fromJson(o: JSONObject) = QueuedTask(o.getString("id"), o.optString("repo"), o.optString("text"),
            runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.QUEUED), o.optString("note"), o.optString("session"),
            o.optString("source", "user"), o.optLong("at"))
    }
}

class TaskQueueStore(private val file: File) {
    @Synchronized fun all(): List<QueuedTask> = runCatching {
        val a = JSONArray(file.readText()); (0 until a.length()).map { QueuedTask.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized fun save(list: List<QueuedTask>) {
        file.parentFile?.mkdirs()
        file.writeText(JSONArray().also { a -> list.takeLast(100).forEach { a.put(it.toJson()) } }.toString())
    }

    @Synchronized fun add(t: QueuedTask) = save(all() + t)
    @Synchronized fun update(id: String, f: (QueuedTask) -> QueuedTask) = save(all().map { if (it.id == id) f(it) else it })
    @Synchronized fun remove(id: String) = save(all().filter { it.id != id })
    fun next(): QueuedTask? = all().firstOrNull { it.state == QueuedTask.State.QUEUED }

    /** A run killed mid-way (process death) goes back to the queue once, then fails. */
    @Synchronized fun recoverStale(maxAgeMs: Long = 3 * 3_600_000L, now: Long = System.currentTimeMillis()) =
        save(all().map { if (it.state == QueuedTask.State.RUNNING && now - it.at > maxAgeMs) it.copy(state = QueuedTask.State.FAILED, note = "Yarıda kaldı.") else it })

    companion object {
        const val ISSUE_LABEL = "coder"

        /** The prompt for a queued task: own branch, PR at the end, never merge. */
        fun prompt(t: QueuedTask): String = t.text.trim() + "\n\n---\nKuyruk görevi (gözetimsiz): yalnızca `${t.branchPrefix}` ile başlayan yeni bir dalda çalış, " +
            "CI yeşil olunca bu daldan varsayılan dala pull request aç ve PR bağlantısıyla kısa bir özet yaz. Merge etme. " +
            "Belirsizlik varsa makul varsayımı seç ve PR açıklamasına yaz."
    }
}

/** Turns the user's PR review comments into short project rules. */
object ReviewLearner {
    const val SYSTEM = "From code-review comments a developer left on pull requests, extract the lasting project conventions they " +
        "imply (what to always/never do in this codebase). Skip one-off remarks, praise and questions. Merge them with the existing " +
        "rules, drop duplicates, keep each rule one short imperative line in the developer's language, at most 12 rules in total. " +
        "Output only the rules, one per line, each starting with '- '."

    fun rules(reply: String): List<String> = reply.lines().map { it.trim() }.filter { it.startsWith("- ") && it.length > 4 }.map { it.take(200) }.take(12)
}
