package com.suarez.coder.evolution

import android.content.Context
import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.agent.StopException
import com.suarez.coder.data.Provider
import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.Locale

/**
 * Coordinates the three tiers for recorded traces. Runs in the background (WorkManager) or on demand; it never
 * touches the chat, and its own model calls are an evolution session (never recorded, never chained).
 */
class EvolutionEngine(
    context: Context,
    private val settings: Settings = Settings(context),
    val store: EvolutionStore = storeFor(context),
) {
    companion object {
        fun storeFor(context: Context) = EvolutionStore(File(context.filesDir, "evolution"))
        const val MAX_TRACES_PER_RUN = 2
    }

    private val silent = object : AgentUi {
        override fun update() {}
        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String) = false
    }

    /** Returns a one-line result for the UI. [force]: the manual button (ignores the switch and the cooldown, not the budget). */
    suspend fun run(force: Boolean = false, onStatus: (String) -> Unit = {}): String {
        val now = System.currentTimeMillis()
        val budget = settings.evoDailyBudget
        val block = EvolutionGuard.mayRun(settings.evoEnabled, isEvolutionSession = false, hasKey = settings.openrouterKey.isNotBlank(),
            spentToday = store.spentToday(now), dailyBudget = budget, lastRunAt = store.lastRunAt, now = now, force = force)
        when (block) {
            EvolutionGuard.Block.DISABLED -> return "Öz-gelişim kapalı."
            EvolutionGuard.Block.NO_KEY -> return "OpenRouter anahtarı yok (evrim modeli OpenRouter'da çalışır)."
            EvolutionGuard.Block.BUDGET -> return "Günlük evrim bütçesi doldu."
            EvolutionGuard.Block.COOLDOWN -> return "Bekleme süresi dolmadı."
            else -> Unit
        }
        val traces = store.unprocessed().takeLast(MAX_TRACES_PER_RUN).ifEmpty {
            if (force) store.traces().takeLast(1) else emptyList()
        }
        if (traces.isEmpty()) return "Değerlendirilecek seans yok."
        // A wrong model id would fail every run: say so once instead of using up sessions.
        val known = Agent(settings).openRouterModels()
        if (known.isNotEmpty() && settings.evoModel !in known) {
            val msg = "Evrim modeli OpenRouter listesinde yok: ${settings.evoModel}. Öz-Gelişim ekranından geçerli bir model seçin."
            if (store.log().lastOrNull()?.summary != msg) store.addLog(EvoTier.INFO, msg)
            return msg
        }
        store.lastRunAt = now
        val results = ArrayList<String>()
        for (t in traces) results += process(t, onStatus)
        return results.joinToString(" ")
    }

    private suspend fun process(t: SessionTrace, onStatus: (String) -> Unit): String {
        val budget = settings.evoDailyBudget
        val startedToday = store.spentToday()
        var spent = 0.0
        var logged = 0.0
        val agent = Agent(settings).apply {
            isEvolutionSession = true
            modelOverride = ModelRef(Provider.OPENROUTER, settings.evoModel)
            budgetDeferred = true
            onUsage = { spent += it }
            beforeCall = { if (startedToday + spent >= budget) throw StopException("Günlük evrim bütçesi doldu.") }
        }
        fun log(tier: EvoTier, text: String, undo: String? = null) {
            store.addLog(tier, text, (spent - logged).coerceAtLeast(0.0), undo)
            logged = spent
        }
        val tiers = buildSet {
            if (settings.evoMemory) add(EvoTier.MEMORY)
            if (settings.evoRouting) add(EvoTier.ROUTING)
            if (settings.evoSelfPr && settings.githubToken.isNotBlank()) add(EvoTier.SELF_PR)
        }
        var produced = 0
        var consumed = true
        try {
            if (EvoTier.ROUTING in tiers) store.routing.record(t)
            // A clean session (no errors, retries, CI failures, stops or corrections) teaches nothing: no model call.
            if (t.troubleScore == 0) return "Temiz seans; model çağrılmadı."
            val recentTrouble = store.traces().takeLast(5).sumOf { it.troubleScore }
            // The Coder file list (the biggest part of the prompt) is only sent when a self-PR could follow.
            val selfPrPossible = maxOf(t.troubleScore, recentTrouble) >= EvolutionGuard.MIN_TROUBLE_FOR_PR && store.pending()?.state != PrState.PENDING
            val coderFiles = if (EvoTier.SELF_PR in tiers && selfPrPossible) runCatching {
                agent.tools.github.treeEntries(EvolutionGuard.SELF_REPO, null)?.second.orEmpty().map { it.path }
                    .filter { it.startsWith("app/src/main/java/") && it.endsWith(".kt") && !EvolutionGuard.isProtected(it) }
            }.getOrDefault(emptyList()) else emptyList()
            val tiersForModel = if (coderFiles.isEmpty()) tiers - EvoTier.SELF_PR else tiers
            onStatus("Seans değerlendiriliyor")
            val reflector = ReflectorEngine { system, user, maxOut ->
                // JSON mode where supported, low reasoning (thinking tokens are billed and can crowd out the answer).
                val m = agent.callModel(ArrayList(), listOf(ChatMessage.User(user)), agent.architectRef(), "reflector", system, emptyList(), silent,
                    useCache = false, maxOut = maxOut, thinking = "low", jsonMode = true)
                // Some reasoning models leave the JSON in their reasoning and the content empty.
                if (ReflectorEngine.extractJson(m.text) == null && m.reasoning?.let { ReflectorEngine.extractJson(it) } != null) m.reasoning!! else m.text
            }
            val notes = store.lessons(t.repo)
            val (report, raw) = reflector.reflect(t, if (EvoTier.ROUTING in tiers) store.routing.summary() else "", notes, tiersForModel, coderFiles)
            if (report == null) {
                log(EvoTier.INFO, "Seans değerlendirildi; model geçerli JSON döndürmedi. Yanıt: «" +
                    raw.replace(Regex("\\s+"), " ").trim().take(160).ifBlank { "(boş)" } + "»")
                return "JSON okunamadı."
            }

            // Tier 1: lessons (local project memory, injected into the stable context).
            if (EvoTier.MEMORY in tiers && report.lessons.isNotEmpty() && t.repo.isNotBlank()) {
                val merged = ReflectorEngine.mergeLessons(notes, report.lessons)
                if (merged != notes) {
                    val pruned = reflector.pruneIfNeeded(merged)
                    store.saveLessons(t.repo, pruned)
                    log(EvoTier.MEMORY, "${t.repo}: ${report.lessons.size} ders öğrenildi: " + report.lessons.joinToString("; ").take(300))
                    produced++
                }
            }
            // Tier 2: bounded routing parameters, undoable.
            if (EvoTier.ROUTING in tiers) {
                store.routing.apply(report)?.let { (before, after) ->
                    log(EvoTier.ROUTING, describe(before, after), undo = before.toJson().toString())
                    produced++
                }
            }
            // Tier 3: self-PR.
            val si = report.selfImprovement
            if (EvoTier.SELF_PR in tiersForModel && si != null) {
                val open = runCatching { agent.tools.github.openPullRequests(EvolutionGuard.SELF_REPO).count { it.head.startsWith(EvolutionGuard.BRANCH_PREFIX) } }.getOrDefault(1)
                val gate = EvolutionGuard.mayOpenPr(open, store.pending()?.state == PrState.PENDING, startedToday + spent, budget,
                    si.topic, store.rejectedTopics(), maxOf(t.troubleScore, recentTrouble))
                if (gate == EvolutionGuard.Block.OK) {
                    val out = SelfPrOrchestrator(agent, onStatus).run(si) { spent }
                    out.pr?.let { store.savePending(it); store.bump("prs") }
                    log(EvoTier.SELF_PR, (if (out.pr != null) "PR #${out.pr.number} açıldı: ${out.pr.title}. " else "Self-PR denemesi: ") + out.summary)
                    produced++
                } else {
                    log(EvoTier.INFO, "Self-PR adayı (${si.title}) beklemede: " + when (gate) {
                        EvolutionGuard.Block.OPEN_PR -> "açık bir evo PR'ı var."
                        EvolutionGuard.Block.BUDGET -> "bütçe yetmiyor."
                        EvolutionGuard.Block.REJECTED_TOPIC -> "bu konu daha önce reddedildi."
                        EvolutionGuard.Block.NO_EVIDENCE -> "tekrarlandığına dair yeterli kanıt yok."
                        else -> gate.name
                    })
                }
            }
            if (produced == 0) log(EvoTier.INFO, "Seans değerlendirildi: yeni ders veya ayar yok.")
            return "Değerlendirildi."
        } catch (e: CancellationException) {
            if (spent > logged) log(EvoTier.INFO, "Değerlendirme durduruldu.")
            throw e
        } catch (e: StopException) {
            log(EvoTier.INFO, e.message ?: "Bütçe doldu.")
            return e.message ?: "Bütçe doldu."
        } catch (e: Exception) {
            // Settings problems (wrong model id, key, credits) are not the session's fault: keep it for a later run.
            consumed = !isConfigError(e.message.orEmpty())
            log(EvoTier.INFO, (if (consumed) "Değerlendirme hatası: " else "Ayar hatası (seans sonra yeniden değerlendirilecek): ") + e.message?.take(200))
            return "Hata: ${e.message?.take(120)}"
        } finally {
            if (consumed) {
                store.updateTrace(t.id) { it.copy(processed = true) }
                store.bump("reviewed")
            }
        }
    }

    private fun isConfigError(msg: String) =
        Regex("(?i)(not a valid model|model.*not found|no endpoints|\\((401|402|403|404)\\)|api anahtarı|insufficient credits)").containsMatchIn(msg)

    private fun describe(before: AdaptiveRouting, after: AdaptiveRouting): String = buildList {
        if (before.complexityThreshold != after.complexityThreshold) {
            add("zorluk eşiği " + String.format(Locale.US, "%.2f → %.2f", before.complexityThreshold, after.complexityThreshold))
        }
        val hints = after.localHints - before.localHints.toSet()
        if (hints.isNotEmpty()) add("${hints.size} yönlendirme ipucu (" + hints.joinToString { "\"${it.pattern}\" → ${it.route}" }.take(200) + ")")
        val notes = after.escalationNotes - before.escalationNotes.toSet()
        if (notes.isNotEmpty()) add("erken yükseltme: " + notes.joinToString { it.model })
    }.joinToString("; ").ifBlank { "Yönlendirme güncellendi." }
}
