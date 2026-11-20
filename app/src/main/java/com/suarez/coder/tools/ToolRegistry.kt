package com.suarez.coder.tools

import com.suarez.coder.ci.LogFilter
import com.suarez.coder.context.FileFilter
import com.suarez.coder.validate.CodeValidator
import kotlinx.coroutines.delay
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

    /** Whether a path is part of the frozen stable context (so its copy there is now outdated). */
    var isInSnapshot: (String) -> Boolean = { false }

    private val repoDesc = "Repo, 'sahip/isim' biçiminde"

    private val webSearch = ToolSpec("web_search", "İnternette arama yapar. Başlık, URL ve özet döndürür.",
        obj("query" to str("Arama sorgusu"), required = listOf("query")))
    private val webFetch = ToolSpec("web_fetch",
        "Bir web sayfasını okunabilir metin olarak getirir. 'query' verilirse yalnızca onunla ilgili bölümler döner (önerilir). Uzun sayfada 'start' ile devam et.",
        obj("url" to str("Tam URL"), "query" to str("Aradığın konu, birkaç anahtar kelime"), "start" to int("Başlangıç karakteri"), required = listOf("url")))
    private val listRepos = ToolSpec("github_list_repos", "Kullanıcının GitHub repolarını listeler.", obj())
    private val getTree = ToolSpec("github_get_tree",
        "Repodaki dosya yollarını listeler (build, bağımlılık, binary ve .gitignore'daki dosyalar gizlenir).",
        obj("repo" to str(repoDesc), "ref" to str("Branch/tag/commit (boşsa varsayılan)"), required = listOf("repo")))
    private val readFile = ToolSpec("github_read_file",
        "Tek dosya okur. Tam okumada satır numarası yoktur; start_line/end_line verilirse o aralık satır numaralarıyla gelir.",
        obj("repo" to str(repoDesc), "path" to str("Dosya yolu"), "ref" to str("Branch/tag/commit"),
            "start_line" to int("İlk satır"), "end_line" to int("Son satır"),
            "force" to bool("Filtrelenen (lock/binary) dosyayı yine de oku"), required = listOf("repo", "path")))
    private val readFiles = ToolSpec("github_read_files",
        "Birden fazla dosyayı TEK çağrıda okur (adım ve token tasarrufu). Gereken tüm dosyaları birlikte iste.",
        obj("repo" to str(repoDesc), "paths" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
            .put("description", "Dosya yolları"), "ref" to str("Branch/tag/commit"), required = listOf("repo", "paths")))
    private val ciWait = ToolSpec("ci_wait",
        "Bir commit/branch için GitHub Actions bitene kadar bekler (en fazla timeout_minutes) ve sonucu döndürür; başarısızsa " +
            "hata satırlarını da ekler. CI'ı github_api ile tekrar tekrar sorgulamak yerine bunu bir kez çağır.",
        obj("repo" to str(repoDesc), "ref" to str("Branch adı veya commit sha (boşsa varsayılan branch)"),
            "timeout_minutes" to int("En fazla bekleme (varsayılan 15)"), required = listOf("repo")))
    private val ciErrors = ToolSpec("ci_errors",
        "Bir Actions çalıştırmasının başarısız işlerinin logundan yalnızca hata satırlarını (bağlamıyla) getirir.",
        obj("repo" to str(repoDesc), "run_id" to int("Çalıştırma kimliği"), required = listOf("repo", "run_id")))
    private val applyDiff = ToolSpec("github_apply_diff",
        "Mevcut dosyalarda değişiklik yapmanın ana yolu. SEARCH/REPLACE bloklarını uygular ve hepsini TEK commit yapar. " +
            "Biçim: dosya yolu satırı, sonra '<<<<<<< SEARCH', birebir mevcut satırlar, '=======', yeni satırlar, '>>>>>>> REPLACE'. " +
            "SEARCH kısa ama benzersiz olsun; uzun bölgede ilk satırlar, yalnızca `...` olan bir satır, son satırlar yazılabilir. " +
            "Yeni dosya için SEARCH boş bırakılır. Bir blok bile uymazsa veya yerel kontrol (parantez/JSON/XML) bozulursa hiçbir şey yazılmaz.",
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

    val specs: List<ToolSpec> = listOf(webSearch, webFetch, listRepos, getTree, readFile, readFiles, applyDiff, writeFiles,
        createBranch, createPr, searchCode, ciWait, ciErrors, githubApi)

    /** Tools the architect may use while planning (github_api is restricted to GET at run time). */
    val readOnlySpecs: List<ToolSpec> = listOf(webSearch, webFetch, listRepos, getTree, readFile, readFiles, searchCode, ciErrors, githubApi, submitPlan)

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
            "web_fetch" -> web.fetch(req("url"), i("start") ?: 0, query = s("query"))
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
            "github_read_files" -> readMany(repo(), a.optJSONArray("paths") ?: throw ToolException("'paths' dizisi gerekli"), s("ref"))
            "ci_wait" -> ciWait(repo(), s("ref"), (i("timeout_minutes") ?: 15).coerceIn(1, 30))
            "ci_errors" -> ciErrors(repo(), a.optLong("run_id").takeIf { it > 0 } ?: throw ToolException("'run_id' gerekli"))
            "github_apply_diff" -> applyDiff(repo(), req("branch"), req("message"), req("diff"), s("base_branch"))
            "github_write_files" -> {
                val arr = a.optJSONArray("files") ?: throw ToolException("'files' dizisi gerekli")
                val files = (0 until arr.length()).map {
                    val f = arr.getJSONObject(it)
                    GitHub.FileChange(f.getString("path"), if (f.isNull("content")) null else f.optString("content"), f.optBoolean("delete"))
                }
                val repo = repo(); val branch = req("branch"); val base = s("base_branch")
                val problems = files.filter { !it.delete && it.content != null }.mapNotNull { f ->
                    val before = runCatching { readForEdit(repo, branch, base, f.path) }.getOrNull()
                    CodeValidator.validate(f.path, before, f.content!!)?.let { "${f.path}: $it" }
                }
                if (problems.isNotEmpty()) throw ToolException("Yerel kontrol başarısız, hiçbir şey yazılmadı:\n" + problems.joinToString("\n"))
                github.commitFiles(repo, branch, req("message"), files, base) + staleNote(files.map { it.path })
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
        val originals = HashMap<String, String?>()
        val failures = ArrayList<SearchReplace.Failure>()
        val summary = StringBuilder()
        for ((path, blocks) in parsed.blocks.groupBy { it.path }) {
            val original = readForEdit(repo, branch, base, path)
            originals[path] = original
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
        val problems = changes.mapNotNull { c -> CodeValidator.validate(c.path, originals[c.path], c.content ?: return@mapNotNull null)?.let { "${c.path}: $it" } }
        if (problems.isNotEmpty()) {
            throw ToolException("Diff uygulandı ama yerel kontrol başarısız, hiçbir şey yazılmadı:\n" + problems.joinToString("\n") +
                "\nDüzeltilmiş blokları tekrar gönder.")
        }
        return github.commitFiles(repo, branch, message, changes, base) + "\n" + summary + staleNote(changes.map { it.path })
    }

    private fun staleNote(paths: List<String>): String {
        val stale = paths.filter { isInSnapshot(it) }
        return if (stale.isEmpty()) "" else "\nNot: proje bağlamındaki ${stale.joinToString()} kopyası artık eski; güncel hali bu değişiklikle oluştu."
    }

    private suspend fun readMany(repo: String, paths: JSONArray, ref: String?): String {
        val sb = StringBuilder()
        val limit = 120_000
        for (i in 0 until paths.length()) {
            val p = paths.getString(i)
            if (FileFilter.isBinaryOrLock(p)) { sb.append("### $p\n(binary/lock dosyası, atlandı)\n\n"); continue }
            val text = github.readFileRaw(repo, p, ref)
            if (text == null) { sb.append("### $p\n(bulunamadı)\n\n"); continue }
            if (sb.length + text.length > limit) { sb.append("### $p\n(toplam sınır aşıldı; ayrıca oku)\n\n"); continue }
            sb.append("### $p (${text.count { it == '\n' } + 1} satır)\n").append(text).append("\n\n")
        }
        return sb.toString()
    }

    private suspend fun ciErrors(repo: String, runId: Long): String {
        val failed = github.jobs(repo, runId).filter { it.conclusion != null && it.conclusion != "success" && it.conclusion != "skipped" }
        if (failed.isEmpty()) return "Bu çalıştırmada başarısız iş yok."
        val sb = StringBuilder()
        for (j in failed) {
            val log = try { LogFilter.extract(github.jobLog(repo, j.id)) } catch (e: Exception) { "(log alınamadı: ${e.message})" }
            sb.append("## ${j.name} (${j.conclusion})\n").append(log).append("\n\n")
        }
        return sb.toString()
    }

    /** Waits inside the app (no model steps) until every workflow run for the commit has finished. */
    private suspend fun ciWait(repo: String, ref: String?, timeoutMinutes: Int): String {
        val sha = if (ref != null && Regex("^[0-9a-f]{40}$").matches(ref)) ref else github.headSha(repo, ref)
        val deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L
        var runs: List<GitHub.Run> = emptyList()
        var waitedForStart = 0
        while (System.currentTimeMillis() < deadline) {
            runs = github.runsForSha(repo, sha)
            if (runs.isEmpty()) {
                if (++waitedForStart > 8) return "Commit ${sha.take(7)} için başlayan bir workflow yok (2 dk beklendi)."
            } else if (runs.all { it.status == "completed" }) break
            delay(15_000)
        }
        if (runs.isEmpty() || runs.any { it.status != "completed" }) {
            return "Zaman aşımı ($timeoutMinutes dk): " + runs.joinToString { "${it.name}: ${it.status}" } + ". Daha sonra tekrar ci_wait çağır."
        }
        val sb = StringBuilder("Commit ${sha.take(7)}:\n")
        runs.forEach { sb.append("- ${it.name}: ${it.conclusion} (${it.url})\n") }
        runs.filter { it.conclusion != "success" && it.conclusion != "skipped" }.forEach { r ->
            sb.append("\nHatalar (${r.name}, run_id ${r.id}):\n").append(ciErrors(repo, r.id))
        }
        return sb.toString()
    }
}
