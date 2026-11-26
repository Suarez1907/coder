package com.suarez.coder.agent

import kotlin.math.roundToInt

import com.suarez.coder.llm.ChatMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Looking ahead before spending: how likely a change is to fail CI (learned from the user's own commits), how many
 * candidate edits a plan deserves, and how sure the plan itself is.
 */
internal class Foresight(private val agent: Agent) {
    private val settings get() = agent.settings
    val log: ChangeLog? = agent.filesDirOrNull()?.let { ChangeLog(java.io.File(it, "changes.jsonl")) }
    private var model: RiskModel? = null
    private var trainedOn = -1

    /** The trained model, retrained when new labels arrived; null while there is too little data. */
    fun model(): RiskModel? {
        val l = log ?: return null
        val labelled = l.all().count { it.failed != null }
        if (labelled != trainedOn) { model = RiskModel.train(l.all()); trainedOn = labelled }
        return model
    }

    /** One line for the settings screen: how much the risk model has learned so far. */
    fun status(): String {
        val l = log ?: return "Risk modeli: kayıt yok."
        val labelled = l.all().count { it.failed != null }
        val m = model() ?: return "Risk modeli henüz eğitilmedi: $labelled/${RiskModel.MIN_SAMPLES} etiketli commit (her iki sonuçtan en az " +
            "${RiskModel.MIN_PER_CLASS}). O zamana kadar değişikliğin boyutuna ve türüne göre temkinli bir tahmin kullanılır."
        fun pct(v: Double) = (v * 100).roundToInt()
        return "Risk modeli ${m.samples} commit'ten öğrendi: AUC ${String.format(java.util.Locale.US, "%.2f", m.auc)}, doğruluk %${pct(m.accuracy)}, " +
            "kırmızı CI oranı %${pct(m.failRate)}. AUC 0,5 = yazı tura, 1 = kusursuz ayırt etme."
    }

    fun features(repo: String, paths: List<String>, newFiles: Int): DoubleArray =
        RiskFeatures.of(paths, newFiles, agent.lastComplexity, log?.repoFailRate(repo))

    /** (probability of a red CI, whether it comes from the trained model rather than the prior). */
    fun risk(repo: String, paths: List<String>, newFiles: Int): Pair<Double, Boolean> {
        val x = features(repo, paths, newFiles)
        val m = if (settings.riskModel) model() else null
        return (m?.pFail(x) ?: RiskModel.prior(x)) to (m != null)
    }

    /** How many candidate edits a file group gets. */
    fun candidates(repo: String, paths: List<String>, newFiles: Int): Int {
        val n = settings.candidates.coerceIn(1, 5)
        if (n <= 1 || agent.isEvolutionSession) return 1
        return when (settings.candidateTrigger) {
            "always" -> n
            "off" -> 1
            else -> if (risk(repo, paths, newFiles).first >= settings.riskThreshold) n else 1
        }
    }

    fun recordCommit(repo: String, result: String, paths: List<String>, newFiles: Int) {
        val l = log ?: return
        val sha = Regex("/commit/([0-9a-f]{40})").find(result)?.groupValues?.get(1) ?: return
        l.add(ChangeRecord(java.util.UUID.randomUUID().toString().take(8), repo, sha, System.currentTimeMillis(), features(repo, paths, newFiles)))
    }

    /** Fills in CI outcomes of earlier commits (a few per run, in the background). */
    suspend fun labelPending(max: Int = 12, minAgeMs: Long = 120_000) {
        val l = log ?: return
        val now = System.currentTimeMillis()
        val all = l.all().toMutableList()
        var changed = false
        var asked = 0
        for ((i, r) in all.withIndex()) {
            if (r.failed != null || now - r.at < minAgeMs || asked >= max) continue
            asked++
            val runs = runCatching { agent.tools.github.runsForSha(r.repo, r.sha) }.getOrNull() ?: continue
            if (runs.isEmpty()) {
                if (now - r.at > 86_400_000L) { all[i] = r.copy(failed = null, at = 0); changed = true } // no CI: never labelled
                continue
            }
            if (runs.any { it.status != "completed" }) continue
            val real = runs.filter { it.conclusion != "cancelled" && it.conclusion != "skipped" }
            if (real.isEmpty()) continue
            all[i] = r.copy(failed = real.any { it.conclusion != "success" }); changed = true
        }
        if (changed) l.save(all.filter { it.at != 0L })
    }

    /**
     * Two cheap, independent guesses of the files to change, compared with the plan. Low agreement means the request
     * can be read several ways (or the plan missed something).
     */
    suspend fun planUncertainty(history: MutableList<ChatMessage>, ui: AgentUi, plan: ParsedPlan): Pair<Double, List<Set<String>>>? {
        if (!settings.planUncertainty || agent.isEvolutionSession || plan.files.isEmpty()) return null
        val request = (history.lastOrNull { it is ChatMessage.User && !it.text.startsWith(Agent.AUTO_NUDGE) } as? ChatMessage.User)?.text ?: return null
        val map = agent.architectSnapshot()?.substringAfter("REPO MAP", "")?.take(30_000).orEmpty()
        val ref = agent.routerRef() ?: agent.editorRef()
        ui.status("Plan belirsizliği ölçülüyor")
        val guesses = try {
            coroutineScope {
                listOf(0.7, 1.0).map { t ->
                    async {
                        runCatching {
                            agent.callModel(history, listOf(ChatMessage.User("REQUEST:\n${request.take(3_000)}\n\nREPO MAP$map")), ref, "critic",
                                PlanUncertainty.SYSTEM, emptyList(), Agent.NoUi, useCache = false, maxOut = 300, thinking = "low", jsonMode = true, temperature = t)
                        }.getOrNull()?.let { PlanUncertainty.parse(it.text) }
                    }
                }.map { it.await() }
            }.filterNotNull()
        } finally { ui.status(null) }
        if (guesses.isEmpty()) return null
        val planned = plan.files.map { it.path }.toSet()
        return PlanUncertainty.agreement(listOf(planned) + guesses) to guesses
    }
}
