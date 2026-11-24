package com.suarez.coder.agent

import com.suarez.coder.evolution.SessionTrace
import com.suarez.coder.llm.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/** The agent's visible task list (update_todos). The latest call in the chat is the current list. */
object Todos {
    enum class Status { PENDING, IN_PROGRESS, DONE }
    data class Item(val text: String, val status: Status)

    fun parse(args: JSONObject): List<Item> {
        val a = args.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull a.optString(i).takeIf { it.isNotBlank() }?.let { Item(it.trim(), Status.PENDING) }
            val text = o.optString("text").ifBlank { o.optString("content") }.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val st = when (o.optString("status").lowercase()) {
                "done", "completed", "complete" -> Status.DONE
                "in_progress", "doing", "active" -> Status.IN_PROGRESS
                else -> Status.PENDING
            }
            Item(text.take(160), st)
        }.take(20)
    }

    /** The list from the last update_todos call after the last user message (an old task's list is not shown). */
    fun latest(history: List<ChatMessage>): List<Item> {
        val from = history.indexOfLast { it is ChatMessage.User && !it.text.startsWith(Agent.AUTO_NUDGE) }.coerceAtLeast(0)
        for (i in history.indices.reversed()) {
            if (i < from) break
            val m = history[i] as? ChatMessage.Assistant ?: continue
            m.toolCalls.lastOrNull { it.name == "update_todos" }?.let { return parse(it.args) }
        }
        return emptyList()
    }

    fun render(items: List<Item>): String = items.joinToString("\n") { i ->
        (when (i.status) { Status.DONE -> "[x] "; Status.IN_PROGRESS -> "[>] "; Status.PENDING -> "[ ] " }) + i.text
    }

    /** Tool result: the list back plus a nudge when it is inconsistent. */
    fun ack(items: List<Item>): String {
        if (items.isEmpty()) return "Liste boş; 'items' dizisi gerekli."
        val doing = items.count { it.status == Status.IN_PROGRESS }
        val note = when {
            items.all { it.status == Status.DONE } -> "Tüm maddeler bitti."
            doing == 0 -> "Uyarı: hiçbir madde in_progress değil; şimdi üzerinde çalıştığını işaretle."
            doing > 1 -> "Uyarı: aynı anda yalnızca bir madde in_progress olmalı."
            else -> "Tamam."
        }
        return render(items) + "\n" + note
    }
}

/** @-mentions in a request: @path/file.kt, @ci, @diff, @https://... */
object Mentions {
    sealed class Mention {
        data class File(val path: String) : Mention()
        object Ci : Mention()
        object Diff : Mention()
        data class Url(val url: String) : Mention()
    }

    private val TOKEN = Regex("(?<![\\w@])@([^\\s,;()]+)")

    fun parse(text: String): List<Mention> = TOKEN.findAll(text).mapNotNull { m ->
        // Turkish suffixes after an apostrophe ("Main.kt'e", "@ci'daki") are not part of the name.
        val v = m.groupValues[1].substringBefore('\'').substringBefore('’').trimEnd('.', ':', '!', '?')
        when {
            v.equals("ci", true) -> Mention.Ci
            v.equals("diff", true) -> Mention.Diff
            v.startsWith("http://") || v.startsWith("https://") -> Mention.Url(v)
            // A file needs a dot or a slash (so "@ahmet" or an e-mail-like word is not a path).
            (v.contains('/') || Regex("\\.[A-Za-z0-9]{1,8}$").containsMatchIn(v)) && !v.contains("..") -> Mention.File(v.trimStart('/'))
            else -> null
        }
    }.distinct().take(8).toList()

    /** What to suggest while typing "@xy": fixed tokens first, then matching paths. */
    fun suggestions(input: String, paths: List<String>): List<String> {
        val m = Regex("@([^\\s@]*)$").find(input) ?: return emptyList()
        val q = m.groupValues[1].lowercase()
        val fixed = listOf("@ci", "@diff", "@https://").filter { it.substring(1).startsWith(q) }
        val files = if (q.isEmpty()) emptyList() else paths.filter { it.lowercase().contains(q) }
            .sortedWith(compareBy({ !it.substringAfterLast('/').lowercase().startsWith(q) }, { it.length })).take(6).map { "@$it" }
        return (fixed + files).take(8)
    }
}

/** Successful earlier tasks that look like the new one, as a short hint (case-based memory). */
object SimilarTasks {
    private val STOP = setOf("bir", "ve", "ile", "bu", "şu", "için", "da", "de", "the", "a", "to", "and", "of", "in", "yap", "ekle", "et", "olsun")

    fun words(s: String) = Regex("[\\p{L}\\p{N}_]{3,}").findAll(s.lowercase()).map { it.value }.filter { it !in STOP }.map { it.take(6) }.toSet()

    fun similarity(a: String, b: String): Double {
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    fun pick(traces: List<SessionTrace>, request: String, repo: String, k: Int = 2, min: Double = 0.25): List<SessionTrace> =
        traces.asSequence().filter { it.success && it.rating >= 0 && it.request.isNotBlank() && it.request != request }
            .map { it to similarity(it.request, request) + (if (it.repo.equals(repo, true)) 0.05 else 0.0) }
            .filter { it.second >= min }.sortedByDescending { it.second }.take(k).map { it.first }.toList()

    fun hint(found: List<SessionTrace>): String? {
        if (found.isEmpty()) return null
        return "Similar tasks that succeeded before (reuse the approach if it fits; do not assume the code is unchanged):\n" +
            found.joinToString("\n") { t ->
                "- \"${t.request.take(160)}\" (${t.repo}, ${t.route ?: "?"}, ${t.turnCount} turns" + (if (t.ciRuns > 0) ", CI passed" else "") + ")"
            }
    }
}

/** A branch head saved before the agent's first write in a run, so the user can rewind to it. */
object Checkpoints {
    data class Point(val repo: String, val branch: String, val sha: String?)

    private val MARK = Regex("\\[\\[checkpoint repo=(\\S+) branch=(\\S+) sha=(\\S+)]]")

    fun marker(p: Point) = "[[checkpoint repo=${p.repo} branch=${p.branch} sha=${p.sha ?: "none"}]]"

    fun find(text: String?): Point? = text?.let { MARK.find(it) }?.let {
        Point(it.groupValues[1], it.groupValues[2], it.groupValues[3].takeIf { s -> s != "none" })
    }
}

/** A user-defined mode (Kilo-style custom modes): its own instructions, tool access and model. */
data class CustomMode(val name: String, val instructions: String, val access: Access = Access.FULL, val model: String = "") {
    enum class Access(val label: String) { FULL("Tam"), READ_ONLY("Salt okunur"), NO_OPS("Kod yazar, PR/merge yok") }

    fun toJson(): JSONObject = JSONObject().put("name", name).put("instructions", instructions).put("access", access.name).put("model", model)

    companion object {
        fun fromJson(o: JSONObject) = CustomMode(o.optString("name"), o.optString("instructions"),
            runCatching { Access.valueOf(o.optString("access")) }.getOrDefault(Access.FULL), o.optString("model"))

        fun list(json: String): List<CustomMode> = runCatching {
            val a = JSONArray(json); (0 until a.length()).map { fromJson(a.getJSONObject(it)) }.filter { it.name.isNotBlank() }
        }.getOrDefault(emptyList())

        fun toJson(list: List<CustomMode>): String = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()

        val EXAMPLES = listOf(
            CustomMode("Soru-cevap", "Only explain and answer. Never change the repository.", Access.READ_ONLY),
            CustomMode("Hata ayıklayıcı", "Debugging mode: first reproduce the failure from CI logs or code, state the root cause in one " +
                "sentence, then make the smallest fix. Never change unrelated code.", Access.FULL),
        )
    }
}

/** Library versions from build files, so the model writes code for the versions the project really uses. */
object DepVersions {
    fun extract(files: Map<String, String>): List<String> {
        val out = LinkedHashSet<String>()
        files["gradle/libs.versions.toml"]?.let { t ->
            val section = t.substringAfter("[versions]", "").substringBefore("\n[")
            Regex("(?m)^\\s*([\\w.-]+)\\s*=\\s*\"([^\"]+)\"").findAll(section).forEach { out += "${it.groupValues[1]} ${it.groupValues[2]}" }
        }
        for (f in listOf("app/build.gradle.kts", "build.gradle.kts", "app/build.gradle", "build.gradle")) files[f]?.let { t ->
            Regex("\"([\\w.-]+):([\\w.-]+):(\\d[\\w.-]*)\"").findAll(t).forEach { out += "${it.groupValues[1]}:${it.groupValues[2]} ${it.groupValues[3]}" }
            Regex("platform\\(\"([\\w.-]+):([\\w.-]+):(\\d[\\w.-]*)\"\\)").findAll(t).forEach { out += "${it.groupValues[2]} (BOM) ${it.groupValues[3]}" }
        }
        files["package.json"]?.let { t ->
            runCatching {
                val o = JSONObject(t)
                for (k in listOf("dependencies", "devDependencies")) o.optJSONObject(k)?.let { d -> d.keys().forEach { n -> out += "$n ${d.optString(n)}" } }
            }
        }
        files["pubspec.yaml"]?.let { t -> Regex("(?m)^  ([\\w_]+):\\s*\\^?(\\d[\\w.+-]*)\\s*$").findAll(t).forEach { out += "${it.groupValues[1]} ${it.groupValues[2]}" } }
        files["project.godot"]?.let { t -> Regex("config/features=PackedStringArray\\(\"(\\d+\\.\\d+)").find(t)?.let { out += "Godot ${it.groupValues[1]}" } }
        files["requirements.txt"]?.lines()?.forEach { l -> Regex("^([\\w.-]+)\\s*[=~><]=\\s*([\\w.]+)").find(l.trim())?.let { out += "${it.groupValues[1]} ${it.groupValues[2]}" } }
        return out.take(60).toList()
    }

    val FILES = listOf("gradle/libs.versions.toml", "app/build.gradle.kts", "build.gradle.kts", "app/build.gradle", "build.gradle",
        "package.json", "pubspec.yaml", "project.godot", "requirements.txt")
}

/** Commit-time self-check by a cheap model: only definite defects block the commit. */
object SelfReview {
    data class Verdict(val ok: Boolean, val problems: List<String>)

    const val SYSTEM = "You check a code change right before it is committed. Report ONLY definite defects that would break the " +
        "build or obviously break behavior: syntax errors, a missing import for a newly used symbol, a call to something removed or " +
        "renamed in this change, an unfinished edit (TODO stubs, '...' left in code, duplicated or truncated blocks), a wrong " +
        "file path. Do not comment on style, naming or design, and do not guess about code you cannot see. Reply with JSON only: " +
        "{\"ok\":true} or {\"ok\":false,\"problems\":[\"file:line - problem\"]} (at most 3 problems)."

    fun parse(reply: String): Verdict? {
        val s = reply.substringAfter('{', "").substringBeforeLast('}', "")
        if (s.isEmpty()) return null
        val o = runCatching { JSONObject("{$s}") }.getOrNull() ?: return null
        val problems = o.optJSONArray("problems")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        return Verdict(o.optBoolean("ok", problems.isEmpty()) || problems.isEmpty(), problems.take(3))
    }

    /** Compact view of a change for the reviewer: changed hunks with a few lines of context. */
    fun view(changes: List<Triple<String, String?, String?>>, maxChars: Int = 24_000): String = buildString {
        for ((path, before, after) in changes) {
            append("### ").append(path).append(if (before == null) " (new)" else if (after == null) " (deleted)" else "").append('\n')
            if (after == null) continue
            if (before == null) { append(after.take(6_000)).append('\n'); continue }
            append(hunks(before, after)).append('\n')
            if (length > maxChars) { append("…(kesildi)\n"); break }
        }
    }.take(maxChars)

    /** Line hunks with 3 lines of context (simple common prefix/suffix per region; enough for a reviewer). */
    fun hunks(before: String, after: String, ctx: Int = 3): String {
        val a = before.lines(); val b = after.lines()
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val start = (p - ctx).coerceAtLeast(0)
        return buildString {
            append("@@ line ${start + 1}\n")
            for (i in start until p) append("  ").append(a[i]).append('\n')
            for (i in p until a.size - s) append("- ").append(a[i]).append('\n')
            for (i in p until b.size - s) append("+ ").append(b[i]).append('\n')
            for (i in (b.size - s) until minOf(b.size, b.size - s + ctx)) append("  ").append(b[i]).append('\n')
        }
    }
}
