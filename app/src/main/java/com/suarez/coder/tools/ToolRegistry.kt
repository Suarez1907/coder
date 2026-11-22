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

    /** Live CI watching (the app shows a foreground notification while ci_wait runs). */
    interface CiListener {
        fun started(repo: String, label: String)
        fun finished(repo: String, success: Boolean, summary: String, apkUrl: String?)
    }

    var ciListener: CiListener? = null

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

    private val mergePr = ToolSpec("github_merge_pull_request", "Bir pull request'i birleştirir (merge).",
        obj("repo" to str(repoDesc), "number" to int("PR numarası"),
            "method" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("merge", "squash", "rebase"))).put("description", "Birleştirme yöntemi (varsayılan merge)"),
            "commit_title" to str("Birleştirme commit başlığı (isteğe bağlı)"), required = listOf("repo", "number")))
    private val runWorkflow = ToolSpec("github_run_workflow",
        "Bir GitHub Actions workflow'unu elle başlatır (workflow_dispatch). Sonucu ci_wait ile izle.",
        obj("repo" to str(repoDesc), "workflow" to str("Workflow dosya adı (ör. android.yml) veya kimliği"),
            "ref" to str("Branch (boşsa varsayılan)"), "inputs_json" to str("Workflow girdileri, JSON nesnesi olarak (ör. {\"release_tag\":\"v1.3.0\"})"),
            required = listOf("repo", "workflow")))

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
        createBranch, createPr, mergePr, runWorkflow, searchCode, ciWait, ciErrors, githubApi)

    /** Tools the architect may use while planning (github_api is restricted to GET at run time). */
    val readOnlySpecs: List<ToolSpec> = listOf(webSearch, webFetch, listRepos, getTree, readFile, readFiles, searchCode, ciErrors, githubApi, submitPlan)

    private val needMoreInfo = ToolSpec("need_more_info",
        "Plan için harita ve bağlam yetmiyorsa çağır: istediğin dosyalar (en fazla 3) verilir ve okuma/GitHub araçların açılır.",
        obj("reason" to str("Neden daha fazla bilgi gerekiyor (kısa)"),
            "paths" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Okunacak dosya yolları (en fazla 3)"),
            required = listOf("reason")))

    /** The architect hands small changes and repository operations (PRs, merges, workflows) to the executor. */
    val delegateToExecutor = ToolSpec("delegate_to_executor",
        "İşi planlamadan doğrudan Uygulayıcı'ya verir: tek dosyalık düzeltmeler, küçük değişiklikler ve GitHub işlemleri " +
            "(branch, PR, merge, workflow) için. Uygulayıcı tüm araçlara sahiptir.",
        obj("instructions" to str("Uygulayıcı'ya kısa ve net talimat: ne yapılacak, hangi dosya/PR"), required = listOf("instructions")))

    /**
     * Everything the architect ever sees, in one fixed list (same tools on every call keep the prompt prefix cacheable):
     * plan, ask for up to 3 files, or delegate. No read loop, no write, no repository operation.
     */
    val singleShotSpecs: List<ToolSpec> = listOf(submitPlan, needMoreInfo, delegateToExecutor)

    companion object {
        /** Planning roles: read-only, whatever a caller passes (enforced in [execute], not only in the prompt). */
        val ARCHITECT_ROLES = setOf("architect", "planner")

        /** Tools that change files, run things or change the repository. */
        val WRITE_TOOLS = setOf("github_write_files", "github_apply_diff", "github_create_branch", "github_create_pull_request",
            "github_merge_pull_request", "github_run_workflow", "ci_wait")

        /** Hard guard for planning roles: only read tools, and github_api only with GET. */
        fun architectDenied(name: String, method: String?): String? {
            if (name in WRITE_TOOLS || name !in READ_TOOLS) return "Mimar salt okunurdur: '$name' kullanılamaz. Kod ve GitHub işlemleri Uygulayıcı'nındır."
            if (name == "github_api" && (method ?: "GET").uppercase() != "GET") return "Mimar salt okunurdur: yalnızca GET istekleri."
            return null
        }

        val OPS_TOOLS = setOf("github_create_branch", "github_create_pull_request", "github_merge_pull_request", "github_run_workflow", "ci_wait", "github_api")
        private val READ_TOOLS = setOf("web_search", "web_fetch", "github_list_repos", "github_get_tree", "github_read_file",
            "github_read_files", "github_search_code", "ci_errors", "github_api", "submit_plan")

        /**
         * Access rule. Full access: everything. readOnly: read tools and GET requests only. readOnly + allowOps (architect):
         * read tools plus repository operations, but never direct code edits (those go through the plan and the editor).
         * Returns an error message, or null when allowed.
         */
        fun accessError(name: String, method: String?, readOnly: Boolean, allowOps: Boolean): String? {
            if (!readOnly) return null
            if (allowOps && name in OPS_TOOLS) return null
            if (name !in READ_TOOLS) return if (allowOps) "Mimar kodu doğrudan değiştiremez: kod değişikliklerini submit_plan ile Uygulayıcı'ya ver ($name)."
                else "Bu modda bu araç kullanılamaz: $name"
            if (name == "github_api" && (method ?: "GET").uppercase() != "GET") return "Bu modda yalnızca GET istekleri yapılabilir."
            return null
        }
    }

    fun needsApproval(call: ToolCall): Boolean = when (call.name) {
        "github_write_files", "github_apply_diff", "github_create_branch", "github_create_pull_request",
        "github_merge_pull_request", "github_run_workflow" -> true
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

    suspend fun execute(call: ToolCall, readOnly: Boolean = false, allowOps: Boolean = false, role: String? = null): String {
        val a = call.args
        fun s(k: String) = a.optString(k).takeIf { a.has(k) && !a.isNull(k) && it.isNotBlank() }
        fun req(k: String) = s(k) ?: throw ToolException("'$k' parametresi gerekli")
        fun i(k: String) = if (a.has(k) && !a.isNull(k)) a.optInt(k) else null
        fun repo(): String {
            val r = s("repo") ?: settings.defaultRepo.takeIf { it.isNotBlank() } ?: throw ToolException("'repo' parametresi gerekli")
            return normalizeRepo(r)
        }
        if (role in ARCHITECT_ROLES) architectDenied(call.name, s("method"))?.let { throw ToolException(it) }
        accessError(call.name, s("method"), readOnly, allowOps)?.let { throw ToolException(it) }

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
            "github_read_files" -> readMany(repo(), Args.stringList(a, "paths")?.takeIf { it.isNotEmpty() }
                ?: throw ToolException("'paths' dosya yolu dizisi olmalı, ör. {\"paths\": [\"app/src/A.kt\", \"build.gradle.kts\"]}"), s("ref"))
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
            "github_merge_pull_request" -> github.mergePullRequest(repo(), i("number") ?: throw ToolException("'number' gerekli"),
                (s("method") ?: "merge").lowercase(), s("commit_title"))
            "github_run_workflow" -> github.dispatchWorkflow(repo(), req("workflow"), s("ref"),
                s("inputs_json")?.let { runCatching { JSONObject(it) }.getOrElse { throw ToolException("inputs_json geçerli bir JSON nesnesi değil") } })
            "github_search_code" -> github.searchCode(req("query"))
            "github_api" -> github.raw(s("method") ?: "GET", req("path"), s("body"))
            else -> throw ToolException("Bilinmeyen araç: ${call.name}")
        }
        if (call.name in setOf("github_apply_diff", "github_write_files", "github_merge_pull_request") ||
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

    suspend fun readFilesText(repo: String, paths: List<String>, ref: String?): String = readMany(repo, paths, ref)

    private suspend fun readMany(repo: String, paths: List<String>, ref: String?): String {
        val sb = StringBuilder()
        val limit = 120_000
        for (p in paths) {
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
        val isSha = ref != null && Regex("^[0-9a-f]{40}$").matches(ref)
        val sha = if (isSha) ref!! else github.headSha(repo, ref)
        val branch = if (isSha) null else ref
        val listener = ciListener
        listener?.started(repo, "commit ${sha.take(7)}")
        var success = false
        var summary = ""
        var apk: String? = null
        try {
            val deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L
            var runs: List<GitHub.Run> = emptyList()
            var waitedForStart = 0
            var announced = ""
            while (System.currentTimeMillis() < deadline) {
                runs = github.runsForSha(repo, sha)
                val names = runs.joinToString(", ") { it.name }
                if (names.isNotBlank() && names != announced) { announced = names; listener?.started(repo, names) }
                if (runs.isEmpty()) {
                    if (++waitedForStart > 8) {
                        summary = "Commit ${sha.take(7)} için başlayan bir workflow yok (2 dk beklendi)."
                        return summary
                    }
                } else if (runs.all { it.status == "completed" }) break
                delay(15_000)
            }
            if (runs.isEmpty() || runs.any { it.status != "completed" }) {
                summary = "Zaman aşımı ($timeoutMinutes dk): " + runs.joinToString { "${it.name}: ${it.status}" }
                return "$summary. Daha sonra tekrar ci_wait çağır."
            }
            val sb = StringBuilder("Commit ${sha.take(7)}:\n")
            runs.forEach { sb.append("- ${it.name}: ${it.conclusion} (${it.url})\n") }
            val failed = runs.filter { it.conclusion != "success" && it.conclusion != "skipped" }
            success = failed.isEmpty()
            if (success) {
                apk = runCatching { github.apkLinkFor(repo, sha, branch, runs.map { it.id }) }.getOrNull()
                apk?.let { sb.append("\nAPK: ").append(it).append('\n') }
                summary = runs.joinToString { "${it.name}: ${it.conclusion}" }
            } else {
                failed.forEach { r ->
                    val errors = ciErrors(repo, r.id)
                    if (summary.isBlank()) summary = "${r.name}: " + (errors.lines().firstOrNull { l -> l.isNotBlank() && !l.startsWith("##") && !l.startsWith("…") } ?: "başarısız")
                    sb.append("\nHatalar (${r.name}, run_id ${r.id}):\n").append(errors)
                }
            }
            return sb.toString()
        } finally {
            listener?.finished(repo, success, summary.ifBlank { "CI izleme durdu" }, apk)
        }
    }
}
