package com.suarez.coder.agent

import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Checks around every commit: self-review, quick compile check (and its daily warm-up), checkpoints. */
internal class CommitGuards(private val agent: Agent) {
    private val settings get() = agent.settings
    private val tools get() = agent.tools
    private val context get() = agent.context

    /**
     * Before anything else: secrets in the added lines and dependencies the change adds (does the package exist, does
     * it imitate a famous one, known vulnerabilities, very new and unused). Certain problems need the user's yes.
     */
    suspend fun supplyChain(repo: String, branch: String, changes: List<Triple<String, String?, String?>>) {
        if (!settings.supplyChainCheck) return
        val own = listOf(settings.githubToken, settings.geminiKey, settings.deepseekKey, settings.openrouterKey, settings.context7Key)
        val findings = changes.flatMap { (p, b, a) -> if (a == null) emptyList() else com.suarez.coder.security.Secrets.scan(p, b, a, own) }
        if (findings.isNotEmpty()) {
            val text = findings.joinToString("\n") { "${it.path}:${it.line} - ${it.kind}" }
            agent.liveHistory?.add(ChatMessage.Notice("Güvenlik: değişiklikte sır bulundu, commit durdu", text, isError = true))
            throw com.suarez.coder.tools.ToolException("Güvenlik: değişiklikte gizli anahtar/token var, commit YAPILMADI:\n$text\n" +
                "Değeri koddan çıkar; ortam değişkeni, GitHub secret veya git'e girmeyen yerel bir yapılandırma dosyası kullan.")
        }
        val deps = changes.flatMap { (p, b, a) -> if (a == null) emptyList() else com.suarez.coder.security.Deps.added(p, b, a) }.distinct().take(12)
        if (deps.isEmpty()) return
        // A private npm registry (.npmrc) makes "not on npmjs.org" meaningless.
        val privateNpm = deps.any { it.eco == "npm" } &&
            runCatching { tools.github.readFileRaw(repo, ".npmrc", branch) ?: tools.github.readFileRaw(repo, ".npmrc", null) }.getOrNull()?.contains("registry=") == true
        agent.liveUi?.status("Yeni bağımlılıklar denetleniyor (${deps.size})")
        val checker = com.suarez.coder.security.RegistryChecker()
        val verdicts = try {
            kotlinx.coroutines.coroutineScope { deps.filterNot { privateNpm && it.eco == "npm" }.map { d -> async { checker.check(d) } }.map { it.await() } }
        } finally { agent.liveUi?.status(null) }
        val o = com.suarez.coder.security.SupplyChainPolicy.evaluate(verdicts)
        if (o.warn.isNotEmpty()) {
            tools.commitNotes += o.warn.map { "Bağımlılık uyarısı: $it" }
            agent.liveHistory?.add(ChatMessage.Notice("Bağımlılık uyarısı (${o.warn.size})", o.warn.joinToString("\n")))
        }
        if (o.block.isEmpty()) return
        val text = o.block.joinToString("\n- ", prefix = "- ")
        val ok = !agent.unattended && !agent.isEvolutionSession && agent.liveUi?.confirm("Şüpheli bağımlılık",
            "$text\n\nModeller bazen var olmayan paket adları uydurur; saldırganlar bu adlarla zararlı paket yayınlar. Yine de eklensin mi?",
            "Yine de ekle", "Durdur") == true
        if (!ok) {
            agent.liveHistory?.add(ChatMessage.Notice("Bağımlılık denetimi commit'i durdurdu", text, isError = true))
            throw com.suarez.coder.tools.ToolException("Bağımlılık denetimi commit'i durdurdu:\n$text\n" +
                "Doğru paket adını ve sürümünü doğrula (fetch_live_docs / web_search), sonra değişikliği düzeltip tekrar gönder.")
        }
    }

    /** The self-reviewer's yes/no for a change without blocking anything (null: could not ask). Judges candidates. */
    suspend fun reviewVerdict(changes: List<Triple<String, String?, String?>>): Boolean? {
        if (changes.isEmpty()) return true
        val reply = runCatching {
            agent.callModel(agent.liveHistory ?: ArrayList(), listOf(ChatMessage.User("Change:\n\n" + SelfReview.view(changes))),
                agent.routerRef() ?: agent.editorRef(), "review", SelfReview.SYSTEM, emptyList(), Agent.NoUi, useCache = false, maxOut = 300, thinking = "low")
        }.getOrNull() ?: return null
        return SelfReview.parse(reply.text)?.let { it.ok || it.problems.isEmpty() }
    }

    companion object {
        /** Below this predicted failure probability the quick check is skipped (trained model only). */
        const val SAFE_RISK = 0.12
    }

    /** A new run: every change may be reviewed again, every branch gets a fresh checkpoint. */
    fun startRun() { reviewedOnce.clear(); runCheckpoints.clear() }

    internal val reviewedOnce = HashSet<Int>()
    private val quickBypass = HashSet<Int>()

    internal val runCheckpoints = HashSet<String>()

    internal suspend fun selfReview(repo: String, branch: String, changes: List<Triple<String, String?, String?>>) {
        if (!settings.selfReview || agent.isEvolutionSession) return
        val code = changes.filter { (p, b, a) -> a != null && a != b && !Regex("\\.(md|txt|json|ya?ml|xml|svg|csv|properties)$", RegexOption.IGNORE_CASE).containsMatchIn(p) }
        if (code.isEmpty()) return
        val key = code.joinToString("|") { "${it.first}:${it.third.hashCode()}" }.hashCode()
        if (!reviewedOnce.add(key)) return // the same change again after a warning: the model judged it a false alarm
        val history = agent.liveHistory ?: ArrayList()
        val ref = agent.routerRef() ?: agent.editorRef()
        agent.liveUi?.status("Öz-denetim: değişiklik commit'ten önce kontrol ediliyor")
        val reply = try {
            agent.callModel(history, listOf(ChatMessage.User("Change to $repo ($branch):\n\n" + SelfReview.view(code))), ref, "review",
                SelfReview.SYSTEM, emptyList(), Agent.NoUi, useCache = false, maxOut = 400, thinking = "low")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // the check is a helper: a failed call never blocks the commit
        } finally {
            agent.liveUi?.status(null)
        }
        val v = SelfReview.parse(reply.text) ?: return
        if (v.ok || v.problems.isEmpty()) return
        history += ChatMessage.Notice("Öz-denetim commit'i durdurdu (${v.problems.size} sorun)", v.problems.joinToString("\n"))
        throw com.suarez.coder.tools.ToolException("Öz-denetim (commit YAPILMADI) şu sorunları buldu:\n- " + v.problems.joinToString("\n- ") +
            "\nDosyayı gerekirse yeniden oku, düzelt ve tekrar gönder. Bu bir yanlış alarmsa aynı değişikliği bir kez daha gönder; ikinci kez denetlenmez.")
    }

    /**
     * Fills the quick-check caches (Gradle, npm) on the default branch once a day: caches made on the default branch are
     * readable from every branch, so the throwaway check branches start warm. Fire and forget.
     */
    internal fun prewarmQuickCheck(repo: String) {
        if (repo.isBlank()) return
        val runner = tools.cloud ?: return
        val warmed = runCatching { JSONObject(settings.quickCheckWarmedAt) }.getOrDefault(JSONObject())
        if (System.currentTimeMillis() - warmed.optLong(repo.lowercase()) < 20 * 3_600_000L) return
        warmed.put(repo.lowercase(), System.currentTimeMillis())
        settings.quickCheckWarmedAt = warmed.toString()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
            runCatching {
                val t = com.suarez.coder.cloudtools.CloudWorkflowTemplates.QUICK
                if (runner.installedOnDefault(repo, t)) tools.github.dispatchWorkflow(repo, t.file, tools.github.defaultBranch(repo), JSONObject())
            }
        }
    }

    internal suspend fun quickCheck(repo: String, branch: String, changes: List<Triple<String, String?, String?>>) {
        if (!settings.quickCheck || agent.isEvolutionSession) return
        val runner = tools.cloud ?: return
        val code = changes.filter { (p, b, a) -> a != b && !Regex("\\.(md|txt|svg|png|jpg|csv)$", RegexOption.IGNORE_CASE).containsMatchIn(p) }
        if (code.isEmpty()) return
        // Adaptive: a change the trained risk model rates very safe skips the 1-5 minute check.
        if (settings.quickCheckAdaptive) {
            val (p, trained) = agent.foresight.risk(repo, code.map { it.first }, code.count { it.second == null })
            if (trained && p < SAFE_RISK) {
                agent.liveHistory?.add(ChatMessage.Notice("Hızlı ön kontrol atlandı: düşük risk (%${(p * 100).toInt()} başarısızlık tahmini)"))
                return
            }
        }
        val t = com.suarez.coder.cloudtools.CloudWorkflowTemplates.QUICK
        if (!agent.cloudSkills.ensureWorkflow(repo, t, "Commit'ten önce derleme/tür denetimi ve değişen dosyaların testleri geçici bir dalda 1-5 dakikada çalışır.")) return
        // Same change sent again after a quick-check stop: the agent judged the failure pre-existing.
        val key = code.joinToString("|") { "${it.first}:${it.third.hashCode()}" }.hashCode()
        if (key in quickBypass) {
            agent.liveHistory?.add(ChatMessage.Notice("Hızlı ön kontrol: aynı değişiklik tekrar gönderildi, bu kez atlandı (hata önceden var olabilir)"))
            return
        }
        // The tests that exercise the changed files run too (by name, the code index, and the changed tests themselves).
        val inputs = org.json.JSONObject()
        var selected: List<String> = emptyList()
        if (settings.targetedTests) runCatching {
            val tree = tools.filteredTree(repo, tools.github.branchSha(repo, branch)?.let { branch }).map { it.path } + code.map { it.first }
            selected = TestSelector.select(code.map { it.first }, tree, agent.cloudSkills.codeIndex(repo, false))
            TestSelector.gradle(selected)?.let { (m, c) -> if (c.isNotEmpty()) inputs.put("test_module", m).put("test_classes", c.joinToString(" ")) }
            TestSelector.files(selected).takeIf { it.isNotEmpty() }?.let { inputs.put("test_files", it.joinToString(" ")) }
        }
        agent.liveUi?.status("Hızlı ön kontrol: derleme" + (if (selected.isNotEmpty()) " ve ${selected.size} test" else "") + " (GitHub Actions)")
        val t0 = System.currentTimeMillis()
        val r = try {
            QuickCheck(tools.github, runner) { rp, id -> tools.ciErrors(rp, id) }.check(repo, branch, code, inputs, selected)
                .also { agent.span(Span.CLOUD, "hızlı ön kontrol", t0, ok = it.ok) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            QuickCheck.Result(true, "Hızlı ön kontrol çalıştırılamadı (${e.message?.take(120)}); commit'e devam edildi.")
        } finally { agent.liveUi?.status(null) }
        agent.liveHistory?.add(ChatMessage.Notice(if (r.ok) "Hızlı ön kontrol: geçti" else "Hızlı ön kontrol: hata, commit durdu", r.report, isError = !r.ok))
        agent.liveUi?.update()
        if (!r.ok) {
            quickBypass += key
            throw com.suarez.coder.tools.ToolException(r.report + "\nHataları düzelt ve değişikliği tekrar gönder. Hata bu değişiklikten önce de varsa aynı değişikliği bir kez daha gönder.")
        }
    }

    internal suspend fun checkpoint(repo: String, branch: String) {
        if (!settings.checkpoints || agent.isEvolutionSession || !runCheckpoints.add("$repo@$branch")) return
        val sha = try { tools.github.branchSha(repo, branch) } catch (e: CancellationException) { throw e } catch (e: Exception) { runCheckpoints.remove("$repo@$branch"); return }
        agent.liveHistory?.add(ChatMessage.Notice("Kontrol noktası: $branch" + (sha?.let { " @ ${it.take(7)}" } ?: " (yeni dal)"),
            Checkpoints.marker(Checkpoints.Point(repo, branch, sha))))
    }
}
