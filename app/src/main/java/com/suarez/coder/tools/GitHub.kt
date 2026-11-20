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
        if (r.body.trimStart().startsWith("[")) {
            // It's a directory listing.
            val arr = JSONArray(r.body)
            return "(Bu bir klasör)\n" + (0 until arr.length()).joinToString("\n") {
                val e = arr.getJSONObject(it); "${e.getString("type")}: ${e.getString("path")}"
            }
        }
        return r.body
    }

    suspend fun readFile(repo: String, path: String, ref: String?, startLine: Int?, endLine: Int?): String {
        val text = readFileRaw(repo, path, ref) ?: throw ToolException("Dosya bulunamadı: $path")
        val lines = text.split("\n")
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
