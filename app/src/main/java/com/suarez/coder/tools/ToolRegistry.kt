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

    /** Auto-Ship: yeşil build'in yayınlamadığı sürüme dair uyarı (CiController bir kez Notice ekler). */
    private var shipMissing: String? = null

    fun takeShipNotice(): com.suarez.coder.llm.ChatMessage.Notice? {
        val text = shipMissing ?: return null
        shipMissing = null
        return com.suarez.coder.llm.ChatMessage.Notice(text)
    }

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
    private val releases = ToolSpec("github_releases",
        "Reponun son sürümlerini (release) ve içindeki dosyaları (APK vb. indirme bağlantılarıyla) listeler. " +
            "Kullanıcıya 'en son sürüm nerede' türü bir yanıt vermeden önce çağır; sürüm adı/bağlantısı tahmin etme.",
        obj("repo" to str(repoDesc), "limit" to int("Kaç sürüm (varsayılan 5, en çok 20)"), required = listOf("repo")))
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

    /** Full instructions of a skill from the catalog in the system prompt (progressive disclosure). */
    private val loadSkill = ToolSpec("load_skill",
        "Sistem talimatındaki SKILLS listesinden bir yeteneğin tam talimatlarını yükler. Böyle bir işe başlamadan önce çağır.",
        obj("name" to str("Yetenek adı, ör. blender-3d"),
            "file" to str("İsteğe bağlı: yeteneğin yanında gelen bir dosya (ör. references/api.md)"), required = listOf("name")))

    /** Runs a script in GitHub Actions (Blender headless or Python/FFmpeg); no local computer is involved. */
    private val runCloudTool = ToolSpec("run_cloud_tool",
        "Repodaki bir betiği GitHub Actions'ta çalıştırır: tool=blender (Blender --background --python), toolbox " +
            "(Python 3.12 + FFmpeg), manim (toolbox + Manim/LaTeX; betik manim'i kendisi çağırır), video (Remotion: " +
            "script_path giriş dosyası, ör. src/index.ts; video.mp4 + preview.png üretir) veya android (debug APK'yı emülatörde açar, " +
            "ilk ekranın ve .coder/screens.txt'deki her adb shell komutundan sonraki ekranın görüntüsünü alır, çökmede başarısız olur; 10-20 dk). Workflow yoksa kurar, bitmesini bekler. " +
            "Hata olursa traceback döner: betiği düzeltip tekrar çağır. Başarılıysa çıktılar (preview.png, .glb, .mp4) indirilir ve " +
            "sohbette gösterilir. Önce betiği commit et.",
        obj("repo" to str(repoDesc), "tool" to str("blender, toolbox, manim, video veya android"), "script_path" to str("Betiğin (video: giriş dosyasının) repo içi yolu"),
            "output_dir" to str("Çıktı klasörü (varsayılan coder-out; betik CODER_OUTPUT_DIR ortam değişkenini kullanmalı)"),
            "branch" to str("Betiğin bulunduğu dal (boş = varsayılan dal)"),
            "blender_version" to str("Blender major.minor (varsayılan 4.2)"),
            "app_dir" to str("video: package.json'ın olduğu klasör (varsayılan .)"),
            "composition" to str("video: Remotion kompozisyon kimliği (boş = ilk)"),
            "requirements" to str("toolbox/manim: pip gereksinim dosyası (isteğe bağlı)"),
            required = listOf("tool", "script_path")))

    private val lookupDocs = ToolSpec("lookup_docs",
        "Bir kütüphanenin GÜNCEL belgesini getirir (Context7, yoksa resmi site). Emin olmadığın bir API'yi, yeni bir sürümü veya " +
            "hata veren bir çağrıyı kullanmadan ÖNCE çağır; projedeki sürümü (DEPENDENCIES satırı) version olarak ver.",
        obj("library" to str("Kütüphane adı, ör. 'jetpack compose', 'remotion', 'godot', 'react'"),
            "topic" to str("Aranan konu, ör. 'LazyColumn sticky header', 'interpolate easing'"),
            "version" to str("Projedeki sürüm, ör. 1.7.5 (isteğe bağlı)"), required = listOf("library", "topic")))
    private val findReferences = ToolSpec("find_references",
        "Bir sembolün (fonksiyon, sınıf, alan) TANIMINI ve onu KULLANAN tüm satırları dosya:satır olarak bulur. Bir imzayı, adı veya " +
            "davranışı değiştirmeden ÖNCE çağır ve etkilenen her çağıranı da güncelle.",
        obj("repo" to str(repoDesc), "symbol" to str("Tam ad, ör. saveScore veya ScoreRepository"), "ref" to str("Branch (boşsa varsayılan)"),
            required = listOf("symbol")))
    private val readSymbol = ToolSpec("read_symbol",
        "Bir fonksiyonu/sınıfı/metodu tüm dosya yerine yalnızca kendi kodu (belge yorumu dahil, satır numaralı) olarak okur. Dosyanın " +
            "tamamı gerekmiyorsa github_read_file yerine bunu kullan: çok daha az token. path verilmezse kod dizininden bulunur.",
        obj("repo" to str(repoDesc), "symbol" to str("Ad, ör. saveScore"), "path" to str("Dosya (biliniyorsa)"), "ref" to str("Branch (boşsa varsayılan)"),
            required = listOf("symbol")))
    private val codemod = ToolSpec("codemod",
        "Çok dosyalı MEKANİK değişiklikleri (yeniden adlandırma, API göçü, kalıp değişimi) tek yapısal kuralla yapar: ast-grep GitHub Actions'ta " +
            "tüm repoya uygulanır, değişen dosyalar commit denetimlerinden geçip tek commit olur. Her dosyaya ayrı diff yazmaktan çok daha ucuz " +
            "ve hatasız. Kalıpta \$A gibi meta değişkenler kullanılır, ör. pattern 'oldName(\$A)', rewrite 'newName(\$A)'.",
        obj("repo" to str(repoDesc), "branch" to str("Hedef branch"), "base_branch" to str("Branch yoksa kaynağı"),
            "lang" to str("kotlin, java, typescript, tsx, javascript, python, ..."), "pattern" to str("Aranan kod kalıbı"),
            "rewrite" to str("Yerine yazılacak kalıp"), "rule" to str("pattern yerine ast-grep YAML kuralı (isteğe bağlı)"),
            "paths" to str("Yalnızca bu klasör (isteğe bağlı)"), "message" to str("Commit mesajı"), required = listOf("branch")))
    private val mutationTest = ToolSpec("mutation_test",
        "Testlerin gerçekten koruduğunu ölçer: değişen koda bilerek küçük hatalar (mutant) sokar, ilgili testleri her birine karşı çalıştırır " +
            "(GitHub Actions). Yakalanmayan mutantlar testlerdeki boşluklardır. Test yazdıktan sonra çağır; düşük skorda eksik testleri ekle.",
        obj("repo" to str(repoDesc), "branch" to str("Kodun olduğu branch"), "path" to str("Mutasyona uğrayacak kaynak dosya"),
            "start_line" to int("Aralık başı (isteğe bağlı)"), "end_line" to int("Aralık sonu"),
            "tests" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Test dosyaları (boşsa otomatik seçilir)"),
            required = listOf("branch", "path")))
    private val updateTodos = ToolSpec("update_todos",
        "Kullanıcının gördüğü görev listesini yazar/günceller. 3+ adımlı işlerde ilk iş olarak listeyi çıkar; her adımda tam listeyi " +
            "tekrar gönder, aynı anda yalnızca bir madde in_progress olsun, biten maddeyi hemen done yap.",
        obj("items" to JSONObject().put("type", "array").put("description", "Tüm maddeler, sırayla")
            .put("items", obj("text" to str("Kısa madde"),
                "status" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("pending", "in_progress", "done"))),
                required = listOf("text", "status"))), required = listOf("items")))
    private val semanticSearch = ToolSpec("semantic_search",
        "Kodu ANLAMINA göre arar (ör. 'ödeme akışı nerede', 'skor nasıl kaydediliyor'). Dosya adını bilmediğinde github_search_code " +
            "yerine kullan. İlk çağrıda repo dizinlenir (sonra yalnızca değişen dosyalar). En ilgili kod parçalarını satır aralığıyla döndürür.",
        obj("repo" to str(repoDesc), "query" to str("Ne aradığın, doğal dilde"), "ref" to str("Branch (boşsa varsayılan)"),
            required = listOf("query")))

    val specs: List<ToolSpec> get() = listOf(webSearch, webFetch, listRepos, getTree, readFile, readFiles, applyDiff, writeFiles,
        createBranch, createPr, mergePr, releases, runWorkflow, searchCode, ciWait, ciErrors, githubApi, loadSkill, runCloudTool) + extraSpecs(true)

    /** Tools the architect may use while planning (github_api is restricted to GET at run time). */
    val readOnlySpecs: List<ToolSpec> get() = listOf(webSearch, webFetch, listRepos, getTree, readFile, readFiles, searchCode, ciErrors,
        githubApi, submitPlan, loadSkill) + extraSpecs(false)

    /** Optional tools, in a fixed order so the tool list (part of the cached prefix) only changes with the settings. */
    private fun extraSpecs(writer: Boolean): List<ToolSpec> = buildList {
        if (settings.docsLookup) add(lookupDocs)
        add(findReferences)
        add(readSymbol)
        if (writer && cloudOps != null) { add(codemod); add(mutationTest) }
        if (settings.semanticSearch && semantic != null && settings.geminiKey.isNotBlank()) add(semanticSearch)
        if (writer && settings.todoList) add(updateTodos)
    }

    private val docs = DocsLookup(web) { settings.context7Key }
    /** Set by the Agent when it has app storage. */
    var semantic: com.suarez.coder.context.SemanticIndex? = null

    /** Runs before a commit with (path, before, after); throws ToolException to block it (self-review). */
    var preCommit: (suspend (repo: String, branch: String, changes: List<Triple<String, String?, String?>>) -> Unit)? = null
    /** Called after every commit with (repo, commit result text, changed paths, new files): the risk model's data. */
    var afterCommit: ((repo: String, result: String, paths: List<String>, newFiles: Int) -> Unit)? = null

    /** Warnings from the commit checks, appended to the commit result so the model sees them. */
    val commitNotes = ArrayList<String>()

    private fun notes(): String = if (commitNotes.isEmpty()) "" else "\n" + commitNotes.joinToString("\n").also { commitNotes.clear() }

    /** Runs before the first write to a branch (checkpoint). */
    var beforeWrite: (suspend (repo: String, branch: String) -> Unit)? = null

    /** Set by the Agent when it has app storage (not for evolution agents). */
    var skills: com.suarez.coder.skills.SkillRepository? = null
    var cloud: com.suarez.coder.cloudtools.CloudToolRunner? = null

    /** The repository skills belong to: the pinned context repo, else the default repo. */
    fun activeRepo(): String = normalizeRepo(repoOverride ?: settings.contextRepo.ifBlank { settings.defaultRepo })

    /** Exam runs: the repo to use when a tool call names none. */
    var repoOverride: String? = null

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
        const val REF_SCAN_FILES = 150
        /** CI polling interval; tests shorten it. */
        @Volatile var CI_POLL_MS = 15_000L

        /** Tools that change files, run things or change the repository. */
        val WRITE_TOOLS = setOf("github_write_files", "github_apply_diff", "github_create_branch", "github_create_pull_request",
            "github_merge_pull_request", "github_run_workflow", "ci_wait", "run_cloud_tool", "codemod", "mutation_test")

        /** Hard guard for planning roles: only read tools, and github_api only with GET. */
        fun architectDenied(name: String, method: String?): String? {
            if (name in WRITE_TOOLS || name !in READ_TOOLS) return "Mimar salt okunurdur: '$name' kullanılamaz. Kod ve GitHub işlemleri Uygulayıcı'nındır."
            if (name == "github_api" && (method ?: "GET").uppercase() != "GET") return "Mimar salt okunurdur: yalnızca GET istekleri."
            return null
        }

        val OPS_TOOLS = setOf("github_create_branch", "github_create_pull_request", "github_merge_pull_request", "github_run_workflow", "ci_wait", "github_api")
        private val READ_TOOLS = setOf("web_search", "web_fetch", "github_list_repos", "github_get_tree", "github_read_file",
            "github_read_files", "github_search_code", "ci_errors", "github_releases", "github_api", "submit_plan", "load_skill",
            "lookup_docs", "semantic_search", "update_todos", "find_references", "read_symbol")

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
        "github_merge_pull_request", "github_run_workflow", "run_cloud_tool", "codemod", "mutation_test" -> true
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
            val r = s("repo") ?: repoOverride ?: settings.defaultRepo.takeIf { it.isNotBlank() } ?: throw ToolException("'repo' parametresi gerekli")
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
                val befores = files.associate { f -> f.path to runCatching { readForEdit(repo, branch, base, f.path) }.getOrNull() }
                val problems = files.filter { !it.delete && it.content != null }.mapNotNull { f ->
                    CodeValidator.validate(f.path, befores[f.path], f.content!!)?.let { "${f.path}: $it" }
                }
                if (problems.isNotEmpty()) throw ToolException("Yerel kontrol başarısız, hiçbir şey yazılmadı:\n" + problems.joinToString("\n"))
                commitNotes.clear()
                preCommit?.invoke(repo, branch, files.map { Triple(it.path, befores[it.path], if (it.delete) null else it.content) })
                beforeWrite?.invoke(repo, branch)
                github.commitFiles(repo, branch, req("message"), files, base).also { r ->
                    afterCommit?.invoke(repo, r, files.map { it.path }, files.count { !it.delete && befores[it.path] == null })
                } + staleNote(files.map { it.path }) + notes()
            }
            "github_create_branch" -> github.createBranch(repo(), req("branch"), s("from"))
            "github_create_pull_request" -> github.createPullRequest(repo(), req("title"), req("head"), s("base"), s("body"))
            "github_merge_pull_request" -> github.mergePullRequest(repo(), i("number") ?: throw ToolException("'number' gerekli"),
                (s("method") ?: "merge").lowercase(), s("commit_title"))
            "github_run_workflow" -> github.dispatchWorkflow(repo(), req("workflow"), s("ref"),
                s("inputs_json")?.let { runCatching { JSONObject(it) }.getOrElse { throw ToolException("inputs_json geçerli bir JSON nesnesi değil") } })
            "github_releases" -> github.releases(repo(), i("limit") ?: 5)
            "github_search_code" -> github.searchCode(req("query"))
            "github_api" -> github.raw(s("method") ?: "GET", req("path"), s("body"))
            "load_skill" -> skills?.load(req("name"), activeRepo(), s("file")) ?: "Yetenek sistemi bu oturumda kullanılamıyor."
            "lookup_docs" -> docs.lookup(req("library"), req("topic"), s("version"))
            "find_references" -> findReferences(repo(), s("ref"), req("symbol").trim())
            "read_symbol" -> readSymbol(repo(), s("ref"), req("symbol").trim(), s("path"))
            "codemod" -> (cloudOps ?: throw ToolException("Codemod bu oturumda kullanılamıyor.")).codemod(repo(), req("branch"), s("base_branch"), a)
            "mutation_test" -> (cloudOps ?: throw ToolException("Mutasyon testi bu oturumda kullanılamıyor.")).mutation(repo(), req("branch"), req("path"),
                i("start_line"), i("end_line"), Args.stringList(a, "tests"))
            "update_todos" -> com.suarez.coder.agent.Todos.ack(com.suarez.coder.agent.Todos.parse(a))
            "semantic_search" -> (semantic ?: throw ToolException("Anlamsal arama bu oturumda kullanılamıyor.")).search(repo(), s("ref"), req("query"))
            "run_cloud_tool" -> {
                val runner = cloud ?: throw ToolException("Bulut araçları bu oturumda kullanılamıyor.")
                val r = repo()
                ciListener?.started(r, "Bulut aracı: ${s("tool") ?: ""}")
                var ok = false
                try {
                    runner.run(r, req("tool"), req("script_path"), s("output_dir"), s("branch"), s("blender_version"),
                        listOf("app_dir", "composition", "requirements").mapNotNull { k -> s(k)?.let { k to it } }.toMap()).also { ok = !it.contains("BAŞARISIZ") }
                } finally {
                    ciListener?.finished(r, ok, if (ok) "Bulut aracı bitti" else "Bulut aracı başarısız", null)
                }
            }
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
        commitNotes.clear()
        preCommit?.invoke(repo, branch, changes.map { Triple(it.path, originals[it.path], it.content) })
        beforeWrite?.invoke(repo, branch)
        return github.commitFiles(repo, branch, message, changes, base).also { r ->
            afterCommit?.invoke(repo, r, changes.map { it.path }, changes.count { originals[it.path] == null })
        } + "\n" + summary + staleNote(changes.map { it.path }) + notes()
    }

    private fun staleNote(paths: List<String>): String {
        val stale = paths.filter { isInSnapshot(it) }
        return if (stale.isEmpty()) "" else "\nNot: proje bağlamındaki ${stale.joinToString()} kopyası artık eski; güncel hali bu değişiklikle oluştu."
    }

    suspend fun readFilesText(repo: String, paths: List<String>, ref: String?): String = readMany(repo, paths, ref)

    suspend fun fetchUrl(url: String): String = web.fetch(url, 0, 12_000)

    /** Set by the Agent: cloud-backed code tools (index, codemod, mutation). */
    var cloudOps: CloudOps? = null

    /** Files to look in for [name] from the code index, plus files changed since it was built; null without an index. */
    private suspend fun indexCandidates(repo: String, ref: String?, name: String): List<String>? {
        val idx = runCatching { cloudOps?.codeIndex(repo, false) }.getOrNull() ?: return null
        val changed = if (idx.sha.isBlank()) emptyList() else runCatching {
            github.changedSince(repo, idx.sha, ref ?: github.defaultBranch(repo))
        }.getOrDefault(emptyList())
        return (idx.filesFor(name) + changed).toList()
    }

    /** One symbol's code: from the index's definition (or [path]), cut with brace/indent matching. */
    private suspend fun readSymbol(repo: String, ref: String?, symbol: String, path: String?): String {
        val name = symbol.substringAfterLast('.')
        val idx = runCatching { cloudOps?.codeIndex(repo, false) }.getOrNull()
        val defs = idx?.defs?.get(name).orEmpty().filter { path == null || it.path == path.trimStart('/') }
        val cands: List<Pair<String, Int?>> = when {
            defs.isNotEmpty() -> defs.map { it.path to it.line }
            path != null -> listOf(path.trimStart('/') to null)
            else -> (indexCandidates(repo, ref, name) ?: filteredTree(repo, ref).map { it.path }.filter { com.suarez.coder.context.SemanticIndex.indexable(GitHub.TreeEntry(it, 1)) }.take(REF_SCAN_FILES))
                .map { it to null }
        }
        runCatching { github.readFilesBatch(repo, ref, cands.map { it.first }.distinct().take(GitHub.GRAPHQL_BATCH)) }
        val out = StringBuilder()
        var found = 0
        for ((p, hint) in cands.distinctBy { it.first }) {
            val text = github.readFileRaw(repo, p, ref) ?: continue
            val r = com.suarez.coder.agent.SymbolExtractor.find(text, name, hint) ?: continue
            val lines = text.lines()
            out.append("### $p:${r.start}-${r.end} (dosya ${lines.size} satır)\n")
            for (n in r.start..r.end) out.append(n).append(": ").append(lines[n - 1]).append('\n')
            out.append('\n')
            if (++found >= 3) break
        }
        return if (found == 0) "'$name' tanımı bulunamadı" + (if (idx == null) " (kod dizini henüz yok)" else "") + "; github_read_file ile dosyayı oku." else out.toString()
    }

    /** Definition and uses of a symbol: the code index when there is one, else GitHub code search, else a bounded scan. */
    private suspend fun findReferences(repo: String, ref: String?, symbol: String): String {
        if (!Regex("[\\w$.]{2,80}").matches(symbol)) throw ToolException("Geçersiz sembol: $symbol")
        val code = Regex("\\.(kt|kts|java|py|js|jsx|ts|tsx|mjs|go|rs|swift|dart|gd|cs|c|cc|cpp|h|hpp|rb|php|lua|vue|svelte|xml)$", RegexOption.IGNORE_CASE)
        val tree = filteredTree(repo, ref).filter { code.containsMatchIn(it.path) && it.size < 200_000 }
        val name = symbol.substringAfterLast('.')
        var source = "kod araması"
        val fromIndex = indexCandidates(repo, ref, name)?.filter { p -> tree.any { it.path == p } }
        var paths: List<String> = if (fromIndex != null) { source = "kod dizini"; fromIndex } else runCatching {
            val body = github.raw("GET", "/search/code?q=${java.net.URLEncoder.encode("$name repo:$repo", "UTF-8")}&per_page=30", null)
            if (!body.startsWith("HTTP 200")) emptyList() else {
                val items = JSONObject(body.substringAfter('\n')).optJSONArray("items") ?: JSONArray()
                (0 until items.length()).map { items.getJSONObject(it).optString("path") }
            }
        }.getOrDefault(emptyList()).filter { p -> tree.any { it.path == p } }
        if (fromIndex == null && (paths.isEmpty() || ref != null)) {
            // The search index only covers the default branch: scan the tree (bounded).
            source = "dal taraması"
            paths = tree.sortedBy { it.size }.take(REF_SCAN_FILES).map { it.path }
        }
        val defs = ArrayList<String>(); val uses = ArrayList<String>()
        runCatching { github.readFilesBatch(repo, ref, paths.take(GitHub.GRAPHQL_BATCH * 3)) }
        for (p in paths) {
            val text = github.readFileRaw(repo, p, ref) ?: continue
            for ((n, line) in com.suarez.coder.agent.References.lines(text, name)) {
                val row = "$p:$n  $line"
                if (com.suarez.coder.agent.References.isDefinition(line, name)) defs += row else uses += row
            }
            if (defs.size + uses.size > 120) break
        }
        if (defs.isEmpty() && uses.isEmpty()) return "'$name' bulunamadı ($source, ${paths.size} dosya)."
        val files = (defs + uses).map { it.substringBefore(':') }.distinct().size
        return "'$name': ${defs.size} tanım, ${uses.size} kullanım, $files dosya ($source)\n" +
            (if (defs.isNotEmpty()) "TANIM:\n" + defs.joinToString("\n") + "\n" else "") +
            (if (uses.isNotEmpty()) "KULLANIM:\n" + uses.take(100).joinToString("\n") else "") +
            (if (paths.size >= REF_SCAN_FILES && source == "dal taraması") "\n(İlk $REF_SCAN_FILES dosya tarandı.)" else "")
    }

    private suspend fun readMany(repo: String, paths: List<String>, ref: String?): String {
        runCatching { github.readFilesBatch(repo, ref, paths.filter { !FileFilter.isBinaryOrLock(it) }) }
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

    internal suspend fun ciErrors(repo: String, runId: Long): String {
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
    /** Set by the Agent: tests that flipped before (per repo). */
    var flaky: com.suarez.coder.ci.Flaky.Store? = null

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
                delay(CI_POLL_MS)
            }
            if (runs.isEmpty() || runs.any { it.status != "completed" }) {
                summary = "Zaman aşımı ($timeoutMinutes dk): " + runs.joinToString { "${it.name}: ${it.status}" }
                return "$summary. Daha sonra tekrar ci_wait çağır."
            }
            var failed = runs.filter { it.conclusion != "success" && it.conclusion != "skipped" && it.conclusion != "cancelled" }
            var rerunNote = ""
            // A failure that looks transient (network, runner) or comes only from tests known to flip: re-run once, no model call.
            if (failed.isNotEmpty() && settings.flakyRetry) {
                val logs = failed.associate { it.id to runCatching { ciErrors(repo, it.id) }.getOrDefault("") }
                val known = flaky?.known(repo).orEmpty()
                val retry = failed.filter { r -> logs[r.id].orEmpty().let { com.suarez.coder.ci.Flaky.isTransient(it) || com.suarez.coder.ci.Flaky.onlyKnownFlaky(it, known) } }
                if (retry.isNotEmpty() && retry.all { runCatching { github.rerunFailedJobs(repo, it.id) }.getOrDefault(false) }) {
                    listener?.started(repo, "geçici hata: yeniden çalıştırılıyor")
                    delay(CI_POLL_MS)
                    while (System.currentTimeMillis() < deadline) {
                        runs = github.runsForSha(repo, sha)
                        if (runs.all { it.status == "completed" }) break
                        delay(CI_POLL_MS)
                    }
                    val again = runs.filter { it.conclusion != "success" && it.conclusion != "skipped" && it.conclusion != "cancelled" }
                    val flipped = retry.filter { r -> again.none { it.id == r.id } }
                    flipped.forEach { r -> flaky?.record(repo, com.suarez.coder.ci.Flaky.failedTests(logs[r.id].orEmpty())) }
                    rerunNote = if (flipped.isNotEmpty()) "Not: ilk çalıştırma geçici bir hatayla düştü, yeniden çalıştırılınca geçti (kod hatası değil).\n"
                        else "Not: geçici görünen hata nedeniyle bir kez yeniden çalıştırıldı, yine başarısız (gerçek hata olabilir).\n"
                    failed = again
                }
            }
            val sb = StringBuilder("Commit ${sha.take(7)}:\n").append(rerunNote)
            runs.forEach { sb.append("- ${it.name}: ${it.conclusion} (${it.url})\n") }
            // Only cancelled runs (superseded by a newer commit) prove nothing.
            success = failed.isEmpty() && runs.any { it.conclusion != "cancelled" }
            if (failed.isEmpty() && !success) {
                summary = "Tüm çalıştırmalar iptal edildi (yeni bir commit tarafından geçilmiş olabilir)."
                sb.append("\n").append(summary).append(" Son commit için tekrar ci_wait çağır.\n")
                return sb.toString()
            }
            if (success) {
                val ship = runCatching { github.shipFor(repo, sha, branch) }.getOrNull()
                apk = ship?.apkUrl ?: runCatching { github.artifactLink(repo, runs.map { it.id }) }.getOrNull()
                apk?.let { sb.append("\nAPK: ").append(it).append('\n') }
                shipMissing = com.suarez.coder.ci.AutoShip.warning(ship, repo, branch ?: sha, runs.lastOrNull()?.url)
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
