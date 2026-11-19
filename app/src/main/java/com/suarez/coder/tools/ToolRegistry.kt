package com.suarez.coder.tools

import com.suarez.coder.context.FileFilter
import com.suarez.coder.data.Settings
import com.suarez.coder.diff.SearchReplace
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
private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)

class ToolRegistry(private val settings: Settings) {
    val github = GitHub { settings.githubToken }
    private val web = Web()
    private val gitignores = HashMap<String, FileFilter>()

    /** Called after any successful write so cached file contents can be dropped. */
    var onRepoChanged: () -> Unit = {}

    private val repoDesc = "Repo, 'sahip/isim' biçiminde"

    private val webSearch = ToolSpec("web_search", "İnternette arama yapar. Başlık, URL ve özet döndürür.",
        obj("query" to str("Arama sorgusu"), required = listOf("query")))
    private val webFetch = ToolSpec("web_fetch", "Bir web sayfasını okunabilir metin olarak getirir. Uzun sayfada 'start' ile devam et.",
        obj("url" to str("Tam URL"), "start" to int("Başlangıç karakteri"), required = listOf("url")))
    private val listRepos = ToolSpec("github_list_repos", "Kullanıcının GitHub repolarını listeler.", obj())
    private val getTree = ToolSpec("github_get_tree",
        "Repodaki dosya yollarını listeler (build, bağımlılık, binary ve .gitignore'daki dosyalar gizlenir).",
        obj("repo" to str(repoDesc), "ref" to str("Branch/tag/commit (boşsa varsayılan)"), required = listOf("repo")))
    private val readFile = ToolSpec("github_read_file",
        "Dosyayı satır numaralarıyla okur. Büyük dosyada start_line/end_line ile sadece gereken kısmı oku.",
        obj("repo" to str(repoDesc), "path" to str("Dosya yolu"), "ref" to str("Branch/tag/commit"),
            "start_line" to int("İlk satır"), "end_line" to int("Son satır"),
            "force" to bool("Filtrelenen (lock/binary) dosyayı yine de oku"), required = listOf("repo", "path")))
    private val applyDiff = ToolSpec("github_apply_diff",
        "Mevcut dosyalarda değişiklik yapmanın ana yolu. SEARCH/REPLACE bloklarını uygular ve hepsini TEK commit yapar. " +
            "Biçim: dosya yolu satırı, sonra '<<<<<<< SEARCH', birebir mevcut satırlar, '=======', yeni satırlar, '>>>>>>> REPLACE'. " +
            "SEARCH kısa ama benzersiz olsun. Yeni dosya için SEARCH boş bırakılır. Bir blok bile uymazsa hiçbir şey yazılmaz.",
        obj("repo" to str(repoDesc), "branch" to str("Hedef branch (yoksa oluşturulur)"), "message" to str("Commit mesajı"),
            "diff" to str("Bir veya daha çok SEARCH/REPLACE bloğu"), "base_branch" to str("Branch yoksa kaynağı"),
            required = listOf("repo", "branch", "message", "diff")))
    private val writeFiles = ToolSpec("github_write_files",
        "Yalnızca YENİ dosya oluşturmak, dosyayı tamamen baştan yazmak veya silmek için. Mevcut dosyada değişiklik için github_apply_diff kullan.",
        obj("repo" to str(repoDesc), "branch" to str("Hedef branch"), "message" to str("Commit mesajı"),
            "files" to JSONObject().put("type", "array").put("description", "Dosyalar")
                .put("items", obj("path" to str("Yol"), "content" to str("Tam içerik"), "delete" to bool("true ise sil"), required = listOf("path"))),
            "base_branch" to str("Branch yoksa kaynağı"), required = listOf("repo", "branch", "message", "files")))
    private val createBranch = ToolSpec("github_create_branch", "Yeni branch oluşturur.",
        obj("repo" to str(repoDesc), "branch" to str("Yeni branch"), "from" to str("Kaynak branch"), required = listOf("repo", "branch")))
    private val createPr = ToolSpec("github_create_pull_request", "Pull request açar.",
        obj("repo" to str(repoDesc), "title" to str("Başlık"), "head" to str("Kaynak branch"), "base" to str("Hedef branch"),
            "body" to str("Açıklama"), required = listOf("repo", "title", "head")))
    private val searchCode = ToolSpec("github_search_code", "GitHub'da kod arar (ör. 'fonksiyonAdi repo:sahip/isim').",
        obj("query" to str("Sorgu"), required = listOf("query")))
    private val githubApi = ToolSpec("github_api",
        "GitHub REST API'ye doğrudan istek: issue'lar, Actions çalıştırmaları ve logları (GET /repos/{repo}/actions/runs, " +
            "GET /repos/{repo}/actions/jobs/{id}/logs), release'ler, yeni repo (POST /user/repos) vb.",
        obj("method" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("GET", "POST", "PUT", "PATCH", "DELETE"))),
            "path" to str("API yolu"), "body" to str("JSON gövdesi (metin)"), required = listOf("method", "path")))

    /** Architect phase 1 finishes by calling this. */
    val submitPlan = ToolSpec("submit_plan",
        "Kod değişikliği planını uygulayıcıya teslim eder. Sadece mantıksal değişiklikleri yaz, kod yazma.",
        obj("repo" to str(repoDesc), "branch" to str("Değişikliklerin commit edileceği branch"),
            "base_branch" to str("Branch yoksa oluşturulacağı kaynak (isteğe bağlı)"),
            "commit_message" to str("Kısa commit mesajı"),
            "files" to JSONObject().put("type", "array").put("description", "Değişecek dosyalar")
                .put("items", obj("path" to str("Dosya yolu"),
                    "action" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("modify", "create", "delete"))),
                    "instructions" to str("Bu dosyada yapılacak değişiklikler, adım adım ve kısa"),
                    required = listOf("path", "action", "instructions"))),
            "notes" to str("Tüm dosyalar için ortak notlar (isteğe bağlı)"),
            required = listOf("repo", "branch", "commit_message", "files")))

    val specs: List<ToolSpec> = listOf(webSearch, webFetch, listRepos, getTree, readFile, applyDiff, writeFiles,
        createBranch, createPr, searchCode, githubApi)

    /** Tools the architect may use while planning (github_api is restricted to GET at run time). */
    val readOnlySpecs: List<ToolSpec> = listOf(webSearch, webFetch, listRepos, getTree, readFile, searchCode, githubApi, submitPlan)

    fun needsApproval(call: ToolCall): Boolean = when (call.name) {
        "github_write_files", "github_apply_diff", "github_create_branch", "github_create_pull_request" -> true
        "github_api" -> call.args.optString("method", "GET").uppercase() != "GET"
        else -> false
    }

    private suspend fun filterFor(repo: String, ref: String?): FileFilter {
        val key = "$repo@${ref ?: ""}"
        gitignores[key]?.let { return it }
        val gi = runCatching { github.readFileRaw(repo, ".gitignore", ref) }.getOrNull()
        return FileFilter(gi).also { gitignores[key] = it }
    }

    suspend fun filteredTree(repo: String, ref: String?): List<GitHub.TreeEntry> {
        val (_, entries) = github.treeEntries(repo, ref) ?: return emptyList()
        val f = filterFor(repo, ref)
        return entries.filter { !f.isExcluded(it.path, it.size) }
    }

    fun normalizeRepo(r: String) = r.trim().removePrefix("https://github.com/").trim('/').removeSuffix(".git")

    suspend fun execute(call: ToolCall, readOnly: Boolean = false): String {
        val a = call.args
        fun s(k: String) = a.optString(k).takeIf { a.has(k) && !a.isNull(k) && it.isNotBlank() }
        fun req(k: String) = s(k) ?: throw ToolException("'$k' parametresi gerekli")
        fun i(k: String) = if (a.has(k) && !a.isNull(k)) a.optInt(k) else null
        fun repo(): String {
            val r = s("repo") ?: settings.defaultRepo.takeIf { it.isNotBlank() } ?: throw ToolException("'repo' parametresi gerekli")
            return normalizeRepo(r)
        }
        if (readOnly && readOnlySpecs.none { it.name == call.name }) throw ToolException("Planlama aşamasında bu araç kullanılamaz: ${call.name}")
        if (readOnly && call.name == "github_api" && (s("method") ?: "GET").uppercase() != "GET") {
            throw ToolException("Planlama aşamasında yalnızca GET istekleri yapılabilir.")
        }

        val result = when (call.name) {
            "web_search" -> web.search(req("query"))
            "web_fetch" -> web.fetch(req("url"), i("start") ?: 0)
            "github_list_repos" -> github.listRepos()
            "github_get_tree" -> {
                val repo = repo(); val ref = s("ref")
                val f = filterFor(repo, ref)
                github.tree(repo, ref) { !f.isExcluded(it.path, it.size) }
            }
            "github_read_file" -> {
                val path = req("path")
                if (FileFilter.isBinaryOrLock(path) && !a.optBoolean("force")) {
                    throw ToolException("$path binary/lock dosyası olduğu için filtrelendi (token israfı). Gerçekten gerekiyorsa force=true ile oku.")
                }
                github.readFile(repo(), path, s("ref"), i("start_line"), i("end_line"))
            }
            "github_apply_diff" -> applyDiff(repo(), req("branch"), req("message"), req("diff"), s("base_branch"))
            "github_write_files" -> {
                val arr = a.optJSONArray("files") ?: throw ToolException("'files' dizisi gerekli")
                val files = (0 until arr.length()).map {
                    val f = arr.getJSONObject(it)
                    GitHub.FileChange(f.getString("path"), if (f.isNull("content")) null else f.optString("content"), f.optBoolean("delete"))
                }
                github.commitFiles(repo(), req("branch"), req("message"), files, s("base_branch"))
            }
            "github_create_branch" -> github.createBranch(repo(), req("branch"), s("from"))
            "github_create_pull_request" -> github.createPullRequest(repo(), req("title"), req("head"), s("base"), s("body"))
            "github_search_code" -> github.searchCode(req("query"))
            "github_api" -> github.raw(s("method") ?: "GET", req("path"), s("body"))
            else -> throw ToolException("Bilinmeyen araç: ${call.name}")
        }
        if (call.name in setOf("github_apply_diff", "github_write_files") ||
            (call.name == "github_api" && (s("method") ?: "GET").uppercase() != "GET")
        ) onRepoChanged()
        return result
    }

    /** Reads a file from the branch, falling back to the base branch and the default branch. */
    suspend fun readForEdit(repo: String, branch: String, base: String?, path: String): String? =
        github.readFileRaw(repo, path, branch)
            ?: base?.let { github.readFileRaw(repo, path, it) }
            ?: github.readFileRaw(repo, path, null)

    private suspend fun applyDiff(repo: String, branch: String, message: String, diff: String, base: String?): String {
        val parsed = SearchReplace.parse(diff)
        if (parsed.blocks.isEmpty()) {
            throw ToolException("Diff içinde geçerli SEARCH/REPLACE bloğu yok. " + parsed.errors.joinToString(" "))
        }
        val changes = ArrayList<GitHub.FileChange>()
        val failures = ArrayList<SearchReplace.Failure>()
        val summary = StringBuilder()
        for ((path, blocks) in parsed.blocks.groupBy { it.path }) {
            val original = readForEdit(repo, branch, base, path)
            val res = SearchReplace.applyToFile(original, blocks)
            failures += res.failures
            if (res.failures.isEmpty()) {
                changes += GitHub.FileChange(path, res.content, false)
                val (add, rem) = SearchReplace.lineDelta(original, res.content)
                summary.append("$path +$add −$rem\n")
            }
        }
        if (failures.isNotEmpty() || parsed.errors.isNotEmpty()) {
            throw ToolException(
                "Diff uygulanmadı, hiçbir dosya değiştirilmedi.\n" +
                    (if (parsed.errors.isNotEmpty()) "Biçim hataları: " + parsed.errors.joinToString(" ") + "\n" else "") +
                    SearchReplace.describeFailures(failures) +
                    "\nDosyayı yeniden okuyup yalnızca hatalı blokları düzeltilmiş olarak tekrar gönder."
            )
        }
        return github.commitFiles(repo, branch, message, changes, base) + "\n" + summary
    }
}
