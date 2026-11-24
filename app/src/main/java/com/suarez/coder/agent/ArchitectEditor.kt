package com.suarez.coder.agent

import com.suarez.coder.diff.SearchReplace
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

        if (!agent.settings.autoApprove) {
            val ok = ui.confirm(
                "Commit onayı",
                "$repo@$branch\n$message\n\n$summary\n" + editorOutputs.toString().take(15_000),
                "Commit et", "Reddet",
            )
            if (!ok) return Outcome(false, "Kullanıcı commit'i reddetti. Değişiklikler yazılmadı.")
        }
        agent.tools.beforeWrite?.invoke(repo, branch)
        val result = agent.tools.github.commitFiles(repo, branch, message, changes, base)
        agent.tools.onRepoChanged()
        // Keep the applied blocks in the conversation so later turns know the new code without re-reading it.
        val applied = editorOutputs.toString().take(APPLIED_IN_RESULT_CHARS)
        return Outcome(true, result + "\n" + summary + "\nUYGULANAN DEĞİŞİKLİKLER:\n" + applied)
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
            val ok = editGroup(history, ui, planText, group, originals, state, outputs)
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

    private suspend fun editGroup(
        history: MutableList<ChatMessage>,
        ui: AgentUi,
        planText: String,
        group: List<PlanFile>,
        originals: Map<String, String?>,
        state: MutableMap<String, String?>,
        outputs: StringBuilder,
    ): Outcome {
        val paths = group.map { it.path }.toSet()
        // Executor-only self-repair: never the architect. The last attempts may use the (executor-class) escalation model.
        val ladder = executorLadder(agent.settings.maxExecutorRetries, agent.editorRef(), agent.escalationChain(),
            early = agent.earlyEscalation(agent.editorRef().model))
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
            val reply = agent.callModel(history, convo, step.ref, step.role, agent.editorSystem, emptyList(), ui,
                useCache = false, maxOut = agent.settings.maxOutEditor,
                thinking = when {
                    step.role == "architect" -> agent.architectThinking()
                    // Adaptive budget: the first try is cheap; repairs think at the configured level.
                    agent.settings.adaptiveBudget && attempt == 0 && agent.settings.thinkingEditor in setOf("medium", "high", "xhigh") -> "low"
                    else -> agent.settings.thinkingEditor
                })
            convo += reply
            outputs.append(reply.text).append("\n\n")

            val parsed = SearchReplace.parse(reply.text)
            val problems = ArrayList<String>()
            problems += parsed.errors
            val touched = HashSet<String>()
            val failedPaths = HashSet<String>()
            for ((path, blocks) in parsed.blocks.groupBy { it.path.trim().removePrefix("./") }) {
                if (path !in paths) { problems += "$path planda yok; atlandı."; continue }
                val res = SearchReplace.applyToFile(state[path], blocks)
                state[path] = if (res.applied.isEmpty() && state[path] == null) null else res.content
                if (res.applied.isNotEmpty()) touched += path
                if (res.failures.isNotEmpty()) {
                    failedPaths += path
                    problems += SearchReplace.describeFailures(res.failures)
                }
            }
            val untouched = pending.filter { it.path !in touched && it.path !in failedPaths }.map { it.path }
            if (untouched.isNotEmpty() && parsed.blocks.isEmpty()) problems += "Hiç geçerli SEARCH/REPLACE bloğu üretilmedi (biçim bozuk)."
            else if (untouched.isNotEmpty()) problems += "Şu dosyalar için blok yok: ${untouched.joinToString()}"
            // Cheap local checks catch broken edits before they cost a CI round trip.
            val invalid = validate(touched, originals, state)
            invalid.forEach { (p, why) -> problems += "Yerel kontrol, $p: $why"; failedPaths += p }

            val label = Agent.roleLabel(step.role)
            history += ChatMessage.Notice(
                "✏️ $label: ${parsed.blocks.size} blok, ${touched.size} dosya" + if (problems.isNotEmpty()) " (${problems.size} sorun)" else "",
                reply.text,
                isError = problems.isNotEmpty(),
            )
            ui.update()
            if (problems.isEmpty()) return Outcome(true, "")
            feedback = problems.joinToString("\n")
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
