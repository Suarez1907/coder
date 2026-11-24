package com.suarez.coder.agent

import com.suarez.coder.cloudtools.CloudWorkflowTemplates
import com.suarez.coder.cloudtools.CloudWorkflowTemplates.CloudTool
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.tools.CloudOps
import com.suarez.coder.tools.GitHub
import com.suarez.coder.tools.ToolException
import com.suarez.coder.validate.CodeValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Cloud-backed code tools: the code index, structural codemods and mutation testing. Each run happens on a throwaway
 * "[skip ci]" branch that carries the workflow file itself, so the user's branches are never touched by tooling and
 * their own CI does not start; the branch is deleted afterwards. A workflow is installed on the default branch only
 * once per repo, with the user's consent.
 */
internal class CloudSkills(private val agent: Agent) : CloudOps {
    private val settings get() = agent.settings
    private val tools get() = agent.tools
    private val store = agent.filesDirOrNull()?.let { CodeIndexStore(File(it, "codeindex")) }
    private val building: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The workflow on the default branch (installed once with consent, upgraded silently); false when not allowed. */
    suspend fun ensureWorkflow(repo: String, t: CloudTool, why: String): Boolean {
        val runner = tools.cloud ?: return false
        val key = "${repo.lowercase()}|${t.id}"
        if (key in settings.workflowDeclined || (t == CloudWorkflowTemplates.QUICK && repo.lowercase() in settings.quickCheckDeclined)) return false
        val state = try {
            CloudWorkflowTemplates.state(t, tools.github.readFileRaw(repo, t.path, tools.github.defaultBranch(repo)))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { return false }
        when (state) {
            CloudWorkflowTemplates.InstallState.CURRENT, CloudWorkflowTemplates.InstallState.CUSTOM -> return true
            CloudWorkflowTemplates.InstallState.OUTDATED -> { // our own older version: consent was given before
                return runCatching { runner.install(repo, t, null, force = false, upgrade = true, skipCi = true) }.isSuccess
            }
            CloudWorkflowTemplates.InstallState.MISSING -> {
                if (agent.unattended || agent.isEvolutionSession) return false
                val ok = agent.liveUi?.confirm("${t.title} kurulsun mu?",
                    "$why\n\nBunun için $repo varsayılan dalına .github/workflows/${t.file} eklenecek ([skip ci] ile, CI tetiklemez). " +
                        "Her çalıştırma geçici bir dalda olur; senin dallarına dokunulmaz.", "Kur", "Bu repoda kullanma") ?: return false
                if (!ok) { settings.workflowDeclined = settings.workflowDeclined + key; return false }
                return runCatching { runner.install(repo, t, null, force = false, upgrade = true, skipCi = true) }.isSuccess
            }
        }
    }

    /** Runs [t] on a throwaway branch made from [source] plus [files]; returns its output folder (or throws). */
    suspend fun runOnScratch(repo: String, source: String, t: CloudTool, inputs: JSONObject, files: List<GitHub.FileChange> = emptyList(), maxWaitMs: Long = 25 * 60_000L): File {
        val runner = tools.cloud ?: throw ToolException("Bulut araçları kullanılamıyor.")
        val gh = tools.github
        val tmp = "coder-tmp-${t.id}-${System.currentTimeMillis() % 10_000_000}"
        val t0 = System.currentTimeMillis()
        try {
            gh.commitFiles(repo, tmp, "[skip ci] coder: geçici ${t.title}", (files + GitHub.FileChange(t.path, t.yaml, false)).distinctBy { it.path }, source)
            agent.liveUi?.status("${t.title} bulutta çalışıyor")
            val run = runner.dispatchAndWait(repo, t, tmp, inputs, maxWaitMs) ?: throw ToolException("${t.title} zamanında bitmedi.")
            if (run.conclusion != "success") throw ToolException("${t.title} başarısız (${run.url}):\n" + runCatching { tools.ciErrors(repo, run.id) }.getOrDefault("").take(2_000))
            return runner.downloadOutputs(repo, t, run) ?: throw ToolException("${t.title} çıktı üretmedi (${run.url}).")
        } finally {
            agent.liveUi?.status(null)
            agent.span(Span.CLOUD, t.title, t0)
            runCatching { gh.deleteBranch(repo, tmp) }
        }
    }

    // ---- Code index ----

    override suspend fun codeIndex(repo: String, refresh: Boolean): CodeIndex? {
        if (!settings.codeIndex || store == null) return null
        val cur = store.get(repo)
        val stale = cur == null || refresh || System.currentTimeMillis() - cur.builtAt > 24 * 3_600_000L
        if (stale && repo !in building && ensureWorkflow(repo, CloudWorkflowTemplates.INDEX, "Kesin referans bulma ve sembol okuma için repo bir kez dizinlenecek (universal-ctags, 1-3 dk).")) {
            if (!building.add(repo)) return cur
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    val base = tools.github.defaultBranch(repo)
                    val dir = runOnScratch(repo, base, CloudWorkflowTemplates.INDEX, JSONObject(), maxWaitMs = 15 * 60_000L)
                    File(dir, "index.json").takeIf { it.isFile }?.let { store.put(repo, it.readText()) }
                    dir.deleteRecursively()
                } catch (e: Exception) { /* the next use tries again */ } finally { building -= repo }
            }
        }
        return cur
    }

    // ---- Codemod ----

    override suspend fun codemod(repo: String, branch: String, base: String?, args: JSONObject): String {
        val lang = args.optString("lang").trim().lowercase()
        val rule = args.optString("rule").trim()
        val pattern = args.optString("pattern").trim(); val rewrite = args.optString("rewrite")
        if (rule.isEmpty() && (pattern.isEmpty() || lang !in LANGS)) throw ToolException("pattern + rewrite + lang (${LANGS.joinToString()}) ya da rule (ast-grep YAML) gerekli.")
        val paths = args.optString("paths").trim().takeIf { it.isEmpty() || Regex("^[\\w./-]+$").matches(it) } ?: throw ToolException("paths göreli bir klasör olmalı.")
        if (!ensureWorkflow(repo, CloudWorkflowTemplates.CODEMOD, "Yapısal toplu düzenleme (ast-grep) GitHub Actions'ta çalışır.")) throw ToolException("Codemod iş akışı kurulmadı (izin verilmedi).")
        val gh = tools.github
        val source = gh.branchSha(repo, branch)?.let { branch } ?: base?.takeIf { it.isNotBlank() } ?: gh.defaultBranch(repo)
        val inputs = JSONObject().put("lang", lang).put("pattern", pattern).put("rewrite", rewrite).put("rule", rule).put("paths", paths)
        val dir = runOnScratch(repo, source, CloudWorkflowTemplates.CODEMOD, inputs, maxWaitMs = 15 * 60_000L)
        try {
            val changed = runCatching { JSONObject(File(dir, "changes.json").readText()) }.getOrDefault(JSONObject())
            val summary = runCatching { File(dir, "summary.txt").readText().trim() }.getOrDefault("")
            if (changed.has("__too_large__")) throw ToolException("Codemod çok fazla değişiklik üretti (${changed.getString("__too_large__")}); hiçbiri commit edilmedi. paths ile klasörü daralt.")
            if (changed.length() == 0) {
                val log = runCatching { File(dir, "log.txt").readText().trim() }.getOrDefault("")
                return "Kural hiçbir yerle eşleşmedi; değişiklik yok." + (if (log.isNotEmpty()) "\nast-grep: ${log.take(1_000)}" else "")
            }
            val files = changed.keys().asSequence().map { p -> GitHub.FileChange(p, changed.getString(p), false) }.toList()
            val befores = files.associate { it.path to runCatching { tools.readForEdit(repo, branch, base, it.path) }.getOrNull() }
            val problems = files.mapNotNull { f -> CodeValidator.validate(f.path, befores[f.path], f.content!!)?.let { "${f.path}: $it" } }
            if (problems.isNotEmpty()) throw ToolException("Codemod sonucu yerel kontrolden geçmedi, commit yapılmadı:\n" + problems.joinToString("\n"))
            tools.commitNotes.clear()
            tools.preCommit?.invoke(repo, branch, files.map { Triple(it.path, befores[it.path], it.content) })
            tools.beforeWrite?.invoke(repo, branch)
            val result = gh.commitFiles(repo, branch, args.optString("message").ifBlank { "coder: yapısal düzenleme" }, files, base)
            tools.afterCommit?.invoke(repo, result, files.map { it.path }, 0)
            tools.onRepoChanged()
            return "Codemod ${files.size} dosyayı değiştirdi ve commit edildi.\n$summary\n$result" + tools.commitNotes.joinToString("\n", prefix = "\n").also { tools.commitNotes.clear() }
        } finally { dir.deleteRecursively() }
    }

    // ---- Mutation testing ----

    override suspend fun mutation(repo: String, branch: String, path: String, from: Int?, to: Int?, tests: List<String>?): String {
        val gh = tools.github
        val text = gh.readFileRaw(repo, path, branch) ?: throw ToolException("$path, $branch dalında yok.")
        val lines = text.lines().size
        val ms = Mutator.generate(path, text, from ?: 1, to ?: lines)
        if (ms.isEmpty()) return "Bu aralıkta mutasyona uygun satır yok (karşılaştırma, mantıksal işlem veya sabit içeren benzersiz satırlar)."
        val tree = runCatching { tools.filteredTree(repo, branch).map { it.path } }.getOrDefault(emptyList())
        val chosen = tests?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: TestSelector.select(listOf(path), tree, codeIndex(repo, false))
        if (chosen.isEmpty()) return "$path için test bulunamadı; mutasyon testi testleri ölçer. Önce bu dosya için test yaz."
        if (!ensureWorkflow(repo, CloudWorkflowTemplates.MUTATE, "Mutasyon testi: koda bilerek küçük hatalar sokulup testlerin yakalayıp yakalamadığı ölçülür.")) throw ToolException("Mutasyon iş akışı kurulmadı (izin verilmedi).")
        val inputs = JSONObject().put("script_path", ".coder/mutants.json")
        TestSelector.gradle(chosen)?.let { (m, c) -> inputs.put("test_module", m).put("test_classes", c.joinToString(" ")) }
        TestSelector.files(chosen).takeIf { it.isNotEmpty() }?.let { inputs.put("test_files", it.joinToString(" ")) }
        val source = gh.branchSha(repo, branch)?.let { branch } ?: gh.defaultBranch(repo)
        val dir = runOnScratch(repo, source, CloudWorkflowTemplates.MUTATE, inputs, listOf(GitHub.FileChange(".coder/mutants.json", Mutator.toJson(ms), false)), 40 * 60_000L)
        try {
            val (killed, survived, error) = Mutator.results(File(dir, "results.json").readText(), ms)
            if (error != null) return "Mutasyon testi ölçülemedi: $error"
            val total = killed + survived.size
            val pct = if (total == 0) 0 else killed * 100 / total
            return "Mutasyon skoru: $killed/$total yakalandı (%$pct). Testler: ${chosen.joinToString()}\n" +
                (if (survived.isEmpty()) "Tüm mutantlar yakalandı; testler bu kodu iyi koruyor."
                else "Testlerin YAKALAMADIĞI değişiklikler (bunları yakalayan test ekle):\n" + survived.joinToString("\n") { "- $it" })
        } finally { dir.deleteRecursively() }
    }

    companion object {
        val LANGS = setOf("kotlin", "java", "typescript", "tsx", "javascript", "python", "go", "rust", "c", "cpp", "csharp", "swift", "ruby", "php", "lua", "scala", "dart", "html", "css")
    }
}
