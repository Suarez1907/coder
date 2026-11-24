package com.suarez.coder.agent

import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Checks around every commit: self-review, quick compile check (and its daily warm-up), checkpoints. */
internal class CommitGuards(private val agent: Agent) {
    private val settings get() = agent.settings
    private val tools get() = agent.tools
    private val context get() = agent.context

    /** A new run: every change may be reviewed again, every branch gets a fresh checkpoint. */
    fun startRun() { reviewedOnce.clear(); runCheckpoints.clear() }

    internal val reviewedOnce = HashSet<Int>()

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
        val t = com.suarez.coder.cloudtools.CloudWorkflowTemplates.QUICK
        if (repo.lowercase() in settings.quickCheckDeclined) return
        val installed = try { runner.installedOnDefault(repo, t) } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        if (!installed) {
            if (agent.unattended) return
            val ok = agent.liveUi?.confirm("Hızlı ön kontrol kurulsun mu?",
                "Commit'ten önce derleme/tür denetimi için $repo varsayılan dalına .github/workflows/${t.file} eklenecek ([skip ci] ile, CI tetiklemez). " +
                    "Kontrol her kod commit'inden önce geçici bir dalda 1-5 dakika sürer.", "Kur", "Bu repoda kullanma") ?: return
            if (!ok) { settings.quickCheckDeclined = settings.quickCheckDeclined + repo.lowercase(); return }
            try { runner.install(repo, t, null, force = false, upgrade = true, skipCi = true) } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        }
        agent.liveUi?.status("Hızlı ön kontrol: derleme denetleniyor (GitHub Actions)")
        val r = try {
            QuickCheck(tools.github, runner) { rp, id -> tools.ciErrors(rp, id) }.check(repo, branch, code)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            QuickCheck.Result(true, "Hızlı ön kontrol çalıştırılamadı (${e.message?.take(120)}); commit'e devam edildi.")
        } finally { agent.liveUi?.status(null) }
        agent.liveHistory?.add(ChatMessage.Notice(if (r.ok) "Hızlı ön kontrol: geçti" else "Hızlı ön kontrol: hata, commit durdu", r.report, isError = !r.ok))
        agent.liveUi?.update()
        if (!r.ok) throw com.suarez.coder.tools.ToolException(r.report + "\nHataları düzelt ve değişikliği tekrar gönder.")
    }

    internal suspend fun checkpoint(repo: String, branch: String) {
        if (!settings.checkpoints || agent.isEvolutionSession || !runCheckpoints.add("$repo@$branch")) return
        val sha = try { tools.github.branchSha(repo, branch) } catch (e: CancellationException) { throw e } catch (e: Exception) { runCheckpoints.remove("$repo@$branch"); return }
        agent.liveHistory?.add(ChatMessage.Notice("Kontrol noktası: $branch" + (sha?.let { " @ ${it.take(7)}" } ?: " (yeni dal)"),
            Checkpoints.marker(Checkpoints.Point(repo, branch, sha))))
    }
}
