package com.suarez.coder.orchestrator

import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.cloudtools.CloudToolRunner
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.tools.GitHub
import org.json.JSONObject
import java.io.File

/**
 * The evaluator of the full orchestrator. Agents grading their own diff praise it; this one checks the RUNNING app.
 * Web projects: it writes a Playwright script from the contract (the subtasks' acceptance criteria), runs it in
 * GitHub Actions against the app, and reads pass/fail per criterion plus console errors. Hard threshold: every
 * criterion passes and the console is clean. Android projects are judged by their CI (unit/screenshot tests).
 */
class Evaluator(private val agent: Agent) {
    /** The script is written once and reused on every branch this evaluator checks (two candidates, re-checks). */
    private var cachedScript: String? = null

    enum class Kind { WEB, ANDROID, NONE }

    data class Criterion(val name: String, val pass: Boolean, val evidence: String)

    data class Result(val criteria: List<Criterion>, val consoleErrors: List<String>, val note: String? = null, val outputMarker: String? = null,
                      /** Share of the screen that changed compared with the last passing run (0..1), if there was one. */
                      val visualChange: Double? = null) {
        /** Hard threshold: all criteria pass, no console errors, at least one criterion checked. */
        val passed get() = criteria.isNotEmpty() && criteria.all { it.pass } && consoleErrors.isEmpty()
        val summary get() = "${criteria.count { it.pass }}/${criteria.size} ölçüt geçti" + if (consoleErrors.isNotEmpty()) ", ${consoleErrors.size} konsol hatası" else ""

        /** What the reviewer gets to open fix tickets: only the failures, with their evidence. */
        fun report(): String = buildString {
            append("EVALUATOR (running app in a real browser): ").append(summary).append('\n')
            criteria.filter { !it.pass }.forEach { append("FAIL: ").append(it.name).append(" — ").append(it.evidence.take(400)).append('\n') }
            consoleErrors.take(10).forEach { append("CONSOLE: ").append(it.take(300)).append('\n') }
            visualChange?.takeIf { it >= VisualDiff.NOTICE }?.let {
                append("VISUAL: the main screen changed %${(it * 100).toInt()} compared with the last passing version; check it was intended.\n")
            }
            note?.let { append(it).append('\n') }
        }
    }

    companion object {
        const val RESULTS = "results.json"

        fun detect(paths: List<String>): Kind = when {
            paths.any { it.endsWith("AndroidManifest.xml") } && paths.any { it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") } -> Kind.ANDROID
            paths.any { it.substringAfterLast('/') == "index.html" } || paths.any { it == "package.json" } -> Kind.WEB
            else -> Kind.NONE
        }

        /** The folder to serve: the shallowest one with an index.html (built output folders are skipped). */
        fun appDir(paths: List<String>): String? = paths.filter { it.substringAfterLast('/') == "index.html" }
            .filter { p -> p.split('/').none { it in setOf("node_modules", "test", "tests", "docs", "coverage") } }
            .minByOrNull { it.count { c -> c == '/' } }?.substringBeforeLast('/', ".")?.ifBlank { "." }

        fun parse(json: String): Result? = runCatching {
            val o = JSONObject(json)
            val c = o.optJSONArray("criteria")
            val e = o.optJSONArray("consoleErrors")
            Result(
                (0 until (c?.length() ?: 0)).map { c!!.getJSONObject(it) }.map { Criterion(it.optString("name"), it.optBoolean("pass"), it.optString("evidence")) },
                (0 until (e?.length() ?: 0)).map { e!!.optString(it) }.filter { it.isNotBlank() },
            )
        }.getOrNull()

        /** The ```js block of a reply (or the whole text when it already is code). */
        fun extractCode(reply: String): String? {
            val m = Regex("```(?:js|javascript|mjs)?\\s*\\n([\\s\\S]*?)```").find(reply)
            val code = (m?.groupValues?.get(1) ?: reply).trim()
            return code.takeIf { it.contains("playwright") && it.contains("results.json") }
        }

        fun contract(plan: OrchestratorPlan): List<String> =
            plan.tasks.filter { !it.fix }.flatMap { t -> t.acceptance.ifEmpty { listOf(t.title) } }.distinct().take(15)

        val SCRIPT_SYSTEM = """
            You write ONE Node.js ES module that evaluates a web app in a real browser, like a careful QA tester.
            - import { chromium } from 'playwright'; import fs from 'fs'; import path from 'path'.
            - BASE_URL and CODER_OUTPUT_DIR come from process.env. Create CODER_OUTPUT_DIR if missing.
            - Collect console 'error' messages and 'pageerror' events for the whole run.
            - Open BASE_URL, then check EACH acceptance criterion the way a user would: click, type, press keys, wait for
              selectors or text (each wait at most 8 s; no fixed sleeps over 2 s; the whole run under 3 minutes).
            - Wrap every criterion in try/catch; a timeout or exception is a failed criterion with the message as evidence.
              Evidence must say what was seen (text, count, state), not just "ok".
            - Screenshots into CODER_OUTPUT_DIR: preview.png (main view after loading) and one per failed criterion.
            - Write CODER_OUTPUT_DIR/results.json = {"criteria":[{"name","pass","evidence"}],"consoleErrors":[...]}
              and ALWAYS exit with code 0: failures are data, not crashes. Close the browser in finally.
            Reply with the code only, in one ```js block.
        """.trimIndent()
    }

    /** Runs the evaluation for the plan's branch; null when the project is not a web app (CI judges it). */
    suspend fun evaluate(history: MutableList<ChatMessage>, ui: AgentUi, plan: OrchestratorPlan): Result? {
        val gh: GitHub = agent.tools.github
        val paths = gh.treeEntries(plan.repo, plan.branch)?.second?.map { it.path }.orEmpty()
        if (detect(paths) != Kind.WEB) return null
        val dir = appDir(paths) ?: return Result(emptyList(), emptyList(), "Statik index.html bulunamadı; değerlendirici atlandı.")
        val script = ".coder/eval/${plan.id}.mjs"
        if (gh.readFileRaw(plan.repo, script, plan.branch) == null) {
            cachedScript?.let { code ->
                gh.commitFiles(plan.repo, plan.branch, "coder: değerlendirme betiği", listOf(GitHub.FileChange(script, code, false)), plan.base)
            }
        }
        if (gh.readFileRaw(plan.repo, script, plan.branch) == null) {
            ui.status("Değerlendirici test betiğini yazıyor")
            val files = plan.tasks.flatMap { it.ownedFiles }.distinct().take(6)
                .mapNotNull { p -> gh.readFileRaw(plan.repo, p, plan.branch)?.let { "### $p\n${it.take(8_000)}" } }.joinToString("\n\n")
            val prompt = "APP ROOT (served at BASE_URL): $dir\n\nACCEPTANCE CRITERIA (the contract):\n" +
                contract(plan).joinToString("\n") { "- $it" } + "\n\nUSER REQUEST:\n${plan.request.take(2_000)}\n\nRELEVANT FILES:\n$files"
            // An executor-class model writes the test: never the architect, never the model that wrote the code alone.
            val reply = agent.callModel(history, listOf(ChatMessage.User(prompt)), agent.escalationRef() ?: agent.editorRef(), "reviewer",
                SCRIPT_SYSTEM, emptyList(), ui, useCache = false, maxOut = 6_000)
            val code = extractCode(reply.text) ?: return Result(emptyList(), emptyList(), "Değerlendirici geçerli bir test betiği yazamadı.")
            cachedScript = code
            gh.commitFiles(plan.repo, plan.branch, "coder: değerlendirme betiği", listOf(GitHub.FileChange(script, code, false)), plan.base)
        }
        ui.status("Uygulama tarayıcıda test ediliyor (GitHub Actions)")
        val runner = agent.tools.cloud ?: return Result(emptyList(), emptyList(), "Bulut araçları kullanılamıyor.")
        val out = runner.run(plan.repo, "evaluate", script, "coder-out/eval", plan.branch, null, mapOf("app_dir" to dir))
        val output = CloudToolRunner.outputs(out).firstOrNull()
            ?: return Result(emptyList(), emptyList(), "Değerlendirme çalışmadı:\n" + out.take(2_000))
        val json = output.files.firstOrNull { it.name == RESULTS }?.let { File(it.path).readText() }
        val r = json?.let { parse(it) } ?: return Result(emptyList(), emptyList(), "results.json yok veya bozuk.", CloudToolRunner.marker(output.tool, output.runId, output.dir))
        // Visual regression: compare the main screenshot with the last passing run of this repository.
        val visualDir = File(runner.outDir.parentFile, "visual").apply { mkdirs() }
        val baseline = File(visualDir, plan.repo.replace('/', '_') + ".png")
        val preview = output.preview
        val change = if (preview != null && baseline.isFile) VisualDiff.compare(baseline, preview) else null
        val result = r.copy(outputMarker = CloudToolRunner.marker(output.tool, output.runId, output.dir), visualChange = change)
        if (result.passed && preview != null) runCatching { preview.copyTo(baseline, overwrite = true) }
        return result
    }
}
