package com.suarez.coder.agent

import com.suarez.coder.diff.SearchReplace
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.GitHub

/**
 * Two-phase mode: an expensive ARCHITECT plans with read-only tools, a cheap EDITOR turns the plan into
 * SEARCH/REPLACE blocks. After two failed editor attempts the architect model takes over that step.
 */
class ArchitectEditor(private val agent: Agent) {
    private data class PlanFile(val path: String, val action: String, val instructions: String)

    private data class Outcome(val ok: Boolean, val text: String)

    companion object {
        const val EDITOR_ATTEMPTS = 2
        const val GROUP_CHARS = 100_000
    }

    suspend fun run(history: MutableList<ChatMessage>, ui: AgentUi, ctx: String?) {
        val map = repoMap(history, ui)
        val plan = agent.agentLoop(
            history, ui, agent.architectRef(), "architect", agent.architectSystem(ctx, map),
            agent.tools.readOnlySpecs, readOnly = true, stopTool = "submit_plan",
            maxOut = agent.settings.maxOutArchitect,
            maxReads = if (map != null) Agent.MAX_ARCHITECT_READS else null,
        ) ?: return

        val outcome = try {
            implement(history, ui, plan)
        } catch (e: StopException) {
            Outcome(false, e.message ?: "Durduruldu")
        }
        history += ChatMessage.ToolResult(plan.id, plan.name, outcome.text, isError = !outcome.ok)
        history += ChatMessage.Assistant(
            (if (outcome.ok) "✅ Plan uygulandı.\n\n" else "⚠️ Plan uygulanamadı.\n\n") + outcome.text,
            model = "coder",
        )
        ui.update()
    }

    /** Repo map for the architect, or null when disabled/unavailable (the architect then reads files with tools). */
    private suspend fun repoMap(history: MutableList<ChatMessage>, ui: AgentUi): String? {
        if (!agent.settings.repoMapForArchitect) return null
        val repo = agent.context.repo.takeIf { it.isNotBlank() }?.let { agent.tools.normalizeRepo(it) } ?: return null
        return try {
            val r = kotlinx.coroutines.withTimeout(45_000) { agent.repoMaps.get(repo, agent.context.ref) }
            val tokens = r.map.length / 4
            history += ChatMessage.Notice(
                "🗺 Repo haritası: ${r.files} dosya, ~${com.suarez.coder.cost.Pricing.formatTokens(tokens.toLong())} token" +
                    (if (r.fromCache) " (önbellekten, ${r.sha.take(7)})" else " (oluşturuldu, ${r.sha.take(7)})"),
                r.map.take(20_000),
            )
            ui.update()
            r.map
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                history += ChatMessage.Notice("Repo haritası zamanında hazırlanamadı; Mimar dosyaları araçlarla okuyacak.")
                null
            } else throw e
        } catch (e: Exception) {
            history += ChatMessage.Notice("Repo haritası oluşturulamadı (${e.message?.take(100)}); Mimar dosyaları araçlarla okuyacak.")
            null
        }
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

        // Load the current contents of the files the editor will touch.
        val originals = HashMap<String, String?>()
        for (f in files.filter { it.action != "delete" }) {
            originals[f.path] = agent.tools.readForEdit(repo, branch, base, f.path)
        }
        val state = HashMap(originals)
        val editable = files.filter { it.action != "delete" }
        val text = planText(a, files)

        // Group files so a single editor request stays reasonably small.
        val groups = ArrayList<MutableList<PlanFile>>()
        var size = 0
        for (f in editable) {
            val len = originals[f.path]?.length ?: 0
            if (groups.isEmpty() || size + len > GROUP_CHARS) { groups += mutableListOf<PlanFile>(); size = 0 }
            groups.last() += f; size += len
        }
        val editorOutputs = StringBuilder()
        for (group in groups) {
            val ok = editGroup(history, ui, text, group, state, editorOutputs)
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
        agent.context.invalidate()
        return Outcome(true, result + "\n" + summary)
    }

    /** Runs up to two editor attempts, then one architect attempt, on one group of files. */
    private suspend fun editGroup(
        history: MutableList<ChatMessage>,
        ui: AgentUi,
        planText: String,
        group: List<PlanFile>,
        state: MutableMap<String, String?>,
        outputs: StringBuilder,
    ): Outcome {
        val paths = group.map { it.path }.toSet()
        var pending = group
        var feedback: String? = null
        for (attempt in 1..EDITOR_ATTEMPTS + 1) {
            val useArchitect = attempt > EDITOR_ATTEMPTS
            val ref = if (useArchitect) agent.architectRef() else agent.editorRef()
            if (useArchitect) {
                history += ChatMessage.Notice("⤴️ Uygulayıcı model $EDITOR_ATTEMPTS kez başarısız oldu; bu adım Mimar modele (${ref.model}) devrediliyor.")
                ui.update()
            }
            val prompt = buildString {
                append("PLAN:\n").append(planText).append("\n\nFILES TO EDIT (current content):\n")
                for (f in pending) {
                    val c = state[f.path]
                    append("\n").append(f.path).append(if (c == null) " (new file)\n" else "\n```\n$c\n```\n")
                }
                if (feedback != null) append("\nPREVIOUS ATTEMPT FAILED:\n").append(feedback)
                    .append("\nThe content above already includes the blocks that did apply. Output blocks only for the remaining changes.\n")
            }
            val role = if (useArchitect) "architect" else "editor"
            val reply = agent.callModel(history, listOf(ChatMessage.User(prompt)), ref, role, agent.editorSystem, emptyList(), ui,
                useCache = false, maxOut = agent.settings.maxOutEditor)
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

            val label = Agent.roleLabel(role)
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
        return Outcome(false, "Değişiklikler ${EDITOR_ATTEMPTS + 1} denemede uygulanamadı, commit yapılmadı.\n\n$feedback")
    }
}
