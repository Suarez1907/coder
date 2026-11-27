package com.suarez.coder.agent

import kotlinx.coroutines.async

import com.suarez.coder.diff.SearchReplace
import com.suarez.coder.harness.OscillationBreaker
import com.suarez.coder.harness.PreFlightValidator
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.Args
import com.suarez.coder.tools.GitHub
import org.json.JSONObject
import com.suarez.coder.validate.CodeValidator

/**
 * Two-phase mode: an expensive ARCHITECT plans with read-only tools (on a cacheable stable context incl. the repo map),
 * a cheap EDITOR turns the plan into SEARCH/REPLACE blocks.
 * Editor failures climb a price ladder: editor → editor again (appended to the same conversation, so the large file
 * contents come from cache) → a mid-priced escalation model → the architect model (after asking, as that is the
 * third consecutive failure).
 */
data class PlanFile(val path: String, val action: String, val instructions: String)

data class ParsedPlan(
    val repo: String,
    val branch: String,
    val base: String?,
    val message: String,
    val notes: String,
    val files: List<PlanFile>,
    /** Why the plan cannot be used (sent back to the architect), or null. */
    val error: String? = null,
)

class ArchitectEditor(private val agent: Agent) {

    /** [repairable]: the executor gave up after its retries (the user may retry, guide it or ask the architect). */
    data class Outcome(val ok: Boolean, val text: String, val repairable: Boolean = false)

    companion object {
        const val GROUP_CHARS = 100_000
        const val APPLIED_IN_RESULT_CHARS = 4_000
        const val MAX_EXECUTOR_RETRIES = 5
        /** Plan agreement below this asks the user before editing. */
        const val UNCERTAIN_BELOW = 0.34
        /** Above this size only the region around the failed SEARCH is re-sent after a failure. */
        const val REREAD_FULL_CHARS = 30_000

        /**
         * Attempts for one file group: all executor-class (editor, then the escalation model for the last two when one is
         * set). The architect is never part of it.
         */
        fun executorLadder(max: Int, editor: ModelRef, escalation: ModelRef?, early: Boolean = false): List<Step> =
            executorLadder(max, editor, listOfNotNull(escalation), early)

        /** Same, climbing the user's ordered model chain. */
        fun executorLadder(max: Int, editor: ModelRef, chain: List<ModelRef>, early: Boolean = false): List<Step> =
            Escalation.ladder(max, editor, chain, early).map { Step(it.ref, it.role) }

        /** The failing files as they are now (re-read), so the next attempt fixes the root cause instead of guessing. */
        fun repairPrompt(feedback: String, paths: Set<String>, state: Map<String, String?>): String = buildString {
            append("Some changes failed:\n").append(feedback).append('\n')
            append("Blocks that applied are already in the files. Re-read the CURRENT text below, find the root cause, and return ")
            append("corrected SEARCH/REPLACE blocks only for the failed or missing changes. Never repeat a failed SEARCH.\n")
            for (p in paths.sorted()) {
                val text = state[p]
                append("\nCURRENT ").append(p)
                if (text == null) { append(" (file does not exist yet)\n"); continue }
                if (text.length <= REREAD_FULL_CHARS) append(":\n```\n").append(text).append("\n```\n")
                else {
                    val lines = text.split("\n")
                    val anchor = Regex("SEARCH başı:\\n(.+)").findAll(feedback).map { it.groupValues[1].trim() }.firstOrNull { it.isNotBlank() }
                    val idx = anchor?.let { a -> lines.indexOfFirst { it.trim() == a || it.contains(a) } }?.takeIf { it >= 0 } ?: 0
                    val from = (idx - 80).coerceAtLeast(0); val to = (idx + 120).coerceAtMost(lines.size)
                    append(" (lines ${from + 1}-$to of ${lines.size}):\n```\n").append(lines.subList(from, to).joinToString("\n")).append("\n```\n")
                }
            }
        }

        const val PLAN_FORMAT = "submit_plan biçimi: {\"repo\":\"sahip/isim\",\"branch\":\"feat/x\",\"commit_message\":\"...\"," +
            "\"files\":[{\"path\":\"app/src/main/java/.../A.kt\",\"action\":\"modify\",\"instructions\":\"1. ...\"}]} — " +
            "'files' dizisinin her öğesi path, action (modify/create/delete) ve instructions alanları olan bir NESNE olmalı."

        /** Reads the architect's plan leniently; returns an error message for the architect when it is not usable. */
        fun parsePlan(a: JSONObject, defaultRepo: String): ParsedPlan {
            val repo = a.optString("repo").ifBlank { defaultRepo }.trim()
                .removePrefix("https://github.com/").trim('/').removeSuffix(".git")
            val branch = a.optString("branch").trim()
            val base = a.optString("base_branch").trim().takeIf { it.isNotBlank() && it != "null" }
            val message = a.optString("commit_message").ifBlank { "Coder: değişiklikler" }
            val notes = a.optString("notes").takeIf { it != "null" } ?: ""
            fun fail(why: String) = ParsedPlan(repo, branch, base, message, notes, emptyList(), "$why $PLAN_FORMAT")
            if (repo.isBlank()) return fail("Planda 'repo' yok.")
            if (branch.isBlank()) return fail("Planda 'branch' yok.")
            val els = Args.elements(a, "files") ?: return fail("Planda 'files' yok.")
            val files = ArrayList<PlanFile>()
            for (e in els) {
                val obj = when (e) {
                    is JSONObject -> e
                    is String -> e.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
                    else -> null
                }
                val f = if (obj != null) {
                    val path = obj.optString("path").ifBlank { obj.optString("file").ifBlank { obj.optString("filename") } }
                    val action = obj.optString("action", "modify").lowercase().let { if (it in setOf("modify", "create", "delete")) it else "modify" }
                    val instr = obj.optString("instructions").ifBlank { obj.optString("changes").ifBlank { obj.optString("description") } }
                    PlanFile(path.trim().removePrefix("./").removePrefix("/"), action, instr)
                } else {
                    // "path: instructions" written as plain text
                    val s = e.toString().trim()
                    val idx = s.indexOf(':')
                    if (idx > 0 && Args.looksLikePath(s.substring(0, idx).trim())) {
                        PlanFile(s.substring(0, idx).trim().removePrefix("./").removePrefix("/"), "modify", s.substring(idx + 1).trim())
                    } else PlanFile(s.removePrefix("./").removePrefix("/"), "modify", "")
                }
                if (!Args.looksLikePath(f.path)) return fail("'${f.path.take(80)}' bir dosya yolu değil.")
                if (f.action != "delete" && f.instructions.isBlank() && notes.isBlank()) return fail("${f.path} için talimat (instructions) yok.")
                files += f
            }
            if (files.isEmpty()) return fail("Plan boş.")
            return ParsedPlan(repo, branch, base, message, notes, files.distinctBy { it.path })
        }
    }

    suspend fun run(history: MutableList<ChatMessage>, ui: AgentUi) {
        val outcome0 = agent.architectTurns(
            history, ui, "architect", agent.architectSystem(agent.architectHasMap, singleShot = true), agent.tools.singleShotSpecs,
            "submit_plan",
        ) { call -> parsePlan(call.args, agent.settings.defaultRepo).error }
        val plan = when (outcome0) {
            is ArchOutcome.Answered -> return
            is ArchOutcome.Delegated -> { agent.runDelegated(history, ui, outcome0.call, outcome0.instructions); return }
            is ArchOutcome.Unusable -> {
                history += ChatMessage.Assistant("⚠️ Mimar ${Agent.MAX_ARCHITECT_CALLS} çağrıda kullanılabilir bir plan vermedi; değişiklik yapılmadı.\n\n${outcome0.why.take(400)}", model = "coder")
                return
            }
            is ArchOutcome.Submitted -> outcome0.call
        }
        val parsed = agent.critiquePlan(history, ui, parsePlan(plan.args, agent.settings.defaultRepo))
        // Plan uncertainty: when independent guesses of the files disagree with the plan, the request is probably ambiguous.
        agent.foresight.planUncertainty(history, ui, parsed)?.let { (agreement, guesses) ->
            val pct = (agreement * 100).toInt()
            val planned = parsed.files.map { it.path }.toSet()
            val missing = PlanUncertainty.missing(planned, guesses)
            val detail = "Mimar: ${planned.joinToString()}\n" + guesses.mapIndexed { i, g -> "Bağımsız tahmin ${i + 1}: ${g.joinToString()}" }.joinToString("\n") +
                (if (missing.isNotEmpty()) "\nTahminlerin çoğunun söylediği ama planda olmayan: ${missing.joinToString()}" else "")
            if (agreement >= UNCERTAIN_BELOW) {
                history += ChatMessage.Notice("Plan belirsizliği düşük (uyum %$pct)", detail)
            } else {
                history += ChatMessage.Notice("Plan belirsizliği YÜKSEK (uyum %$pct)", detail, isError = true)
                ui.update()
                val go = agent.unattended || ui.confirm("Plan belirsiz",
                    "Bu isteğe dokunulacak dosyalar konusunda bağımsız tahminler Mimar'la uyuşmuyor (uyum %$pct):\n\n$detail\n\n" +
                        "İstek birden fazla şekilde anlaşılabilir. Plana göre devam edilsin mi?", "Devam et", "Dur, netleştirelim")
                if (!go) {
                    history += ChatMessage.ToolResult(plan.id, plan.name, "Kullanıcı belirsiz planı durdurdu; netleştirme bekleniyor.", isError = true)
                    history += ChatMessage.Assistant("${Agent.CLARIFY_MARK} Plan belirsiz (uyum %$pct). Hangi dosyalar/ekranlar değişmeli? " +
                        "Mimar ${planned.joinToString()} dedi; bağımsız tahminler ${guesses.flatten().toSet().joinToString()} dedi.", model = "coder")
                    ui.update()
                    return
                }
            }
            ui.update()
        }

        val outcome = try {
            implement(history, ui, parsed)
        } catch (e: StopException) {
            Outcome(false, e.message ?: "Durduruldu")
        }
        history += ChatMessage.ToolResult(plan.id, plan.name, outcome.text, isError = !outcome.ok)
        if (!outcome.ok && outcome.repairable) {
            pauseForUser(history, parsed, outcome)
        } else {
            history += ChatMessage.Assistant(
                (if (outcome.ok) "✅ Plan uygulandı.\n\n" else "⚠️ Plan uygulanamadı.\n\n") + outcome.text.substringBefore("\nUYGULANAN DEĞİŞİKLİKLER:"),
                model = "coder",
            )
        }
        ui.update()
    }

    /**
     * The executor ran out of attempts: never call the architect automatically. The plan is saved as a paused repair
     * card (Tekrar Dene / Yönlendirme Ekle / Mimar'a Sor) and the user decides.
     */
    private fun pauseForUser(history: MutableList<ChatMessage>, p: ParsedPlan, o: Outcome) {
        history += ChatMessage.Assistant("⏸ Uygulayıcı ${agent.settings.maxExecutorRetries} denemede değişiklikleri uygulayamadı. " +
            "Aşağıdaki karttan **Tekrar Dene**, **Yönlendirme Ekle** (açıklamaya ipucu yaz) veya **Mimar'a Sor** seçin.\n\n" +
            o.text.take(600), model = "coder")
        val hooks = agent.orchestratorHooks ?: return
        hooks.save(com.suarez.coder.orchestrator.OrchestratorTools.repairPlan(p.repo, p.branch, p.base, p.message,
            planText(p), p.files, o.text))
    }

    private fun planText(p: ParsedPlan): String = buildString {
        append("Commit: ").append(p.message).append('\n')
        if (p.notes.isNotBlank()) append("Notes: ").append(p.notes).append('\n')
        p.files.forEach { append("\n## ").append(it.path).append(" [").append(it.action).append("]\n").append(it.instructions).append('\n') }
    }

    private suspend fun implement(history: MutableList<ChatMessage>, ui: AgentUi, plan: ParsedPlan): Outcome {
        val repo = plan.repo
        val branch = plan.branch
        val base = plan.base
        val message = plan.message
        val files = plan.files

        history += ChatMessage.Notice("📋 Plan hazır: ${files.size} dosya → $repo@$branch", planText(plan))
        if (files.size > Agent.MAX_ARCHITECT_READS) {
            history += ChatMessage.Notice("ℹ️ Plan ${files.size} dosyaya dokunuyor (hedef 1-3). Uygulayıcıya giden içerik ve maliyet buna göre artar.")
        }
        ui.update()

        val originals = HashMap<String, String?>()
        val state = HashMap<String, String?>()
        val editorOutputs = StringBuilder()
        val edited = editFiles(history, ui, planText(plan), files, repo, branch, base, originals, state, editorOutputs)
        if (!edited.ok) return edited

        val (changes, summary) = changesFor(files.map { it.path to (it.action == "delete") }, originals, state)
        if (changes.isEmpty()) return Outcome(false, "Uygulayıcı hiçbir değişiklik üretmedi.")

        val staged = changes.filter { !it.delete && it.content != null }
            .map { PreFlightValidator.StagedFile(it.path, originals[it.path], it.content) }
        val pf = PreFlightValidator.validate(staged)
        if (!pf.ok) {
            val report = pf.report()
            history += ChatMessage.Notice(
                "Ön kontrol: ${pf.issues.size} sözdizimi hatası push öncesi yakalandı (\$0 CI)",
                report,
                isError = true,
            )
            ui.update()
            return Outcome(false, "Ön kontrol commit'i durdurdu, GitHub API çağrılmadı:\n$report", repairable = true)
        }

        if (!agent.settings.autoApprove) {
            val ok = ui.confirm(
                "Commit onayı",
                "$repo@$branch\n$message\n\n$summary\n" + editorOutputs.toString().take(15_000),
                "Commit et", "Reddet",
            )
            if (!ok) return Outcome(false, "Kullanıcı commit'i reddetti. Değişiklikler yazılmadı.")
        }
        // The same checks as direct commits: secrets, dependencies, self-review, quick check.
        agent.tools.commitNotes.clear()
        try {
            agent.tools.preCommit?.invoke(repo, branch, changes.map { Triple(it.path, originals[it.path], if (it.delete) null else it.content) })
        } catch (e: com.suarez.coder.tools.ToolException) {
            return Outcome(false, "Commit denetimi değişikliği durdurdu, commit yapılmadı:\n${e.message}", repairable = true)
        }
        agent.tools.beforeWrite?.invoke(repo, branch)
        val result = agent.tools.github.commitFiles(repo, branch, message, changes, base)
        agent.tools.afterCommit?.invoke(repo, result, changes.map { it.path }, changes.count { !it.delete && originals[it.path] == null })
        agent.tools.onRepoChanged()
        val notes = agent.tools.commitNotes.joinToString("\n").also { agent.tools.commitNotes.clear() }
        // Keep the applied blocks in the conversation so later turns know the new code without re-reading it.
        val applied = editorOutputs.toString().take(APPLIED_IN_RESULT_CHARS)
        return Outcome(true, result + "\n" + summary + (if (notes.isNotBlank()) "\n$notes" else "") + "\nUYGULANAN DEĞİŞİKLİKLER:\n" + applied)
    }

    /**
     * Reads the files not yet in [originals] and lets the editor ladder apply [planText] to them; results land in
     * [state] (shared with other subtasks in orchestrator mode). Nothing is committed here.
     */
    suspend fun editFiles(
        history: MutableList<ChatMessage>,
        ui: AgentUi,
        planText: String,
        files: List<PlanFile>,
        repo: String,
        branch: String,
        base: String?,
        originals: MutableMap<String, String?>,
        state: MutableMap<String, String?>,
        outputs: StringBuilder,
    ): Outcome {
        val editable = files.filter { it.action != "delete" }
        for (f in editable) {
            if (!originals.containsKey(f.path)) originals[f.path] = agent.tools.readForEdit(repo, branch, base, f.path)
            if (!state.containsKey(f.path)) state[f.path] = originals[f.path]
        }
        val groups = ArrayList<MutableList<PlanFile>>()
        var size = 0
        for (f in editable) {
            val len = state[f.path]?.length ?: 0
            if (groups.isEmpty() || size + len > GROUP_CHARS) { groups += mutableListOf<PlanFile>(); size = 0 }
            groups.last() += f; size += len
        }
        for (group in groups) {
            val ok = editGroup(history, ui, planText, group, originals, state, outputs, repo)
            if (!ok.ok) return ok
        }
        return Outcome(true, "")
    }

    /** File changes between [originals] and [state] for (path, delete) pairs, plus a "+add −rem" summary. */
    fun changesFor(paths: List<Pair<String, Boolean>>, originals: Map<String, String?>, state: Map<String, String?>): Pair<List<GitHub.FileChange>, String> {
        val changes = ArrayList<GitHub.FileChange>()
        val summary = StringBuilder()
        for ((path, delete) in paths.distinctBy { it.first }) {
            if (delete) { changes += GitHub.FileChange(path, null, true); summary.append("$path (silinecek)\n"); continue }
            val before = originals[path]; val after = state[path]
            if (after != null && after != before) {
                changes += GitHub.FileChange(path, after, false)
                val (add, rem) = SearchReplace.lineDelta(before, after)
                summary.append("$path +$add −$rem\n")
            }
        }
        return changes to summary.toString()
    }

    fun planTextFor(message: String, notes: String, files: List<PlanFile>) =
        planText(ParsedPlan("", "", null, message, notes, files))

    /** Validates edited files locally; returns problems keyed by path. */
    private fun validate(paths: Collection<String>, originals: Map<String, String?>, state: Map<String, String?>): Map<String, String> =
        paths.mapNotNull { p ->
            val after = state[p] ?: return@mapNotNull null
            CodeValidator.validate(p, originals[p], after)?.let { p to it }
        }.toMap()

    data class Step(val ref: ModelRef, val role: String)

    /** A reply applied to a copy of the files: what changed and what went wrong (nothing is written). */
    private data class Applied(val state: Map<String, String?>, val problems: List<String>, val touched: Set<String>, val failed: Set<String>, val blocks: Int)

    private fun applyReply(text: String, paths: Set<String>, pending: List<PlanFile>, originals: Map<String, String?>, start: Map<String, String?>): Applied {
        val st = HashMap(start)
        val parsed = SearchReplace.parse(text)
        val problems = ArrayList<String>(parsed.errors)
        val touched = HashSet<String>(); val failed = HashSet<String>()
        for ((path, blocks) in parsed.blocks.groupBy { it.path.trim().removePrefix("./") }) {
            if (path !in paths) { problems += "$path planda yok; atlandı."; continue }
            val res = SearchReplace.applyToFile(st[path], blocks)
            st[path] = if (res.applied.isEmpty() && st[path] == null) null else res.content
            if (res.applied.isNotEmpty()) touched += path
            if (res.failures.isNotEmpty()) { failed += path; problems += SearchReplace.describeFailures(res.failures) }
        }
        val untouched = pending.filter { it.path !in touched && it.path !in failed }.map { it.path }
        if (untouched.isNotEmpty() && parsed.blocks.isEmpty()) problems += "Hiç geçerli SEARCH/REPLACE bloğu üretilmedi (biçim bozuk)."
        else if (untouched.isNotEmpty()) problems += "Şu dosyalar için blok yok: ${untouched.joinToString()}"
        validate(touched, originals, st).forEach { (p, why) -> problems += "Yerel kontrol, $p: $why"; failed += p }
        return Applied(st, problems, touched, failed + untouched, parsed.blocks.size)
    }

    /**
     * Several candidate edits of the first attempt at once (different temperatures); each is applied to a copy,
     * checked locally and by the self-reviewer, and the best one (passing, reviewed, most agreed on, smallest) is kept.
     */
    private suspend fun bestCandidate(
        history: MutableList<ChatMessage>, ui: AgentUi, convo: List<ChatMessage>, step: Step, n: Int, thinking: String?,
        paths: Set<String>, pending: List<PlanFile>, originals: Map<String, String?>, state: Map<String, String?>,
    ): ChatMessage.Assistant {
        ui.status("$n aday düzenleme aynı anda üretiliyor")
        val replies = try {
            kotlinx.coroutines.coroutineScope {
                (0 until n).map { i ->
                    async {
                        runCatching {
                            agent.callModel(history, convo, step.ref, step.role, agent.editorSystem, emptyList(), ui, useCache = false,
                                maxOut = agent.settings.maxOutEditor, thinking = thinking, temperature = Candidates.temperature(i))
                        }.getOrNull()
                    }
                }.map { it.await() }
            }
        } finally { ui.status(null) }
        val ok = replies.withIndex().filter { it.value != null }.map { it.index to it.value!! }
        if (ok.isEmpty()) throw com.suarez.coder.llm.LlmException("Aday düzenlemelerin hiçbiri üretilemedi.")
        val applied = ok.map { (i, r) -> i to applyReply(r.text, paths, pending, originals, state) }
        val passing = applied.filter { it.second.problems.isEmpty() }
        val votes = passing.groupingBy { Candidates.fingerprint(it.second.state.filterKeys { k -> k in paths }) }.eachCount()
        // The self-reviewer only judges when there is a real choice between different passing edits.
        val review = HashMap<Int, Boolean>()
        if (votes.size > 1) for ((i, a) in passing) {
            review[i] = agent.guards.reviewVerdict(a.touched.map { p -> Triple(p, originals[p], a.state[p]) }) ?: true
        }
        val scored = applied.map { (i, a) ->
            Candidates.Scored(i, a.problems.isEmpty(), review[i],
                votes[Candidates.fingerprint(a.state.filterKeys { k -> k in paths })] ?: 0,
                a.touched.sumOf { p -> (a.state[p]?.length ?: 0) - (originals[p]?.length ?: 0) }.let { kotlin.math.abs(it) })
        }
        val best = Candidates.rank(scored).first()
        history += ChatMessage.Notice("Çok aday: ${ok.size} aday, ${passing.size} yerel kontrolden geçti; #${best.index + 1} seçildi" +
            (if (best.votes > 1) " (${best.votes} aday aynı sonuca vardı)" else ""),
            scored.joinToString("\n") { s -> "#${s.index + 1}: " + (if (s.passes) "geçti" else "kaldı") + (s.reviewOk?.let { if (it) ", öz-denetim tamam" else ", öz-denetim itiraz etti" } ?: "") + ", oy ${s.votes}" })
        ui.update()
        return ok.first { it.first == best.index }.second
    }

    private suspend fun editGroup(
        history: MutableList<ChatMessage>,
        ui: AgentUi,
        planText: String,
        group: List<PlanFile>,
        originals: Map<String, String?>,
        state: MutableMap<String, String?>,
        outputs: StringBuilder,
        repo: String = "",
    ): Outcome {
        val paths = group.map { it.path }.toSet()
        // Executor-only self-repair: never the architect. The last attempts may use the (executor-class) escalation model.
        val ladder = executorLadder(agent.settings.maxExecutorRetries, agent.editorRef(), agent.escalationChain(),
            early = agent.earlyEscalation(agent.editorRef().model))
        val breaker = OscillationBreaker(agent.architectRef())
        var pending = group
        var feedback: String? = null
        var failedNow: Set<String> = emptySet()
        var convo = ArrayList<ChatMessage>()
        for ((attempt, step) in ladder.withIndex()) {
            if (attempt > 0 && ladder[attempt - 1].ref.key != step.ref.key) {
                history += ChatMessage.Notice("⤴️ Onarım denemesi ${attempt + 1}: ${Agent.roleLabel(step.role)} modeli (${step.ref.model}).")
                ui.update()
            }
            if (convo.isNotEmpty()) {
                // Append-only repair turn: the error plus a fresh read of the failing files (the big first message stays cached).
                convo += ChatMessage.User(repairPrompt(feedback ?: "", failedNow.ifEmpty { pending.map { it.path }.toSet() }, state))
            } else {
                convo = arrayListOf(ChatMessage.User(buildPrompt(planText, pending, state, feedback)))
            }
            val thinking = when {
                step.role == "architect" -> agent.architectThinking()
                // Adaptive budget: the first try is cheap; repairs think at the configured level.
                agent.settings.adaptiveBudget && attempt == 0 && agent.settings.thinkingEditor in setOf("medium", "high", "xhigh") -> "low"
                else -> agent.settings.thinkingEditor
            }
            // Risky groups get several candidate edits on the first attempt (test-time scaling with cheap verifiers).
            val nCand = if (attempt == 0) agent.foresight.candidates(repo, group.map { it.path }, group.count { originals[it.path] == null }) else 1
            val reply = if (nCand > 1) bestCandidate(history, ui, convo, step, nCand, thinking, paths, pending, originals, state)
            else agent.callModel(history, convo, step.ref, step.role, agent.editorSystem, emptyList(), ui,
                useCache = false, maxOut = agent.settings.maxOutEditor, thinking = thinking)
            convo += reply
            outputs.append(reply.text).append("\n\n")

            val parsed = SearchReplace.parse(reply.text)
            val problems = ArrayList<String>()
            problems += parsed.errors
            val touched = HashSet<String>()
            val failedPaths = HashSet<String>()
            var tier2 = 0
            var tier3 = 0
            for ((path, blocks) in parsed.blocks.groupBy { it.path.trim().removePrefix("./") }) {
                if (path !in paths) { problems += "$path planda yok; atlandı."; continue }
                val res = SearchReplace.applyToFile(state[path], blocks)
                state[path] = if (res.applied.isEmpty() && state[path] == null) null else res.content
                if (res.applied.isNotEmpty()) {
                    touched += path
                    tier2 += res.applied.count { res.tiersUsed[it.index]?.name == "WHITESPACE_NORMALIZED" }
                    tier3 += res.applied.count { res.tiersUsed[it.index]?.name == "LINE_ANCHOR" }
                }
                if (res.failures.isNotEmpty()) {
                    failedPaths += path
                    problems += SearchReplace.describeFailures(res.failures)
                }
            }
            if (tier2 > 0 || tier3 > 0) agent.tools.onSearchReplaceTiers?.invoke(tier2, tier3)
            val untouched = pending.filter { it.path !in touched && it.path !in failedPaths }.map { it.path }
            if (untouched.isNotEmpty() && parsed.blocks.isEmpty()) problems += "Hiç geçerli SEARCH/REPLACE bloğu üretilmedi (biçim bozuk)."
            else if (untouched.isNotEmpty()) problems += "Şu dosyalar için blok yok: ${untouched.joinToString()}"
            // Cheap local checks catch broken edits before they cost a CI round trip.
            val invalid = validate(touched, originals, state)
            invalid.forEach { (p, why) -> problems += "Yerel kontrol, $p: $why"; failedPaths += p }
            val stagedTouched = touched.mapNotNull { p -> state[p]?.let { PreFlightValidator.StagedFile(p, originals[p], it) } }
            val pf = PreFlightValidator.validate(stagedTouched)
            if (!pf.ok) {
                pf.issues.forEach { issue ->
                    problems += "${issue.path}:${issue.line}: ${issue.reason}"
                    failedPaths += issue.path
                }
                history += ChatMessage.Notice(
                    "Ön kontrol: ${pf.issues.size} sözdizimi hatası yakalandı (\$0 CI)",
                    pf.report(),
                    isError = true,
                )
            }

            val label = Agent.roleLabel(step.role)
            history += ChatMessage.Notice(
                "$label: ${parsed.blocks.size} blok, ${touched.size} dosya" + if (problems.isNotEmpty()) " (${problems.size} sorun)" else "",
                reply.text,
                isError = problems.isNotEmpty(),
            )
            ui.update()
            if (problems.isEmpty()) return Outcome(true, "")
            feedback = problems.joinToString("\n")
            val verdict = breaker.recordFailure(feedback.hashCode().toString(), feedback, "Uygulayıcı hatası")
            if (verdict.action == OscillationBreaker.Action.ESCALATE_TO_ARCHITECT) {
                history += ChatMessage.Notice(verdict.userNotice ?: "Salınım kesici devreye girdi", isError = true)
                ui.update()
                return Outcome(false, verdict.internalMessage ?: "Salınım tespit edildi, mimara yükseltildi.", repairable = true)
            }
            pending = group.filter { it.path in failedPaths || it.path in untouched }
            failedNow = (failedPaths + untouched).toSet()
        }
        return Outcome(false, "Uygulayıcı ${ladder.size} denemede değişiklikleri uygulayamadı, commit yapılmadı.\n\nSon hata:\n$feedback", repairable = true)
    }

    private fun buildPrompt(planText: String, pending: List<PlanFile>, state: Map<String, String?>, feedback: String?) = buildString {
        append("PLAN:\n").append(planText).append("\n\nFILES TO EDIT (current content):\n")
        for (f in pending) {
            val c = state[f.path]
            append("\n").append(f.path).append(if (c == null) " (new file)\n" else "\n```\n$c\n```\n")
        }
        if (feedback != null) append("\nPREVIOUS ATTEMPT FAILED:\n").append(feedback)
            .append("\nThe content above already includes the blocks that did apply. Output blocks only for the remaining changes.\n")
    }
}
