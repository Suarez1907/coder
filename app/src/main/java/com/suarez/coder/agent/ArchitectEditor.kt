package com.suarez.coder.agent

import com.suarez.coder.diff.SearchReplace
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.GitHub
import com.suarez.coder.validate.CodeValidator

/**
 * Two-phase mode: an expensive ARCHITECT plans with read-only tools (on a cacheable stable context incl. the repo map),
 * a cheap EDITOR turns the plan into SEARCH/REPLACE blocks.
 * Editor failures climb a price ladder: editor → editor again (appended to the same conversation, so the large file
 * contents come from cache) → a mid-priced escalation model → the architect model (after asking, as that is the
 * third consecutive failure).
 */
class ArchitectEditor(private val agent: Agent) {
    private data class PlanFile(val path: String, val action: String, val instructions: String)

    private data class Outcome(val ok: Boolean, val text: String)

    companion object {
        const val GROUP_CHARS = 100_000
        const val APPLIED_IN_RESULT_CHARS = 4_000
    }

    suspend fun run(history: MutableList<ChatMessage>, ui: AgentUi) {
        val stable = agent.stableContext("architect", history, ui)
        val plan = agent.agentLoop(
            history, ui, agent.architectRef(), "architect", agent.architectSystem(agent.architectHasMap), "architect",
            agent.tools.architectSpecs, readOnly = true, stopTool = "submit_plan",
            maxOut = agent.settings.maxOutArchitect,
            maxReads = if (agent.architectHasMap && stable != null) Agent.MAX_ARCHITECT_READS else null,
            thinking = agent.settings.thinkingArchitect,
            allowOps = true,
        ) ?: return

        val outcome = try {
            implement(history, ui, plan)
        } catch (e: StopException) {
            Outcome(false, e.message ?: "Durduruldu")
        }
        history += ChatMessage.ToolResult(plan.id, plan.name, outcome.text, isError = !outcome.ok)
        history += ChatMessage.Assistant(
            (if (outcome.ok) "✅ Plan uygulandı.\n\n" else "⚠️ Plan uygulanamadı.\n\n") + outcome.text.substringBefore("\nUYGULANAN DEĞİŞİKLİKLER:"),
            model = "coder",
        )
        ui.update()
    }

    private fun planText(planArgs: org.json.JSONObject, files: List<PlanFile>): String = buildString {
        append("Commit: ").append(planArgs.optString("commit_message")).append('\n')
        planArgs.optString("notes").takeIf { it.isNotBlank() && it != "null" }?.let { append("Notes: ").append(it).append('\n') }
        files.forEach { append("\n## ").append(it.path).append(" [").append(it.action).append("]\n").append(it.instructions).append('\n') }
    }

    private suspend fun implement(history: MutableList<ChatMessage>, ui: AgentUi, plan: ToolCall): Outcome {
        val a = plan.args
        val repo = agent.tools.normalizeRepo(a.optString("repo").ifBlank { agent.settings.defaultRepo })
        if (repo.isBlank()) return Outcome(false, "Planda repo yok.")
        val branch = a.optString("branch").ifBlank { return Outcome(false, "Planda branch yok.") }
        val base = a.optString("base_branch").takeIf { it.isNotBlank() && it != "null" }
        val message = a.optString("commit_message").ifBlank { "Coder: değişiklikler" }
        val arr = a.optJSONArray("files") ?: return Outcome(false, "Planda dosya listesi yok.")
        val files = (0 until arr.length()).map {
            val f = arr.getJSONObject(it)
            PlanFile(f.getString("path").trim().removePrefix("/"), f.optString("action", "modify"), f.optString("instructions"))
        }
        if (files.isEmpty()) return Outcome(false, "Plan boş.")

        history += ChatMessage.Notice("📋 Plan hazır: ${files.size} dosya → $repo@$branch", planText(a, files))
        if (files.size > Agent.MAX_ARCHITECT_READS) {
            history += ChatMessage.Notice("ℹ️ Plan ${files.size} dosyaya dokunuyor (hedef 1-3). Uygulayıcıya giden içerik ve maliyet buna göre artar.")
        }
        ui.update()

        val originals = HashMap<String, String?>()
        for (f in files.filter { it.action != "delete" }) {
            originals[f.path] = agent.tools.readForEdit(repo, branch, base, f.path)
        }
        val state = HashMap(originals)
        val editable = files.filter { it.action != "delete" }
        val text = planText(a, files)

        val groups = ArrayList<MutableList<PlanFile>>()
        var size = 0
        for (f in editable) {
            val len = originals[f.path]?.length ?: 0
            if (groups.isEmpty() || size + len > GROUP_CHARS) { groups += mutableListOf<PlanFile>(); size = 0 }
            groups.last() += f; size += len
        }
        val editorOutputs = StringBuilder()
        for (group in groups) {
            val ok = editGroup(history, ui, text, group, originals, state, editorOutputs)
            if (!ok.ok) return ok
        }

        val changes = ArrayList<GitHub.FileChange>()
        val summary = StringBuilder()
        for (f in editable) {
            val before = originals[f.path]; val after = state[f.path]
            if (after != null && after != before) {
                changes += GitHub.FileChange(f.path, after, false)
                val (add, rem) = SearchReplace.lineDelta(before, after)
                summary.append("${f.path} +$add −$rem\n")
            }
        }
        files.filter { it.action == "delete" }.forEach {
            changes += GitHub.FileChange(it.path, null, true); summary.append("${it.path} (silinecek)\n")
        }
        if (changes.isEmpty()) return Outcome(false, "Uygulayıcı hiçbir değişiklik üretmedi.")

        if (!agent.settings.autoApprove) {
            val ok = ui.confirm(
                "Commit onayı",
                "$repo@$branch\n$message\n\n$summary\n" + editorOutputs.toString().take(15_000),
                "Commit et", "Reddet",
            )
            if (!ok) return Outcome(false, "Kullanıcı commit'i reddetti. Değişiklikler yazılmadı.")
        }
        val result = agent.tools.github.commitFiles(repo, branch, message, changes, base)
        agent.tools.onRepoChanged()
        // Keep the applied blocks in the conversation so later turns know the new code without re-reading it.
        val applied = editorOutputs.toString().take(APPLIED_IN_RESULT_CHARS)
        return Outcome(true, result + "\n" + summary + "\nUYGULANAN DEĞİŞİKLİKLER:\n" + applied)
    }

    /** Validates edited files locally; returns problems keyed by path. */
    private fun validate(paths: Collection<String>, originals: Map<String, String?>, state: Map<String, String?>): Map<String, String> =
        paths.mapNotNull { p ->
            val after = state[p] ?: return@mapNotNull null
            CodeValidator.validate(p, originals[p], after)?.let { p to it }
        }.toMap()

    private data class Step(val ref: ModelRef, val role: String, val appendToPrevious: Boolean)

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
        val ladder = buildList {
            add(Step(agent.editorRef(), "editor", false))
            add(Step(agent.editorRef(), "editor", true))
            agent.escalationRef()?.let { add(Step(it, "escalation", false)) }
            add(Step(agent.architectRef(), "architect", false))
        }
        var pending = group
        var feedback: String? = null
        var convo = ArrayList<ChatMessage>()
        for ((attempt, step) in ladder.withIndex()) {
            if (attempt >= Agent.MAX_CONSECUTIVE_FAILURES) {
                val ok = ui.confirm(
                    "${Agent.MAX_CONSECUTIVE_FAILURES} deneme başarısız",
                    "Değişiklikler $attempt denemede uygulanamadı.\n\nSon sorunlar:\n${feedback?.take(800)}\n\n" +
                        "Bu adımı en güçlü model (${step.ref.model}) ile bir kez daha denemek maliyeti artırır. Devam edilsin mi?",
                    "Devam et", "Durdur",
                )
                if (!ok) break
            }
            if (attempt > 0 && !step.appendToPrevious) {
                history += ChatMessage.Notice("⤴️ Bu adım ${Agent.roleLabel(step.role)} modeline (${step.ref.model}) devrediliyor.")
                ui.update()
            }
            if (step.appendToPrevious && convo.isNotEmpty()) {
                // Same conversation, only the short error report is new: the large first message is read from cache.
                convo += ChatMessage.User(
                    "Some blocks failed:\n$feedback\nBlocks that applied are already in the files. " +
                        "Return corrected SEARCH/REPLACE blocks only for the failed or missing changes, matching the current file text."
                )
            } else {
                convo = arrayListOf(ChatMessage.User(buildPrompt(planText, pending, state, feedback)))
            }
            val reply = agent.callModel(history, convo, step.ref, step.role, agent.editorSystem, emptyList(), ui,
                useCache = false, maxOut = agent.settings.maxOutEditor,
                thinking = if (step.role == "architect") agent.settings.thinkingArchitect else agent.settings.thinkingEditor)
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
        }
        return Outcome(false, "Değişiklikler uygulanamadı, commit yapılmadı.\n\n$feedback")
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
