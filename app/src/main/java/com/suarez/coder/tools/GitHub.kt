package com.suarez.coder.tools

import android.util.Base64
import com.suarez.coder.llm.Http
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class ToolException(message: String) : Exception(message)

class GitHub(private val token: () -> String) {
    private val api = "https://api.github.com"

    data class FileChange(val path: String, val content: String?, val delete: Boolean)

    /** Builds an authenticated request (for streaming downloads). */
    fun request(method: String, path: String, accept: String = "application/vnd.github+json"): Request {
        val t = token()
        if (t.isBlank()) throw ToolException("GitHub token girilmemiş.")
        val url = if (path.startsWith("http")) path else api + (if (path.startsWith("/")) path else "/$path")
        return Request.Builder().url(url)
            .header("Authorization", "Bearer $t").header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "Coder-Android")
            .method(method, null).build()
    }

    /** Commit sha of a branch/tag (default branch when [ref] is null). */
    suspend fun headSha(repo: String, ref: String?): String {
        val r = ref?.takeIf { it.isNotBlank() } ?: defaultBranch(repo)
        val res = call("GET", "/repos/$repo/commits/${enc(r)}", accept = "application/vnd.github.sha")
        if (res.code !in 200..299) throw ToolException("Commit bulunamadı ($r): HTTP ${res.code}")
        return res.body.trim()
    }

    private suspend fun call(method: String, path: String, body: Any? = null, accept: String = "application/vnd.github+json"): Http.Result {
        val t = token()
        if (t.isBlank()) throw ToolException("GitHub token girilmemiş. Kullanıcıdan Ayarlar'a GitHub token eklemesini iste.")
        val url = if (path.startsWith("http")) path else api + (if (path.startsWith("/")) path else "/$path")
        val reqBody = when {
            body != null -> body.toString().toRequestBody(Http.JSON)
            method in setOf("POST", "PUT", "PATCH") -> "{}".toRequestBody(Http.JSON)
            else -> null
        }
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $t")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Coder-Android")
            .method(method, reqBody)
            .build()
        return Http.execute(req)
    }

    private suspend fun json(method: String, path: String, body: Any? = null): JSONObject {
        val r = call(method, path, body)
        if (r.code !in 200..299) throw ToolException("GitHub $method $path -> ${r.code}: ${r.body.take(800)}")
        return if (r.body.isBlank()) JSONObject() else JSONObject(r.body)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun encPath(p: String) = p.trim('/').split('/').joinToString("/") { enc(it) }

    suspend fun defaultBranch(repo: String): String = json("GET", "/repos/$repo").getString("default_branch")

    suspend fun listRepos(): String {
        val r = call("GET", "/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member")
        if (r.code !in 200..299) throw ToolException("GitHub -> ${r.code}: ${r.body.take(500)}")
        val arr = JSONArray(r.body)
        return (0 until arr.length()).joinToString("\n") {
            val o = arr.getJSONObject(it)
            "${o.getString("full_name")}  [${if (o.getBoolean("private")) "private" else "public"}, default: ${o.optString("default_branch")}]" +
                (o.optString("description").takeIf { d -> d.isNotBlank() && d != "null" }?.let { d -> " - $d" } ?: "")
        }.ifBlank { "(Hiç repo yok)" }
    }

    data class TreeEntry(val path: String, val size: Long, val sha: String = "")

    /** Returns (branch, blobs) or null if the repository is empty. */
    suspend fun treeEntries(repo: String, ref: String?): Pair<String, List<TreeEntry>>? {
        val branch = ref?.takeIf { it.isNotBlank() } ?: defaultBranch(repo)
        val r = call("GET", "/repos/$repo/git/trees/${enc(branch)}?recursive=1")
        if (r.code == 409) return null
        if (r.code !in 200..299) throw ToolException("GitHub -> ${r.code}: ${r.body.take(500)}")
        val arr = JSONObject(r.body).getJSONArray("tree")
        val out = ArrayList<TreeEntry>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            if (e.getString("type") == "blob") out += TreeEntry(e.getString("path"), e.optLong("size"), e.optString("sha"))
        }
        return branch to out
    }

    suspend fun tree(repo: String, ref: String?, filter: (TreeEntry) -> Boolean = { true }): String {
        val (branch, all) = treeEntries(repo, ref) ?: return "(Repo boş, henüz commit yok)"
        val lines = all.filter(filter).map { "${it.path} (${it.size} B)" }
        val hidden = all.size - lines.size
        val limit = 1500
        val head = "Branch: $branch, ${lines.size} dosya" + (if (hidden > 0) " ($hidden dosya filtrelendi: build/bağımlılık/binary/.gitignore)" else "") + "\n"
        return head + lines.take(limit).joinToString("\n") + (if (lines.size > limit) "\n... (${lines.size - limit} dosya daha)" else "")
    }

    suspend fun readFileRaw(repo: String, path: String, ref: String?): String? {
        val q = ref?.takeIf { it.isNotBlank() }?.let { "?ref=${enc(it)}" } ?: ""
        val r = call("GET", "/repos/$repo/contents/${encPath(path)}$q", accept = "application/vnd.github.raw+json")
        if (r.code == 404) return null
        if (r.code !in 200..299) throw ToolException("GitHub -> ${r.code}: ${r.body.take(500)}")
        directoryListing(r.body)?.let { return it }
        return r.body
    }

    companion object {
        /**
         * A contents request for a folder returns a JSON array of entries. Files may also start with '['
         * (TOML sections like `[versions]`, JSON arrays), so only a real array of {type, path} objects counts.
         */
        fun directoryListing(body: String): String? {
            if (!body.trimStart().startsWith("[")) return null
            val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
            val entries = (0 until arr.length()).map { arr.opt(it) }
            if (entries.isEmpty() || entries.any { it !is JSONObject || !it.has("type") || !it.has("path") }) return null
            return "(Bu bir klasör)\n" + entries.joinToString("\n") { e -> (e as JSONObject).let { "${it.getString("type")}: ${it.getString("path")}" } }
        }
    }

    /**
     * Reads a file. Whole-file reads come without per-line numbers (they add ~9% tokens); ranged reads keep them,
     * since the model needs them to ask for the next range.
     */
    suspend fun readFile(repo: String, path: String, ref: String?, startLine: Int?, endLine: Int?): String {
        val text = readFileRaw(repo, path, ref) ?: throw ToolException("Dosya bulunamadı: $path")
        val lines = text.split("\n")
        if (startLine == null && endLine == null) {
            val limit = 80_000
            return "$path (${lines.size} satır)\n" + if (text.length <= limit) text
            else text.take(limit) + "\n... (kesildi; devamı için start_line kullan, toplam ${lines.size} satır)"
        }
        val from = (startLine ?: 1).coerceAtLeast(1)
        val to = (endLine ?: lines.size).coerceAtMost(lines.size)
        val sb = StringBuilder()
        for (i in from..to) {
            sb.append(i).append(": ").append(lines[i - 1]).append('\n')
            if (sb.length > 80_000) {
                sb.append("... (kesildi; satır ${i + 1}'den itibaren devam etmek için start_line kullan, toplam ${lines.size} satır)")
                break
            }
        }
        return sb.toString()
    }

    /** Creates a single commit with all changes on [branch] (created from [baseBranch]/default if missing). */
    suspend fun commitFiles(repo: String, branch: String, message: String, files: List<FileChange>, baseBranch: String?): String {
        if (files.isEmpty()) throw ToolException("Değiştirilecek dosya yok.")
        var refRes = call("GET", "/repos/$repo/git/ref/heads/${enc(branch)}")
        if (refRes.code == 409) return commitToEmptyRepo(repo, branch, message, files)
        if (refRes.code == 404) {
            val base = baseBranch?.takeIf { it.isNotBlank() } ?: defaultBranch(repo)
            val baseRef = call("GET", "/repos/$repo/git/ref/heads/${enc(base)}")
            if (baseRef.code == 409 || baseRef.code == 404) return commitToEmptyRepo(repo, branch, message, files)
            if (baseRef.code !in 200..299) throw ToolException("Base branch alınamadı: ${baseRef.body.take(300)}")
            val sha = JSONObject(baseRef.body).getJSONObject("object").getString("sha")
            json("POST", "/repos/$repo/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", sha))
            refRes = call("GET", "/repos/$repo/git/ref/heads/${enc(branch)}")
        }
        if (refRes.code !in 200..299) throw ToolException("Branch alınamadı: ${refRes.body.take(300)}")
        val headSha = JSONObject(refRes.body).getJSONObject("object").getString("sha")
        val baseTree = json("GET", "/repos/$repo/git/commits/$headSha").getJSONObject("tree").getString("sha")
        val entries = JSONArray()
        for (f in files) {
            val e = JSONObject().put("path", f.path.trim('/')).put("mode", "100644").put("type", "blob")
            if (f.delete) e.put("sha", JSONObject.NULL) else e.put("content", f.content ?: "")
            entries.put(e)
        }
        val newTree = json("POST", "/repos/$repo/git/trees", JSONObject().put("base_tree", baseTree).put("tree", entries)).getString("sha")
        val commit = json("POST", "/repos/$repo/git/commits",
            JSONObject().put("message", message).put("tree", newTree).put("parents", JSONArray().put(headSha)))
        val commitSha = commit.getString("sha")
        json("PATCH", "/repos/$repo/git/refs/heads/${enc(branch)}", JSONObject().put("sha", commitSha))
        return "Commit oluşturuldu: ${commitSha.take(7)} on $branch (${files.size} dosya)\nhttps://github.com/$repo/commit/$commitSha"
    }

    /** The Git Data API does not work on an empty repo, so the first files go through the contents API. */
    private suspend fun commitToEmptyRepo(repo: String, branch: String, message: String, files: List<FileChange>): String {
        val out = StringBuilder("Repo boştu; dosyalar tek tek eklendi:\n")
        for (f in files.filter { !it.delete }) {
            val b64 = Base64.encodeToString((f.content ?: "").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            json("PUT", "/repos/$repo/contents/${encPath(f.path)}",
                JSONObject().put("message", message).put("content", b64).put("branch", branch))
            out.append("+ ").append(f.path).append('\n')
        }
        return out.toString()
    }

    suspend fun createBranch(repo: String, branch: String, from: String?): String {
        val base = from?.takeIf { it.isNotBlank() } ?: defaultBranch(repo)
        val sha = json("GET", "/repos/$repo/git/ref/heads/${enc(base)}").getJSONObject("object").getString("sha")
        json("POST", "/repos/$repo/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", sha))
        return "Branch oluşturuldu: $branch ($base üzerinden, ${sha.take(7)})"
    }

    suspend fun createPullRequest(repo: String, title: String, head: String, base: String?, body: String?): String {
        val b = base?.takeIf { it.isNotBlank() } ?: defaultBranch(repo)
        val pr = json("POST", "/repos/$repo/pulls",
            JSONObject().put("title", title).put("head", head).put("base", b).put("body", body ?: ""))
        return "PR #${pr.getInt("number")} açıldı: ${pr.getString("html_url")}"
    }

    suspend fun mergePullRequest(repo: String, number: Int, method: String, title: String?): String {
        val body = JSONObject().put("merge_method", method)
        title?.let { body.put("commit_title", it) }
        val o = json("PUT", "/repos/$repo/pulls/$number/merge", body)
        return "PR #$number birleştirildi (${method}, ${o.optString("sha").take(7)}). https://github.com/$repo/pull/$number"
    }

    /** The newest workflow_dispatch run of [workflowFile] on [branch] created at or after [sinceMs], or null. */
    suspend fun dispatchedRun(repo: String, workflowFile: String, branch: String, sinceMs: Long): Run? {
        val r = call("GET", "/repos/$repo/actions/workflows/${enc(workflowFile)}/runs?event=workflow_dispatch&branch=${enc(branch)}&per_page=5")
        if (r.code !in 200..299) return null
        val arr = JSONObject(r.body).optJSONArray("workflow_runs") ?: return null
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { o -> runCatching { java.time.Instant.parse(o.optString("created_at")).toEpochMilli() }.getOrDefault(0L) >= sinceMs - 60_000 }
            .maxByOrNull { it.optString("created_at") }
            ?.let { o -> Run(o.getLong("id"), o.optString("name"), o.optString("status"),
                o.optString("conclusion").takeIf { !o.isNull("conclusion") && it.isNotBlank() }, o.optString("html_url")) }
    }

    suspend fun run(repo: String, runId: Long): Run {
        val o = json("GET", "/repos/$repo/actions/runs/$runId")
        return Run(o.getLong("id"), o.optString("name"), o.optString("status"),
            o.optString("conclusion").takeIf { !o.isNull("conclusion") && it.isNotBlank() }, o.optString("html_url"))
    }

    /** (artifact id, name) of a run. */
    suspend fun artifacts(repo: String, runId: Long): List<Pair<Long, String>> {
        val r = call("GET", "/repos/$repo/actions/runs/$runId/artifacts?per_page=20")
        if (r.code !in 200..299) return emptyList()
        val arr = JSONObject(r.body).optJSONArray("artifacts") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { it.getLong("id") to it.optString("name") }
    }

    data class PrInfo(val number: Int, val title: String, val head: String, val url: String)

    suspend fun openPullRequests(repo: String): List<PrInfo> {
        val r = call("GET", "/repos/$repo/pulls?state=open&per_page=50")
        if (r.code !in 200..299) throw ToolException("PR listesi alınamadı: HTTP ${r.code}")
        val arr = JSONArray(r.body)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PrInfo(o.getInt("number"), o.optString("title"), o.optJSONObject("head")?.optString("ref").orEmpty(), o.optString("html_url"))
        }
    }

    /** Merges; returns (HTTP code, message or merge sha). 405/409 mean "not mergeable / conflict". */
    suspend fun tryMerge(repo: String, number: Int, method: String): Pair<Int, String> {
        val r = call("PUT", "/repos/$repo/pulls/$number/merge", JSONObject().put("merge_method", method))
        val body = runCatching { JSONObject(r.body) }.getOrNull()
        return r.code to (if (r.code in 200..299) body?.optString("sha").orEmpty() else body?.optString("message") ?: r.body.take(300))
    }

    suspend fun closePullRequest(repo: String, number: Int) {
        json("PATCH", "/repos/$repo/pulls/$number", JSONObject().put("state", "closed"))
    }

    suspend fun deleteBranch(repo: String, branch: String): Boolean =
        call("DELETE", "/repos/$repo/git/refs/heads/${enc(branch)}").code in 200..299

    /** "path +add −del" lines of a pull request. */
    suspend fun pullRequestFiles(repo: String, number: Int): List<String> {
        val r = call("GET", "/repos/$repo/pulls/$number/files?per_page=50")
        if (r.code !in 200..299) return emptyList()
        val arr = JSONArray(r.body)
        return (0 until arr.length()).map { i -> arr.getJSONObject(i).let { "${it.optString("filename")} +${it.optInt("additions")} −${it.optInt("deletions")}" } }
    }

    /** Starts a workflow_dispatch run; [workflow] is the file name (e.g. android.yml) or id. */
    suspend fun dispatchWorkflow(repo: String, workflow: String, ref: String?, inputs: JSONObject?): String {
        val branch = ref?.takeIf { it.isNotBlank() } ?: defaultBranch(repo)
        val body = JSONObject().put("ref", branch)
        if (inputs != null && inputs.length() > 0) body.put("inputs", inputs)
        json("POST", "/repos/$repo/actions/workflows/${enc(workflow)}/dispatches", body)
        return "Workflow '$workflow' $branch üzerinde başlatıldı. Sonucu görmek için ci_wait(ref=\"$branch\") çağır."
    }

    // ---- GitHub Actions ----

    data class Run(val id: Long, val name: String, val status: String, val conclusion: String?, val url: String)

    suspend fun runsForSha(repo: String, sha: String): List<Run> {
        val o = json("GET", "/repos/$repo/actions/runs?head_sha=$sha&per_page=20")
        val arr = o.optJSONArray("workflow_runs") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            Run(r.getLong("id"), r.optString("name"), r.optString("status"),
                r.optString("conclusion").takeIf { !r.isNull("conclusion") && it.isNotBlank() }, r.optString("html_url"))
        }
    }

    data class Job(val id: Long, val name: String, val conclusion: String?)

    suspend fun jobs(repo: String, runId: Long): List<Job> {
        val arr = json("GET", "/repos/$repo/actions/runs/$runId/jobs?per_page=50").optJSONArray("jobs") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val j = arr.getJSONObject(i)
            Job(j.getLong("id"), j.optString("name"), j.optString("conclusion").takeIf { !j.isNull("conclusion") && it.isNotBlank() })
        }
    }

    /**
     * A downloadable APK built for [sha]: a release asset of a release targeting that commit (or [branch]),
     * else the first artifact of the given runs. Returns a link the chat/installer understands, or null.
     */
    suspend fun apkLinkFor(repo: String, sha: String, branch: String?, runIds: List<Long>): String? {
        val rel = call("GET", "/repos/$repo/releases?per_page=10")
        if (rel.code in 200..299) {
            val arr = JSONArray(rel.body)
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val target = r.optString("target_commitish")
                if (target != sha && (branch == null || target != branch)) continue
                val assets = r.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (a.optString("name").endsWith(".apk")) return a.optString("browser_download_url").ifBlank { null }
                }
            }
        }
        for (id in runIds) {
            val res = call("GET", "/repos/$repo/actions/runs/$id/artifacts?per_page=10")
            if (res.code !in 200..299) continue
            val arts = JSONObject(res.body).optJSONArray("artifacts") ?: continue
            if (arts.length() > 0) return "https://api.github.com/repos/$repo/actions/artifacts/${arts.getJSONObject(0).getLong("id")}"
        }
        return null
    }

    suspend fun jobLog(repo: String, jobId: Long): String {
        val r = call("GET", "/repos/$repo/actions/jobs/$jobId/logs")
        if (r.code !in 200..299) throw ToolException("Log alınamadı (HTTP ${r.code})")
        return r.body
    }

    suspend fun searchCode(query: String): String {
        val r = call("GET", "/search/code?per_page=30&q=${enc(query)}")
        if (r.code !in 200..299) throw ToolException("GitHub -> ${r.code}: ${r.body.take(500)}")
        val items = JSONObject(r.body).getJSONArray("items")
        return (0 until items.length()).joinToString("\n") {
            val o = items.getJSONObject(it)
            "${o.getJSONObject("repository").getString("full_name")}: ${o.getString("path")}"
        }.ifBlank { "(Sonuç yok)" }
    }

    suspend fun raw(method: String, path: String, body: String?): String {
        val parsedBody: Any? = body?.takeIf { it.isNotBlank() }?.let {
            runCatching { JSONObject(it) }.getOrElse { _ -> runCatching { JSONArray(it) }.getOrElse { _ -> throw ToolException("body geçerli JSON değil") } }
        }
        val r = call(method.uppercase(), path, parsedBody)
        val text = r.body
        return "HTTP ${r.code}\n" + if (text.length > 40_000) text.take(40_000) + "\n... (kesildi, ${text.length} karakter)" else text
    }
}
