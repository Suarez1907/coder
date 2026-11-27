package com.suarez.coder.evolution

import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.agent.ArchitectEditor
import com.suarez.coder.agent.PlanFile
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.GitHub
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tier 3: a minimal fix to Coder's own code, test first, on an isolated evo/ branch, verified by CI, offered as a
 * pull request. Every role runs on the evolution agent (one isolated model). It never merges: the user decides in
 * Settings, Öz-Gelişim.
 */
class SelfPrOrchestrator(private val agent: Agent, private val onStatus: (String) -> Unit = {}) {

    data class Outcome(val pr: PendingSelfPr?, val summary: String)

    private val repo = EvolutionGuard.SELF_REPO
    private val gh: GitHub get() = agent.tools.github
    private val silent = object : AgentUi {
        override fun update() {}
        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String) = false
        override fun status(text: String?) { text?.let(onStatus) }
    }

    companion object {
        const val PLAN_SYSTEM = "You plan a minimal, single-purpose fix to the Android app Coder (Kotlin, Jetpack Compose). " +
            "Reply with ONE JSON object only: {\"title\": \"...\", \"root_cause\": \"...\", \"change_summary\": \"...\", " +
            "\"test\": {\"path\": \"app/src/test/java/com/suarez/coder/....kt\", \"action\": \"create|modify\", \"instructions\": \"...\"}, " +
            "\"files\": [{\"path\": \"app/src/main/java/...\", \"action\": \"modify\", \"instructions\": \"...\"}]}. " +
            "The test must fail before the fix and pass after it (plain JUnit, no network). At most 3 source files. " +
            "Instructions are a technical contract (functions, signatures, steps), not code. Never touch credentials, signing, " +
            "budgets, build files, CI workflows or the evolution engine."

        fun branchName(topic: String, now: Long = System.currentTimeMillis()): String =
            EvolutionGuard.BRANCH_PREFIX + EvolutionGuard.normalizeTopic(topic).take(30).ifBlank { "fix" } + "-" +
                SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date(now))

        data class SelfPlan(val title: String, val rootCause: String, val summary: String, val files: List<PlanFile>)

        /** Parses and checks the plan; the test file comes first so it is written before the fix. */
        fun parsePlan(text: String): Pair<SelfPlan?, String?> {
            val o = ReflectorEngine.extractJson(text) ?: return null to "Plan JSON değil."
            fun file(f: JSONObject?) = f?.let { PlanFile(it.optString("path").trim().removePrefix("./"), it.optString("action", "modify").lowercase(), it.optString("instructions")) }
            val test = file(o.optJSONObject("test")) ?: return null to "Planda test yok."
            if (!test.path.startsWith("app/src/test/")) return null to "Test app/src/test altında olmalı: ${test.path}"
            val arr = o.optJSONArray("files")
            val files = (0 until (arr?.length() ?: 0)).mapNotNull { file(arr!!.optJSONObject(it)) }.filter { it.path.isNotBlank() }
            if (files.isEmpty()) return null to "Planda kaynak dosya yok."
            val all = listOf(test) + files
            EvolutionGuard.checkPlan(all.map { it.path }, o.toString())?.let { return null to it }
            if (all.any { it.action == "delete" }) return null to "Self-PR dosya silemez."
            return SelfPlan(o.optString("title").ifBlank { "Coder düzeltmesi" }, o.optString("root_cause"), o.optString("change_summary"), all) to null
        }

        fun prBody(p: SelfPlan, si: SelfImprovement, files: List<String>, ciRounds: Int, costUsd: Double, eval: String? = null): String = buildString {
            append("## Sorun\n").append(si.problem.ifBlank { si.title }).append("\n\n")
            append("## Kök neden\n").append(p.rootCause.ifBlank { "-" }).append("\n\n")
            append("## Yapılan değişiklik\n").append(p.summary.ifBlank { p.title }).append("\n\n")
            files.forEach { append("- `").append(it).append("`\n") }
            append("\n## Eklenen test\n`").append(p.files.first().path).append("`\n\n")
            if (!eval.isNullOrBlank()) append("## Golden Eval\n").append(eval).append("\n\n")
            append("## Doğrulama\nCI geçti (`./gradlew testDebugUnitTest`, GoldenEvalSuiteTest dahil)")
                .append(if (ciRounds > 0) " ($ciRounds düzeltme turundan sonra)" else "").append(".\n\n")
            append("## Maliyet\n$").append(String.format(Locale.US, "%.4f", costUsd)).append(" (öz-gelişim modeli)\n\n")
            append("_Coder öz-gelişim hattı tarafından açıldı. Otomatik merge edilmez; Ayarlar > Öz-Gelişim'den onaylanır._\n")
        }
    }

    suspend fun run(si: SelfImprovement, cluster: ErrorCluster, costSoFar: () -> Double): Outcome {
        if (cluster.status != ClusterStatus.READY_FOR_PR) {
            return Outcome(null, "Küme ${cluster.clusterKey} hazır değil; tek seans sinyaliyle PR açılmaz.")
        }
        val base = gh.defaultBranch(repo)
        val (evalPath, _) = EvalGate.fileFor(cluster.clusterKey)
        val existingEval = runCatching { gh.readFileRaw(repo, evalPath, base) }.getOrNull()
        val prepared = EvalGate.prepare(cluster, existingEval)
            ?: return Outcome(null, if (EvalGate.caseFrom(cluster) == null)
                "Kümede gerçek dosya parçası yok; PR açılmadı."
            else "Golden eval geriledi; PR açılmadı.")
        val files = si.files.map { it.trim().removePrefix("./") }.filter { !EvolutionGuard.isProtected(it) }.take(EvolutionGuard.MAX_PR_FILES)
        if (files.isEmpty()) return Outcome(null, "Aday dosyalar korumalı veya boş; PR açılmadı.")
        onStatus("Coder dosyaları okunuyor")
        val sources = files.mapNotNull { p -> gh.readFileRaw(repo, p, base)?.let { p to it } }
        if (sources.isEmpty()) return Outcome(null, "Aday dosyalar bulunamadı: ${files.joinToString()}")

        // 1. Plan (evolution model as architect).
        onStatus("Düzeltme planlanıyor")
        val local = ArrayList<ChatMessage>()
        val prompt = buildString {
            append("PROBLEM\n").append(si.title).append("\n").append(si.problem).append("\n\nFILES (current)\n")
            for ((p, text) in sources) append("### ").append(p).append("\n```kotlin\n").append(text.take(40_000)).append("\n```\n")
        }
        val reply = agent.callModel(local, listOf(ChatMessage.User(prompt)), agent.architectRef(), "architect", PLAN_SYSTEM,
            emptyList(), silent, useCache = false, maxOut = 3_000)
        val (plan, why) = parsePlan(reply.text)
        if (plan == null) return Outcome(null, "Plan reddedildi: $why")

        // 2. Isolated branch from the default branch.
        val branch = branchName(si.topic)
        onStatus("Dal açılıyor: $branch")
        gh.createBranch(repo, branch, base)
        val editor = ArchitectEditor(agent)
        val text = editor.planTextFor("${plan.title}\nRoot cause: ${plan.rootCause}", "Write the failing test first, then the minimal fix.", plan.files)
        val originals = HashMap<String, String?>()
        val state = HashMap<String, String?>()
        try {
            // 3. Test first, then the fix (one atomic commit per round).
            var feedback: String? = null
            var rounds = 0
            while (true) {
                onStatus(if (feedback == null) "Test ve düzeltme yazılıyor" else "CI hatası düzeltiliyor (tur $rounds)")
                val planText = if (feedback == null) text else "$text\n\nCI FAILED. Fix these errors:\n${feedback.take(6_000)}"
                val o = editor.editFiles(local, silent, planText, plan.files, repo, branch, null, originals, state, StringBuilder())
                if (!o.ok) { cleanup(branch); return Outcome(null, "Uygulayıcı düzeltmeyi uygulayamadı: ${o.text.take(300)}") }
                val (changes, _) = editor.changesFor(plan.files.map { it.path to false }, originals, state)
                if (changes.isEmpty() && feedback == null) { cleanup(branch); return Outcome(null, "Değişiklik üretilmedi.") }
                if (changes.isNotEmpty()) {
                    val evalChange = com.suarez.coder.tools.GitHub.FileChange(prepared.path, prepared.json, false)
                    val withEval = if (changes.none { it.path == prepared.path }) changes + evalChange else changes
                    gh.commitFiles(repo, branch, if (feedback == null) "evo: ${plan.title}" else "evo: CI düzeltmesi ($rounds)", withEval, base)
                    for (c in withEval) if (c.content != null) originals[c.path] = c.content
                }
                // 4. CI on the branch.
                onStatus("CI bekleniyor")
                val ci = agent.tools.execute(ToolCall("evo_ci", "ci_wait", JSONObject().put("repo", repo).put("ref", branch).put("timeout_minutes", 30)),
                    readOnly = false, allowOps = true)
                val failed = ci.contains("\nHatalar (")
                val passed = !failed && ci.contains(": success")
                if (passed) {
                    // 5. Pull request (never merged here).
                    val changed = plan.files.map { it.path }
                    val cost = costSoFar()
                    val changedWithEval = (changed + prepared.path).distinct()
                    val card = prepared.card
                    val res = gh.createPullRequest(repo, "evo: ${plan.title}", branch, base, prBody(plan, si, changedWithEval, rounds, cost, card.line()))
                    val number = Regex("#(\\d+)").find(res)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val url = Regex("https://\\S+").find(res)?.value ?: "https://github.com/$repo/pulls"
                    return Outcome(PendingSelfPr(number, plan.title, url, branch, plan.rootCause.ifBlank { si.problem }.take(400), changedWithEval,
                        ciPassed = true, costUsd = cost, topic = si.topic, createdAt = System.currentTimeMillis(),
                        evalPassed = card.passed, evalTotal = card.total, evalAdded = card.added, evalRegressions = card.regressions,
                        clusterKey = cluster.clusterKey), "PR #$number açıldı. ${card.line()}")
                }
                if (!failed || rounds >= EvolutionGuard.CI_FIX_ROUNDS) {
                    cleanup(branch)
                    return Outcome(null, if (failed) "CI ${rounds + 1} denemede geçmedi; dal silindi." else "CI sonucu alınamadı; dal silindi.")
                }
                rounds++
                feedback = ci
            }
        } catch (e: Exception) {
            cleanup(branch)
            throw e
        }
    }

    private suspend fun cleanup(branch: String) { runCatching { gh.deleteBranch(repo, branch) } }
}
