package com.suarez.coder.agent

import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** "Until green": what happens after each ci_wait (fix memory, escalation on a repeated error, round limit). */
internal class CiController(private val agent: Agent) {
    private val settings get() = agent.settings
    private val tools get() = agent.tools
    private val context get() = agent.context

    /** "Until green" bookkeeping for one agent loop. */
    internal class CiState {
        val seen = ArrayList<String>()
        var failures = 0
        var lastFailed = false
        var pendingSig: String? = null
        var pendingRepo = ""
        val fixes = ArrayList<String>()
        var nudges = 0
    }

    /** After a ci_wait: fix-memory hint, escalation on a repeated error, ask after too many rounds. Returns the model to use next, or null to stop. */
    internal suspend fun afterCi(call: com.suarez.coder.llm.ToolCall, result: ChatMessage.ToolResult, st: CiState, cur: ModelRef,
                                history: MutableList<ChatMessage>, ui: AgentUi): Pair<ChatMessage.ToolResult, ModelRef?> {
        val text = result.content
        val repo = call.args.optString("repo").ifBlank { tools.activeRepo() }
        if (com.suarez.coder.evolution.ExamRunner.ciMissing(text)) return result to cur
        if (com.suarez.coder.evolution.ExamRunner.ciPassed(text)) {
            st.lastFailed = false
            val sig = st.pendingSig
            if (sig != null && settings.fixMemory && st.fixes.isNotEmpty()) {
                val fix = st.fixes.joinToString("; ").take(800)
                agent.fixMemory?.record(FixMemory.Fix(st.pendingRepo, sig, fix, System.currentTimeMillis()))
                agent.ciKnowledge?.record(st.pendingRepo, sig, fix)
            }
            st.pendingSig = null; st.fixes.clear()
            // Auto-Ship: yeşil build sürüm yayınlamadıysa kullanıcıya tek satır uyarı.
            if (!agent.isEvolutionSession) tools.takeShipNotice()?.let { history += it }
            return result to cur
        }
        val sig = com.suarez.coder.evolution.SessionTraceRecorder.signature(text)
        st.failures++; st.lastFailed = true
        val repeated = st.seen.any { ErrorSig.similar(it, sig) }
        st.seen += sig
        if (st.pendingSig == null) { st.pendingSig = sig; st.pendingRepo = repo }
        var out = result
        if (settings.fixMemory) agent.fixMemory?.let { m -> m.hint(m.lookup(repo, sig))?.let { out = result.copy(content = text + "\n\n" + it) } }
        // AutoHealer: hata sınıfı + eylem kararı; tek satır Notice ve ders defterinden geçen seferki çözüm.
        val heal = com.suarez.coder.ci.AutoHealer.decide(
            com.suarez.coder.ci.LogFilter.extract(text), repo,
            if (settings.fixMemory) agent.ciKnowledge else null, round = st.failures
        )
        if (!agent.isEvolutionSession) history += ChatMessage.Notice(com.suarez.coder.ci.AutoHealer.notice(heal), sig)
        heal.hint?.let { h -> out = out.copy(content = out.content + "\n\n" + h) }
        var next: ModelRef? = cur
        when (heal.action) {
            com.suarez.coder.ci.AutoHealer.Action.RERUN -> {
                out = out.copy(content = out.content + "\n\nAutoHealer: geçici/flaky hata tespit edildi — workflow'u yeniden çalıştır.")
            }
            com.suarez.coder.ci.AutoHealer.Action.USE_KNOWN_FIX -> Unit
            com.suarez.coder.ci.AutoHealer.Action.ESCALATE -> {
                if (settings.untilGreen && !agent.isEvolutionSession) {
                    val up = Escalation.next(agent.escalationChain(), cur)
                    if (up != null) {
                        history += ChatMessage.Notice("CI hatası için daha güçlü modele geçiliyor (${up.model})", sig)
                        next = up
                    } else if (repeated && !ui.confirm("Aynı CI hatası tekrarlıyor", "Yükseltme zincirinin sonundasın (${cur.model}).\n\n$sig\n\nDevam edilsin mi?", "Devam et", "Durdur")) {
                        next = null
                    }
                }
            }
            com.suarez.coder.ci.AutoHealer.Action.ASK -> {
                if (!agent.isEvolutionSession &&
                    !ui.confirm("${st.failures}. kırmızı CI turu", "CI hâlâ başarısız.\n\n$sig\n\nDüzeltmeye devam edilsin mi?", "Devam et", "Durdur")
                ) next = null
            }
        }
        if (next != null && heal.action != com.suarez.coder.ci.AutoHealer.Action.ASK &&
            st.failures >= settings.ciMaxRounds && !agent.isEvolutionSession &&
            !ui.confirm("${st.failures}. kırmızı CI turu", "CI hâlâ başarısız.\n\n$sig\n\nDüzeltmeye devam edilsin mi?", "Devam et", "Durdur")
        ) next = null
        ui.update()
        return out to next
    }
}
