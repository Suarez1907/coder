package com.suarez.coder.tools

import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.llm.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
    val p = JSONObject()
    props.forEach { (k, v) -> p.put(k, v) }
    val o = JSONObject().put("type", "object").put("properties", p)
    if (required.isNotEmpty()) o.put("required", JSONArray(required))
    return o
}

private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)

class ToolRegistry(private val settings: Settings) {
    private val github = GitHub { settings.githubToken }
    private val web = Web()

    private val repoDesc = "Repo, 'sahip/isim' biçiminde (ör. kullanici/proje)"

    val specs: List<ToolSpec> = listOf(
        ToolSpec("web_search", "İnternette arama yapar (DuckDuckGo). Başlık, URL ve özet döndürür.",
            obj("query" to str("Arama sorgusu"), required = listOf("query"))),
        ToolSpec("web_fetch", "Bir web sayfasını veya dosyayı indirir ve okunabilir metin olarak döndürür. Uzun sayfalarda 'start' ile devam edilir.",
            obj("url" to str("Tam URL"), "start" to int("Başlangıç karakteri (varsayılan 0)"), required = listOf("url"))),
        ToolSpec("github_list_repos", "Kullanıcının erişebildiği GitHub repolarını listeler.", obj()),
        ToolSpec("github_get_tree", "Bir repodaki tüm dosyaların yollarını listeler.",
            obj("repo" to str(repoDesc), "ref" to str("Branch/tag/commit (boşsa varsayılan branch)"), required = listOf("repo"))),
        ToolSpec("github_read_file", "Repodan bir dosyayı satır numaralarıyla okur. Klasör yolu verilirse içeriğini listeler.",
            obj("repo" to str(repoDesc), "path" to str("Dosya yolu"), "ref" to str("Branch/tag/commit (isteğe bağlı)"),
                "start_line" to int("İlk satır (isteğe bağlı)"), "end_line" to int("Son satır (isteğe bağlı)"),
                required = listOf("repo", "path"))),
        ToolSpec("github_write_files",
            "Bir veya daha fazla dosyayı TEK bir commit ile yazar/siler. 'content' dosyanın TAM yeni içeriğidir. " +
                "Branch yoksa base_branch'tan (veya varsayılan branch'tan) oluşturulur.",
            obj("repo" to str(repoDesc), "branch" to str("Hedef branch"), "message" to str("Commit mesajı"),
                "files" to JSONObject().put("type", "array").put("description", "Değişen dosyalar")
                    .put("items", obj("path" to str("Dosya yolu"), "content" to str("Dosyanın tam içeriği (silmede boş bırak)"),
                        "delete" to JSONObject().put("type", "boolean").put("description", "true ise dosya silinir"),
                        required = listOf("path"))),
                "base_branch" to str("Branch yoksa oluşturulacağı kaynak branch (isteğe bağlı)"),
                required = listOf("repo", "branch", "message", "files"))),
        ToolSpec("github_edit_file",
            "Bir dosyada tam metin değiştirme yapar ve commit eder. old_text dosyada TAM OLARAK bir kez geçmelidir. Büyük dosyalarda küçük değişiklikler için tercih et.",
            obj("repo" to str(repoDesc), "branch" to str("Hedef branch"), "path" to str("Dosya yolu"),
                "old_text" to str("Değiştirilecek mevcut metin (birebir)"), "new_text" to str("Yeni metin"),
                "message" to str("Commit mesajı"), "base_branch" to str("Branch yoksa kaynak branch (isteğe bağlı)"),
                required = listOf("repo", "branch", "path", "old_text", "new_text", "message"))),
        ToolSpec("github_create_branch", "Yeni bir branch oluşturur.",
            obj("repo" to str(repoDesc), "branch" to str("Yeni branch adı"), "from" to str("Kaynak branch (boşsa varsayılan)"),
                required = listOf("repo", "branch"))),
        ToolSpec("github_create_pull_request", "Pull request açar.",
            obj("repo" to str(repoDesc), "title" to str("Başlık"), "head" to str("Değişikliklerin olduğu branch"),
                "base" to str("Hedef branch (boşsa varsayılan)"), "body" to str("Açıklama"),
                required = listOf("repo", "title", "head"))),
        ToolSpec("github_search_code", "GitHub'da kod arar (GitHub arama sözdizimi, ör. 'fonksiyonAdi repo:sahip/isim').",
            obj("query" to str("Arama sorgusu"), required = listOf("query"))),
        ToolSpec("github_api",
            "GitHub REST API'sine doğrudan istek atar. Diğer araçların kapsamadığı her şey için: issue'lar, Actions çalıştırmaları ve logları " +
                "(GET /repos/{repo}/actions/runs, GET /repos/{repo}/actions/jobs/{id}/logs), release'ler, yeni repo (POST /user/repos) vb.",
            obj("method" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("GET", "POST", "PUT", "PATCH", "DELETE")))
                .put("description", "HTTP metodu"),
                "path" to str("API yolu, ör. /repos/sahip/isim/issues"),
                "body" to str("JSON gövdesi, metin olarak (isteğe bağlı)"),
                required = listOf("method", "path"))),
    )

    /** Tools that change something on GitHub ask the user first (unless auto-approve is on). */
    fun needsApproval(call: ToolCall): Boolean = when (call.name) {
        "github_write_files", "github_edit_file", "github_create_branch", "github_create_pull_request" -> true
        "github_api" -> call.args.optString("method", "GET").uppercase() != "GET"
        else -> false
    }

    suspend fun execute(call: ToolCall): String {
        val a = call.args
        fun s(k: String) = a.optString(k).takeIf { a.has(k) && !a.isNull(k) && it.isNotBlank() }
        fun req(k: String) = s(k) ?: throw ToolException("'$k' parametresi gerekli")
        fun i(k: String) = if (a.has(k) && !a.isNull(k)) a.optInt(k) else null
        fun repo(): String {
            val r = s("repo") ?: settings.defaultRepo.takeIf { it.isNotBlank() } ?: throw ToolException("'repo' parametresi gerekli")
            return r.removePrefix("https://github.com/").trim('/').removeSuffix(".git")
        }

        return when (call.name) {
            "web_search" -> web.search(req("query"))
            "web_fetch" -> web.fetch(req("url"), i("start") ?: 0)
            "github_list_repos" -> github.listRepos()
            "github_get_tree" -> github.tree(repo(), s("ref"))
            "github_read_file" -> github.readFile(repo(), req("path"), s("ref"), i("start_line"), i("end_line"))
            "github_write_files" -> {
                val arr = a.optJSONArray("files") ?: throw ToolException("'files' dizisi gerekli")
                val files = (0 until arr.length()).map {
                    val f = arr.getJSONObject(it)
                    GitHub.FileChange(f.getString("path"), if (f.isNull("content")) null else f.optString("content"), f.optBoolean("delete"))
                }
                github.commitFiles(repo(), req("branch"), req("message"), files, s("base_branch"))
            }
            "github_edit_file" -> {
                val repo = repo()
                val branch = req("branch")
                val path = req("path")
                val old = a.optString("old_text")
                if (old.isEmpty()) throw ToolException("old_text boş olamaz")
                val current = github.readFileRaw(repo, path, branch)
                    ?: s("base_branch")?.let { github.readFileRaw(repo, path, it) }
                    ?: github.readFileRaw(repo, path, null)
                    ?: throw ToolException("Dosya bulunamadı: $path")
                var count = 0
                var idx = current.indexOf(old)
                while (idx >= 0) { count++; idx = current.indexOf(old, idx + 1) }
                if (count == 0) throw ToolException("old_text dosyada bulunamadı. Dosyayı tekrar oku ve birebir kopyala.")
                if (count > 1) throw ToolException("old_text dosyada $count kez geçiyor; daha fazla bağlam ekleyerek benzersiz yap.")
                val updated = current.replace(old, a.optString("new_text"))
                github.commitFiles(repo, branch, req("message"), listOf(GitHub.FileChange(path, updated, false)), s("base_branch"))
            }
            "github_create_branch" -> github.createBranch(repo(), req("branch"), s("from"))
            "github_create_pull_request" -> github.createPullRequest(repo(), req("title"), req("head"), s("base"), s("body"))
            "github_search_code" -> github.searchCode(req("query"))
            "github_api" -> github.raw(s("method") ?: "GET", req("path"), s("body"))
            else -> throw ToolException("Bilinmeyen araç: ${call.name}")
        }
    }
}
