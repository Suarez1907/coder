package com.suarez.coder.e2e

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One fake server for end-to-end tests: an in-memory GitHub repository (refs, commits, trees, blobs, contents, Actions,
 * pull requests) and a scripted OpenAI-compatible model API. The agent talks to it exactly as to the real services.
 */
class FakeWorld(val repo: String = "me/app", defaultBranch: String = "main", files: Map<String, String> = emptyMap()) {
    val server = MockWebServer()

    // ---- Git ----
    private var seq = 0
    private fun sha() = String.format("%040x", ++seq)
    val trees = HashMap<String, Map<String, String>>()
    private val blobs = HashMap<String, String>()
    data class Commit(val tree: String, val parent: String?, val message: String)
    val commits = LinkedHashMap<String, Commit>()
    val branches = LinkedHashMap<String, String>()
    var defaultBranch = defaultBranch
    var private = true

    init {
        val t = sha(); trees[t] = files
        val c = sha(); commits[c] = Commit(t, null, "init")
        branches[defaultBranch] = c
    }

    fun filesAt(ref: String): Map<String, String>? {
        val c = branches[ref] ?: ref.takeIf { commits.containsKey(it) } ?: return null
        return trees[commits.getValue(c).tree]
    }

    fun file(branch: String, path: String) = filesAt(branch)?.get(path)
    fun commitMessages(branch: String): List<String> {
        val out = ArrayList<String>(); var c = branches[branch]
        while (c != null) { val k = commits.getValue(c); out += k.message; c = k.parent }
        return out
    }

    // ---- Actions: CI result for a commit's files (conclusion, log) ----
    var ci: ((Map<String, String>) -> Pair<String, String>)? = null
    private val runs = HashMap<Long, Pair<String, Pair<String, String>>>() // id -> (sha, (conclusion, log))
    val pulls = CopyOnWriteArrayList<JSONObject>()
    var merges = 0

    // ---- Model API ----
    val llmRequests = CopyOnWriteArrayList<JSONObject>()
    /** Assertion failures and crashes inside handlers (checked by the test). */
    val errors = CopyOnWriteArrayList<Throwable>()
    /** Each handler answers one request; when empty, the default handler answers. */
    val script = java.util.concurrent.ConcurrentLinkedQueue<(JSONObject) -> MockResponse>()
    /** Answers requests the script does not cover (e.g. the self-review call). */
    var fallback: (JSONObject) -> MockResponse = { reply("Tamam.") }
    /** Answers every request whose system prompt contains the key (checked before the script). */
    val bySystem = LinkedHashMap<String, (JSONObject) -> MockResponse>()

    fun start(): FakeWorld {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeWorld) {
                // A failed assertion inside a script must not leave the client waiting: answer 500 and remember it.
                try { route(request) } catch (e: Throwable) { errors += e; MockResponse().setResponseCode(500).setBody("fake: ${e.message}") }
            }
        }
        server.start()
        return this
    }

    fun url() = server.url("/").toString().trimEnd('/')

    private fun json(o: Any, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(o.toString())
    private fun notFound() = json(JSONObject().put("message", "Not Found"), 404)

    private fun route(r: RecordedRequest): MockResponse {
        val path = r.path ?: "/"
        val body = r.body.readUtf8()
        if (path.startsWith("/chat/completions")) {
            val req = JSONObject(body)
            llmRequests += req
            val system = req.getJSONArray("messages").optJSONObject(0)?.optString("content").orEmpty()
            bySystem.entries.firstOrNull { system.contains(it.key) }?.let { return it.value(req) }
            return (script.poll() ?: fallback)(req)
        }
        if (path == "/user") return json(JSONObject().put("login", "me"))
        val prefix = "/repos/$repo"
        if (!path.startsWith(prefix)) return notFound()
        val rest = path.removePrefix(prefix)
        val q = rest.substringAfter('?', "")
        val p = rest.substringBefore('?')
        fun param(k: String) = q.split('&').firstOrNull { it.startsWith("$k=") }?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
        val m = r.method ?: "GET"
        return when {
            p.isEmpty() || p == "/" -> json(JSONObject().put("default_branch", defaultBranch).put("private", private).put("pushed_at", "2026-11-20T00:00:00Z"))
            p.startsWith("/contents/") -> {
                val file = URLDecoder.decode(p.removePrefix("/contents/"), "UTF-8")
                val text = filesAt(param("ref") ?: defaultBranch)?.get(file) ?: return notFound()
                MockResponse().setBody(text)
            }
            p.startsWith("/git/ref/heads/") -> {
                val b = URLDecoder.decode(p.removePrefix("/git/ref/heads/"), "UTF-8")
                branches[b]?.let { json(JSONObject().put("object", JSONObject().put("sha", it))) } ?: notFound()
            }
            p == "/git/refs" && m == "POST" -> {
                val o = JSONObject(body); branches[o.getString("ref").removePrefix("refs/heads/")] = o.getString("sha"); json(JSONObject(), 201)
            }
            p.startsWith("/git/refs/heads/") && m == "PATCH" -> {
                val b = URLDecoder.decode(p.removePrefix("/git/refs/heads/"), "UTF-8")
                if (!branches.containsKey(b)) return notFound()
                branches[b] = JSONObject(body).getString("sha"); json(JSONObject())
            }
            p.startsWith("/git/refs/heads/") && m == "DELETE" -> {
                val b = URLDecoder.decode(p.removePrefix("/git/refs/heads/"), "UTF-8")
                if (branches.remove(b) == null) notFound() else MockResponse().setResponseCode(204)
            }
            p.startsWith("/git/commits/") && m == "GET" -> {
                val c = commits[p.removePrefix("/git/commits/")] ?: return notFound()
                json(JSONObject().put("tree", JSONObject().put("sha", c.tree)))
            }
            p == "/git/blobs" && m == "POST" -> {
                val o = JSONObject(body); val s = sha()
                blobs[s] = if (o.optString("encoding") == "base64") String(Base64.getDecoder().decode(o.getString("content"))) else o.getString("content")
                json(JSONObject().put("sha", s), 201)
            }
            p == "/git/trees" && m == "POST" -> {
                val o = JSONObject(body)
                val files = HashMap(trees[o.optString("base_tree")] ?: emptyMap())
                val a = o.getJSONArray("tree")
                for (i in 0 until a.length()) {
                    val e = a.getJSONObject(i)
                    when {
                        e.has("content") -> files[e.getString("path")] = e.getString("content")
                        e.has("sha") && e.isNull("sha") -> files.remove(e.getString("path"))
                        e.has("sha") -> files[e.getString("path")] = blobs.getValue(e.getString("sha"))
                    }
                }
                val s = sha(); trees[s] = files; json(JSONObject().put("sha", s), 201)
            }
            p == "/git/commits" && m == "POST" -> {
                val o = JSONObject(body); val s = sha()
                commits[s] = Commit(o.getString("tree"), o.optJSONArray("parents")?.optString(0), o.getString("message"))
                json(JSONObject().put("sha", s), 201)
            }
            p.startsWith("/git/trees/") -> {
                val files = filesAt(URLDecoder.decode(p.removePrefix("/git/trees/"), "UTF-8")) ?: return notFound()
                json(JSONObject().put("tree", JSONArray().also { a -> files.forEach { (k, v) -> a.put(JSONObject().put("path", k).put("type", "blob").put("size", v.length).put("sha", "b" + v.hashCode())) } }))
            }
            p.startsWith("/commits/") -> {
                val ref = URLDecoder.decode(p.removePrefix("/commits/"), "UTF-8")
                val c = branches[ref] ?: ref.takeIf { commits.containsKey(it) } ?: return notFound()
                if (r.getHeader("Accept")?.contains("sha") == true) MockResponse().setBody(c)
                else {
                    val k = commits.getValue(c)
                    val now = trees.getValue(k.tree); val before = k.parent?.let { trees[commits.getValue(it).tree] }.orEmpty()
                    val changed = (now.keys + before.keys).filter { now[it] != before[it] }
                    json(JSONObject().put("sha", c).put("commit", JSONObject().put("message", k.message))
                        .put("parents", JSONArray().also { a -> k.parent?.let { a.put(JSONObject().put("sha", it)) } })
                        .put("files", JSONArray().also { a -> changed.forEach { f -> a.put(JSONObject().put("filename", f).put("status", "modified").put("patch", "+${now[f]}")) } }))
                }
            }
            p == "/actions/runs" -> {
                val head = param("head_sha")
                val a = JSONArray()
                if (head != null && ci != null && commits.containsKey(head)) {
                    val id = head.takeLast(8).toLong(16)
                    val result = runs.getOrPut(id) { head to ci!!(trees.getValue(commits.getValue(head).tree)) }
                    a.put(JSONObject().put("id", id).put("name", "CI").put("status", "completed").put("conclusion", result.second.first).put("html_url", "https://ci/$id"))
                }
                json(JSONObject().put("workflow_runs", a))
            }
            p.matches(Regex("/actions/runs/\\d+/jobs")) -> {
                val id = p.split('/')[3].toLong()
                val c = runs[id]?.second?.first ?: return notFound()
                json(JSONObject().put("jobs", JSONArray().put(JSONObject().put("id", id * 10).put("name", "build").put("conclusion", c))))
            }
            p.matches(Regex("/actions/jobs/\\d+/logs")) -> MockResponse().setBody(runs[p.split('/')[3].toLong() / 10]?.second?.second ?: "")
            p == "/releases" -> json(JSONArray())
            p == "/pulls" && m == "POST" -> {
                val o = JSONObject(body).put("number", pulls.size + 1).put("html_url", "https://github.com/$repo/pull/${pulls.size + 1}")
                pulls += o; json(o, 201)
            }
            p.matches(Regex("/pulls/\\d+/merge")) -> { merges++; json(JSONObject().put("merged", true).put("sha", sha())) }
            else -> notFound()
        }
    }

    fun shutdown() = server.shutdown()

    companion object {
        /** An OpenAI-compatible chat completion with optional tool calls. */
        fun reply(content: String, vararg calls: Pair<String, JSONObject>): MockResponse {
            val msg = JSONObject().put("content", content)
            if (calls.isNotEmpty()) msg.put("tool_calls", JSONArray().also { a ->
                calls.forEachIndexed { i, (name, args) ->
                    a.put(JSONObject().put("id", "call_${System.nanoTime()}_$i").put("type", "function")
                        .put("function", JSONObject().put("name", name).put("arguments", args.toString())))
                }
            })
            return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject()
                .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", msg)))
                .put("usage", JSONObject().put("prompt_tokens", 100).put("completion_tokens", 10)).toString())
        }

        fun diff(path: String, search: String, replace: String) = "$path\n<<<<<<< SEARCH\n$search\n=======\n$replace\n>>>>>>> REPLACE"

        /** Tool results the model saw in this request. */
        fun toolResults(req: JSONObject): List<String> {
            val a = req.getJSONArray("messages")
            return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("role") == "tool" }.map { it.optString("content") }
        }

        fun userTexts(req: JSONObject): List<String> {
            val a = req.getJSONArray("messages")
            return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("role") == "user" }.map { it.opt("content").toString() }
        }
    }
}
