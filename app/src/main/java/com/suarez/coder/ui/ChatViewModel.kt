package com.suarez.coder.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import com.suarez.coder.apk.ApkDownloader
import com.suarez.coder.apk.ApkTarget
import com.suarez.coder.ci.CiWatchService
import com.suarez.coder.cost.CostBreakdown
import com.suarez.coder.cost.Ledger
import com.suarez.coder.cost.LedgerEntry
import com.suarez.coder.data.ProjectProfile
import com.suarez.coder.data.SessionMeta
import com.suarez.coder.data.SessionStore
import com.suarez.coder.tools.ToolRegistry
import com.suarez.coder.llm.Http
import com.suarez.coder.media.ImagePrep
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.AgentUi
import com.suarez.coder.agent.StopException
import com.suarez.coder.cost.Pricing
import com.suarez.coder.data.Provider
import com.suarez.coder.data.Settings
import com.suarez.coder.decision.Route
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.MessageJson
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.evolution.AdaptiveRouting
import com.suarez.coder.evolution.EvoTier
import com.suarez.coder.evolution.EvolutionEngine
import com.suarez.coder.evolution.EvolutionGuard
import com.suarez.coder.evolution.EvolutionLogEntry
import com.suarez.coder.evolution.EvolutionWorker
import com.suarez.coder.evolution.PendingSelfPr
import com.suarez.coder.evolution.PrState
import com.suarez.coder.evolution.SessionTraceRecorder
import com.suarez.coder.orchestrator.OrchestratorHooks
import com.suarez.coder.orchestrator.OrchestratorMode
import com.suarez.coder.orchestrator.OrchestratorPlan
import com.suarez.coder.orchestrator.OrchestratorStore
import com.suarez.coder.orchestrator.PlanState
import com.suarez.coder.llm.OpenAiCompatClient
import com.suarez.coder.llm.OpenRouterCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File

class PendingConfirm(
    val title: String,
    val body: String,
    val okText: String,
    val cancelText: String,
    val answer: CompletableDeferred<Boolean>,
)

/** State of an in-app APK download, keyed by the link it came from. */
sealed class DownloadState {
    data class Running(val percent: Int) : DownloadState()
    data class Ready(val path: String) : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    /** Agent of the chat on screen. A chat that keeps running in the background keeps its own agent. */
    var agent = Agent(settings, app.filesDir)
        private set
    private val sessionStore = SessionStore(File(app.filesDir, "chats"), legacyChat = File(app.filesDir, "chat.json"))
    private val ledger = Ledger(File(app.filesDir, "ledger.jsonl"))
    private val loggedKeys = HashSet<String>()
    private val planStore = OrchestratorStore(File(app.filesDir, "orchestrator"))
    val evoStore = EvolutionEngine.storeFor(app)

    /** Self-evolution screen state. */
    var evoPending by mutableStateOf<PendingSelfPr?>(null)
    var evoClusters by mutableStateOf<List<com.suarez.coder.evolution.ErrorCluster>>(emptyList())
    var evoRules by mutableStateOf<List<com.suarez.coder.evolution.ScopedRule>>(emptyList())
    var evoCtg by mutableStateOf<List<com.suarez.coder.evolution.CostToGreenScorer.Score>>(emptyList())
        private set
    var evoGeneral by mutableStateOf<List<com.suarez.coder.evolution.GeneralRule>>(emptyList())
        private set
    var evoAb by mutableStateOf<com.suarez.coder.evolution.StrategyAbOptimizer.Report?>(null)
        private set
    var evoSentinel by mutableStateOf<List<com.suarez.coder.evolution.SentinelReport>>(emptyList())
        private set
    var evoLog by mutableStateOf<List<EvolutionLogEntry>>(emptyList())
        private set
    var evoStatus by mutableStateOf<String?>(null)
        private set
    var evoBusy by mutableStateOf(false)
        private set
    /** Last trace of this app run, so a corrective follow-up message or the thumbs can be attached to it. */
    private var lastTraceId: String? = null
    /** Thumbs on the latest answer (1 / -1 / 0); reset by the next message. */
    var lastRating by mutableStateOf(0)
        private set
    /** True while the latest finished run has a trace that can be rated. */
    var canRate by mutableStateOf(false)
        private set
    val manager = com.suarez.coder.evolution.SelfManager(settings, evoStore)

    /** Orchestrator plan of the chat on screen (shown as a card), or null. */
    var activePlan by mutableStateOf<OrchestratorPlan?>(null)
        private set
    /** Set by "Duraklat": the run stops after the current subtask. */
    var pauseRequested by mutableStateOf(false)
        private set

    /** Id of the chat on screen. */
    var currentSessionId by mutableStateOf("")
        private set
    var sessions by mutableStateOf<List<SessionMeta>>(emptyList())
        private set
    var profiles by mutableStateOf<List<ProjectProfile>>(emptyList())
        private set

    val messages = mutableStateListOf<ChatMessage>()

    /** Prepared (resized JPEG) images waiting to be sent with the next message. */
    val attachments = mutableStateListOf<String>()
    /** Set when images would go to a model that cannot see them; the UI asks before sending. */
    var visionWarning by mutableStateOf<String?>(null)
        private set
    private var pendingSendText: String? = null

    val downloads = mutableStateMapOf<String, DownloadState>()
    private val apkDownloader = ApkDownloader({ settings.githubToken }, File(app.cacheDir, "apk"), Http.client)
    var running by mutableStateOf(false)
        private set
    var pending by mutableStateOf<PendingConfirm?>(null)
        private set
    var preview by mutableStateOf<String?>(null)
        private set
    /** What the agent is waiting for and since when (for the live timer). */
    var status by mutableStateOf<String?>(null)
        private set
    var statusSince by mutableStateOf(0L)
        private set
    /** Bumped whenever settings shown on the chat screen change. */
    var settingsVersion by mutableStateOf(0)
        private set

    /** A run and the chat it belongs to; it keeps going when the user switches chats (background task). */
    private class Run(val sid: String, val agent: Agent, val work: MutableList<ChatMessage>) {
        var job: Job? = null
        var base: Pair<String, String>? = null
    }
    private val runs = HashMap<String, Run>()
    /** Chats whose run continues in the background. */
    var backgroundRuns by mutableStateOf<Set<String>>(emptySet())
        private set
    private val job: Job? get() = runs[currentSessionId]?.job
    private val MAX_RUNS = 3
    private fun refreshBackground() { backgroundRuns = runs.keys.filter { it != currentSessionId }.toSet() }
    private var previewJob: Job? = null

    /** Set when the first CI wait starts and notifications are not allowed yet; the UI asks once. */
    var askNotificationPermission by mutableStateOf(false)
        private set
    private var askedNotificationPermission = false

    fun notificationPermissionAsked() { askNotificationPermission = false }

    init {
        // Resume the most recent chat; older ones stay in the history drawer.
        val latest = sessionStore.list().firstOrNull()
        currentSessionId = latest?.id ?: sessionStore.newId()
        if (latest != null) messages.addAll(sessionStore.load(latest.id))
        messages.addAll(com.suarez.coder.security.Guard.closeDangling(messages))
        sessions = sessionStore.list()
        profiles = settings.profiles()
        activePlan = planStore.recover(currentSessionId)
        wire(agent)
        if (settings.evoEnabled) com.suarez.coder.evolution.WeeklyWorker.schedule(app, settings.evoWifiOnly)
        if (settings.sentinelEnabled) com.suarez.coder.evolution.RepoSentinelWorker.schedule(app, settings.sentinelHours)
        viewModelScope.launch(Dispatchers.IO) {
            // New crashes and, after a self-update, a crash spike (rolled back at level 3).
            val cycle = com.suarez.coder.evolution.WeeklyCycle(app, settings, evoStore)
            cycle.watchCrashes()
            val msg = runCatching { cycle.rollbackIfNeeded() }.getOrNull()
            withContext(Dispatchers.Main) { crashNotice = msg }
        }
        refreshEvolution()
        viewModelScope.launch(Dispatchers.IO) { initLedger() }
        // Keep the model lists fresh in the background (once a day), so nobody has to press "Modelleri getir".
        if (System.currentTimeMillis() - settings.catalogAt > 24 * 3_600_000L) refreshCatalog(quiet = true)
    }

    /** Connects an agent to the app: memory, learned choices, plans, CI notifications, similar tasks. */
    private fun wire(a: Agent): Agent {
        a.lessonsFor = { repo -> if (settings.evoEnabled && settings.evoMemory) evoStore.lessons(repo) else null }
        a.context.ruleStore = evoStore.scopedRules
        a.context.rulesEnabled = { settings.evoEnabled && settings.evoMemory }
        a.context.generalRules = {
            com.suarez.coder.evolution.CrossRepoKnowledge(java.io.File(evoStore.dir, "general_rules.json")).all()
        }
        a.ciLessons = com.suarez.coder.evolution.CiLessonLearner(evoStore.scopedRules)
        a.tools.attachNotes = { repo, branch, files ->
            if (!settings.syncProjectNotes || a.isEvolutionSession) files
            else com.suarez.coder.evolution.NotesSync.attachFromGitHub(a.tools.github, a.context.ruleStore, repo, branch, files, enabled = true)
        }
        a.modelPicker = { route, ctx, paths, intent -> manager.pick(route, ctx, paths, intent) }
        a.promptOverrides = { if (settings.evoEnabled) evoStore.promptOverrides() else emptyMap() }
        a.policyText = { manager.policyText() }
        a.adaptiveRouting = { if (settings.evoEnabled && settings.evoRouting) evoStore.routing.current() else AdaptiveRouting() }
        a.similarFor = { repo, request ->
            com.suarez.coder.agent.SimilarTasks.hint(com.suarez.coder.agent.SimilarTasks.pick(runCatching { evoStore.traces() }.getOrDefault(emptyList()), request, repo))
        }
        a.onSpan = { span ->
            val sid = runs.values.firstOrNull { it.agent === a }?.sid ?: currentSessionId
            spanStore.add(sid, span)
            if (sid == currentSessionId) viewModelScope.launch(Dispatchers.Main) { timeline = timeline + span }
        }
        a.orchestratorHooks = object : OrchestratorHooks {
            override fun save(plan: OrchestratorPlan) {
                val session = runs.values.firstOrNull { it.agent === a }?.sid ?: currentSessionId
                planStore.save(session, plan)
                if (session == currentSessionId) activePlan = plan
            }
            override fun pauseRequested() = pauseRequested
            override fun clearPause() { pauseRequested = false }
        }
        a.tools.ciListener = object : ToolRegistry.CiListener {
            override fun started(repo: String, label: String) {
                val ctx = getApplication<Application>()
                if (!CiWatchService.canNotify(ctx) && !askedNotificationPermission) {
                    askedNotificationPermission = true
                    viewModelScope.launch { askNotificationPermission = true }
                }
                CiWatchService.start(ctx, repo, label)
            }

            override fun finished(repo: String, success: Boolean, summary: String, apkUrl: String?) {
                CiWatchService.finish(getApplication(), repo, success, summary, apkUrl)
            }
        }
        return a
    }

    /** The chat on screen loses its agent to a background run: it gets a fresh one. */
    private fun releaseForegroundAgent() {
        if (runs.values.any { it.agent === agent }) agent = wire(Agent(settings, getApplication<Application>().filesDir))
        else agent.resetSession()
    }

    fun refreshSettings() { settingsVersion++; profiles = settings.profiles() }

    /** Repo, branch or instructions changed in Settings: the next request must not reuse the old project context. */
    fun projectSettingsChanged() {
        agent.context.invalidate()
        agent.invalidateSnapshot()
        refreshSettings()
    }

    val sessionCostText: String
        get() {
            val c = agent.sessionCost(messages)
            val calls = messages.filterIsInstance<ChatMessage.UsageRecord>().filter { it.role != "cache" && it.role != "decision" }
            val hit = agent.cacheHitRate(calls)?.let { " · önbellek %${(it * 100).toInt()}" } ?: ""
            return "Oturum ${Pricing.formatUsd(c)} (${Pricing.formatTry(c * settings.usdTry)})$hit"
        }

    // ---- Model choices ----

    fun modelOptions(): List<ModelRef> {
        val keys = LinkedHashSet<String>()
        keys += settings.modelCatalog
        keys += listOf("gemini/gemini-2.5-pro", "gemini/gemini-2.5-flash", "gemini/gemini-2.5-flash-lite",
            "deepseek/deepseek-chat", "deepseek/deepseek-reasoner")
        if (settings.openrouterKey.isNotBlank()) keys += "openrouter/" + settings.openrouterModel
        keys += listOf(agent.singleRef(Provider.GEMINI).key, agent.singleRef(Provider.DEEPSEEK).key, settings.architectModel, settings.editorModel)
        return keys.mapNotNull { ModelRef.parse(it) }.sortedWith(compareBy({ it.provider.ordinal }, { it.model }))
    }

    fun selectSingle(ref: ModelRef) {
        settings.provider = ref.provider
        settings.setModelFor(ref.provider, ref.model)
        refreshSettings()
    }

    fun priceLabel(ref: ModelRef): String {
        val p = agent.price(ref.model)
        return String.format(java.util.Locale.US, "$%.2f / $%.2f 1M", p.input, p.output) + if (agent.supportsVision(ref)) " · 🖼" else ""
    }

    fun selectArchitect(ref: ModelRef) { settings.architectModel = ref.key; refreshSettings() }
    fun selectEditor(ref: ModelRef) { settings.editorModel = ref.key; refreshSettings() }
    fun setArchitectMode(on: Boolean) { settings.architectMode = on; refreshSettings() }
    fun setThinking(level: String) { settings.thinkingLevel = level; refreshSettings() }
    fun setSmartRouting(on: Boolean) { settings.smartRouting = on; refreshSettings() }
    fun setEconomy(on: Boolean) { settings.economyMode = on; refreshSettings() }

    /** Deletes the active Gemini cache; the result is shown as a notice. */
    fun clearCache() {
        if (running) return
        viewModelScope.launch {
            val msg = runCatching { agent.clearCache() }.getOrElse { "Önbellek silinemedi: ${it.message}" }
            messages += ChatMessage.Notice("🧹 $msg")
            save()
        }
    }

    // ---- Sending ----

    private val ui = object : AgentUi {
        override fun update() {}
        override fun status(text: String?) {
            status = text
            statusSince = System.currentTimeMillis()
        }
        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String): Boolean {
            val p = PendingConfirm(title, body, okText, cancelText, CompletableDeferred())
            pending = p
            return try { p.answer.await() } finally { pending = null }
        }
    }

    fun send(text: String) {
        if ((text.isBlank() && attachments.isEmpty() && docs.none { it.text != null }) || running) return
        if (docs.any { it.busy != null }) { docStatus = "Belge hâlâ okunuyor; bitince gönder."; return }
        if (attachments.isNotEmpty() && !visionSupported()) {
            pendingSendText = text
            visionWarning = "Seçili model(ler)den biri görselleri göremiyor. Görseller bu modellere gönderilmez; " +
                "yalnızca bir not eklenir. Yine de gönderilsin mi? Görsel için Gemini veya görsel destekli bir OpenRouter modeli seçin."
            return
        }
        doSend(text)
    }

    fun answerVisionWarning(send: Boolean) {
        visionWarning = null
        val t = pendingSendText
        pendingSendText = null
        if (send && t != null) doSend(t)
    }

    /** Models that may read the user's message: the main one, and the editor when routing can pick it. */
    private fun visionSupported(): Boolean {
        val refs = buildList {
            add(agent.mainRef())
            if (settings.smartRouting) add(agent.editorRef())
        }
        return refs.all { agent.supportsVision(it) }
    }

    private fun doSend(text: String) {
        previewJob?.cancel(); preview = null
        val images = attachments.toList()
        attachments.clear()
        val docBlocks = docs.mapNotNull { d -> d.text?.let { com.suarez.coder.docs.DocumentReader.block(d.name, it) } }
        docs.clear(); docStatus = null
        val body = expandSkillCommand(text.trim()).ifBlank { if (docBlocks.isNotEmpty()) "Ekteki belgeyi oku." else "(Ekran görüntüsüne bak.)" }
        messages += ChatMessage.User(if (docBlocks.isEmpty()) body else body + "\n\n" + docBlocks.joinToString("\n\n"), images)
        save()
        // "Hayır, olmadı" right after a run is a correction signal for the previous trace.
        lastTraceId?.takeIf { SessionTraceRecorder.isCorrection(text) }?.let { id ->
            viewModelScope.launch(Dispatchers.IO) {
                evoStore.traces().firstOrNull { it.id == id }?.let { before ->
                    val after = before.copy(userCorrection = true)
                    evoStore.updateTrace(id) { after }
                    manager.relearn(before, after)
                }
            }
        }
        lastTraceId = null
        start(null)
    }

    /** A document attached to the next message: read on the phone, or converted by markitdown in the cloud. */
    data class DocAttachment(val id: Long, val name: String, val text: String? = null, val busy: String? = null, val error: String? = null)
    val docs = mutableStateListOf<DocAttachment>()
    var docStatus by mutableStateOf<String?>(null)

    fun addDocuments(uris: List<Uri>) {
        val app = getApplication<Application>()
        for (uri in uris.take(com.suarez.coder.docs.DocumentReader.MAX_DOCS - docs.size)) {
            val name = runCatching {
                app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "belge"
            val id = System.nanoTime()
            docs += DocAttachment(id, name, busy = "okunuyor")
            fun update(f: (DocAttachment) -> DocAttachment) { val i = docs.indexOfFirst { it.id == id }; if (i >= 0) docs[i] = f(docs[i]) }
            viewModelScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = app.contentResolver.openInputStream(uri)?.use { s ->
                            val b = s.readBytes(); if (b.size > com.suarez.coder.docs.DocumentReader.MAX_BYTES) throw IllegalStateException("Belge 15 MB'tan büyük."); b
                        } ?: throw IllegalStateException("Belge açılamadı.")
                        com.suarez.coder.docs.DocumentReader.extract(name, bytes) ?: run {
                            withContext(Dispatchers.Main) { update { it.copy(busy = "bulutta dönüştürülüyor (markitdown)") } }
                            val runner = agent.tools.cloud ?: throw IllegalStateException("Bulut araçları için GitHub token gerekli.")
                            com.suarez.coder.docs.DocsConverter(agent.tools.github, runner).convert(agent.tools.activeRepo(), name, bytes)
                        }
                    }
                }
                result.onSuccess { t -> update { it.copy(text = t, busy = null) } }
                    .onFailure { e -> update { it.copy(busy = null, error = e.message?.take(240) ?: "Okunamadı") } }
            }
        }
    }

    fun removeDocument(id: Long) { docs.removeAll { it.id == id }; docStatus = null }

    /** Resizes picked images on a background thread (long edge ≤ 1568 px, JPEG 80%). */
    fun addImages(uris: List<Uri>) {
        val room = ImagePrep.MAX_IMAGES - attachments.size
        if (room <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            for (uri in uris.take(room)) {
                val path = runCatching { ImagePrep.prepare(getApplication(), uri) }.getOrNull() ?: continue
                withContext(Dispatchers.Main) { if (attachments.size < ImagePrep.MAX_IMAGES) attachments += path }
            }
        }
    }

    fun removeImage(path: String) {
        attachments.remove(path)
        runCatching { File(path).delete() }
    }

    /** Downloads an APK found in the chat; progress and result are exposed in [downloads]. */
    fun downloadApk(url: String, target: ApkTarget) {
        if (downloads[url] is DownloadState.Running) return
        viewModelScope.launch { fetchApk(url, target) }
    }

    /** Downloads (or reuses) the APK and returns its file, or null on failure. */
    suspend fun fetchApk(url: String, target: ApkTarget): File? {
        (downloads[url] as? DownloadState.Ready)?.let { r -> File(r.path).takeIf { it.isFile }?.let { return it } }
        downloads[url] = DownloadState.Running(0)
        val result = try {
            val file = apkDownloader.download(target) { pct -> viewModelScope.launch { if (downloads[url] is DownloadState.Running) downloads[url] = DownloadState.Running(pct) } }
            DownloadState.Ready(file.absolutePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DownloadState.Failed(e.message ?: e.toString())
        }
        downloads[url] = result
        return (result as? DownloadState.Ready)?.let { File(it.path) }
    }

    /** Stops the current run if any and repeats the last request in Architect mode. Spent cost stays recorded. */
    fun rerunAsArchitect() {
        val previous = job
        previous?.cancel()
        viewModelScope.launch {
            previous?.join()
            val idx = messages.indexOfLast { it is ChatMessage.User }
            if (idx < 0) return@launch
            val keep = messages.take(idx + 1) + messages.drop(idx + 1).filterIsInstance<ChatMessage.UsageRecord>()
            messages.clear(); messages.addAll(keep)
            save()
            start(Route.COMPLEX_ARCHITECT)
        }
    }

    private fun start(override: Route?) = launchRun { work, agentUi -> agent.run(work, agentUi, override) }

    private fun launchRun(block: suspend (MutableList<ChatMessage>, AgentUi) -> Unit) {
        if (runs.size >= MAX_RUNS) {
            messages += ChatMessage.Notice("Aynı anda en fazla $MAX_RUNS görev çalışabilir; arka plandakilerden biri bitince tekrar gönder.", isError = true)
            save()
            return
        }
        running = true
        canRate = false
        lastRating = 0
        val sid = currentSessionId
        val a = agent
        runCatching {
            val ab = com.suarez.coder.evolution.StrategyAbOptimizer(java.io.File(evoStore.dir, "ab.json"), settings.architectModel)
            if (settings.abEnabled) {
                ab.ensureActive()
                a.implementerTactic = ab.pin(sid).hint
            } else a.implementerTactic = null
        }
        val run = Run(sid, a, messages.toMutableList())
        runs[sid] = run
        val baseRepo = a.tools.normalizeRepo(repoNow())
        if (baseRepo.isNotBlank() && settings.githubToken.isNotBlank()) viewModelScope.launch(Dispatchers.IO) {
            val branch = settings.contextRef.ifBlank { settings.defaultBranch }.ifBlank { null }
            runCatching { a.tools.github.headSha(baseRepo, branch) }.getOrNull()?.let { run.base = it to (branch ?: "") }
        }
        val runT0 = System.currentTimeMillis()
        run.job = viewModelScope.launch {
            val work = run.work
            val runStart = work.size
            val agentUi = object : AgentUi {
                override fun update() { if (sid == currentSessionId) { sync(work); save() } else saveSession(sid, work) }
                override fun status(text: String?) { if (sid == currentSessionId) ui.status(text) }
                override suspend fun confirm(title: String, body: String, okText: String, cancelText: String) =
                    ui.confirm(if (sid == currentSessionId) title else "[Arka plan] $title", body, okText, cancelText)
            }
            try {
                block(work, agentUi)
            } catch (e: CancellationException) {
                work += ChatMessage.Notice("Durduruldu.")
            } catch (e: StopException) {
                work += ChatMessage.Notice(e.message ?: "Durduruldu.")
            } catch (e: Exception) {
                work += ChatMessage.Notice("Hata: ${e.message ?: e.toString()}", isError = true)
            } finally {
                runs.remove(sid)
                (work.drop(runStart).firstOrNull { it is ChatMessage.User } as? ChatMessage.User ?: work.take(runStart).lastOrNull { it is ChatMessage.User } as? ChatMessage.User)
                    ?.let { u -> a.onSpan?.invoke(com.suarez.coder.agent.Span(com.suarez.coder.agent.Span.RUN, u.text.lineSequence().first().take(60), runT0, System.currentTimeMillis())) }
                if (sid == currentSessionId) {
                    sync(work)
                    pending = null
                    status = null
                    running = false
                    save(now = true)
                } else {
                    saveSession(sid, work, now = true)
                    notifyBackgroundDone(work)
                }
                refreshBackground()
                recordTrace(work, runStart, sid, a, run.base)
            }
        }
    }

    private fun notifyBackgroundDone(work: List<ChatMessage>) {
        val ctx = getApplication<Application>()
        val request = (work.lastOrNull { it is ChatMessage.User } as? ChatMessage.User)?.text.orEmpty()
        val answer = (work.lastOrNull { it is ChatMessage.Assistant && it.text.isNotBlank() } as? ChatMessage.Assistant)?.text
            ?: (work.lastOrNull { it is ChatMessage.Notice } as? ChatMessage.Notice)?.text ?: ""
        com.suarez.coder.ci.TaskNotifier.done(ctx, "Arka plan görevi bitti", com.suarez.coder.docs.DocumentReader.collapse(request).take(80), answer.take(400))
    }

    /** Puts the input box text through a cheap model that turns it into a precise request (shown for editing, not sent). */
    var enhancing by mutableStateOf(false)
        private set

    fun enhancePrompt(text: String, onResult: (String) -> Unit) {
        if (text.isBlank() || enhancing) return
        enhancing = true
        viewModelScope.launch {
            val a = agent
            val tmp = ArrayList<ChatMessage>()
            val system = "Rewrite the user's request to a coding agent as a precise task, in the user's language, as plain text (no " +
                "markdown headings). Keep their intent and every detail they gave; do not invent features. Structure: one goal sentence; " +
                "then short lines for: where (files/screens, only if known or obvious), behavior, acceptance criteria, out of scope. " +
                "Keep @mentions and /commands unchanged. At most 12 lines. Output only the rewritten request."
            val recent = messages.takeLast(6).joinToString("\n") { m -> when (m) { is ChatMessage.User -> "User: " + m.text.take(300); is ChatMessage.Assistant -> "Assistant: " + m.text.take(300); else -> "" } }.trim()
            val r = runCatching {
                a.callModel(tmp, listOf(ChatMessage.User(text.take(4_000) + (if (recent.isNotBlank()) "\n\nRecent chat:\n$recent" else ""))),
                    a.routerRef() ?: a.editorRef(), "agent", system, emptyList(), ui, useCache = false, maxOut = 700, thinking = "low")
            }
            enhancing = false
            r.onSuccess { reply ->
                messages += tmp.filterIsInstance<ChatMessage.UsageRecord>()
                save()
                reply.text.trim().takeIf { it.isNotBlank() }?.let(onResult)
            }.onFailure { e -> if (e !is CancellationException) docStatus = "İstek iyileştirilemedi: ${e.message?.take(120)}" }
        }
    }

    /** Rewinds a branch to the checkpoint saved before the agent's first write (asks first; force update). */
    fun rewind(p: com.suarez.coder.agent.Checkpoints.Point) {
        if (running) return
        viewModelScope.launch {
            val what = if (p.sha == null) "${p.branch} dalı bu görevde oluşturuldu; SİLİNECEK." else
                "${p.branch} dalı ${p.sha.take(7)} commit'ine zorla geri alınacak. Sonraki commit'ler daldan çıkar (GitHub'da sha ile hâlâ bulunabilir)."
            val warn = if (p.branch == settings.defaultBranch || p.branch == "main" || p.branch == "master") "\n\nDİKKAT: bu varsayılan dal olabilir." else ""
            if (!ui.confirm("Kontrol noktasına dön", "${p.repo}\n$what$warn", "Geri dön", "Vazgeç")) return@launch
            val r = runCatching { withContext(Dispatchers.IO) { agent.tools.github.resetBranch(p.repo, p.branch, p.sha) } }
            messages += ChatMessage.Notice(r.fold({ "Geri alındı: ${p.branch}" + (p.sha?.let { " → ${it.take(7)}" } ?: " silindi") },
                { "Geri alınamadı: ${it.message?.take(200)}" }), isError = r.isFailure)
            agent.invalidateSnapshot(); agent.context.invalidate()
            save()
        }
    }

    /** Repo paths for @-suggestions, loaded once per repo when the user types "@". */
    var repoPaths by mutableStateOf<List<String>>(emptyList())
        private set
    private var pathsRepo = ""

    fun loadRepoPaths() {
        val repo = agent.tools.activeRepo()
        if (repo.isBlank() || repo == pathsRepo || settings.githubToken.isBlank()) return
        pathsRepo = repo
        viewModelScope.launch {
            repoPaths = runCatching { withContext(Dispatchers.IO) { agent.tools.filteredTree(repo, settings.contextRef.ifBlank { null }).map { it.path } } }.getOrDefault(emptyList())
        }
    }

    // ---- Custom modes ----

    fun customModes() = com.suarez.coder.agent.CustomMode.list(settings.customModes)
    fun saveModes(list: List<com.suarez.coder.agent.CustomMode>) {
        settings.customModes = com.suarez.coder.agent.CustomMode.toJson(list)
        if (list.none { it.name == settings.activeMode }) settings.activeMode = ""
        refreshSettings()
    }
    fun setActiveMode(name: String) { settings.activeMode = name; refreshSettings() }

    // ---- Skills & cloud tools ----

    var skills by mutableStateOf<List<com.suarez.coder.skills.Skill>>(emptyList())
        private set
    var cloudInstalls by mutableStateOf<List<com.suarez.coder.cloudtools.CloudToolRunner.Install>?>(null)
        private set
    var skillsStatus by mutableStateOf<String?>(null)
        private set

    // Kotlin runs initializers in source order: this must come after the skill state above.
    init { refreshSkills() }

    // ---- Importing skills from GitHub (starred repositories or a link) ----

    private val importer = com.suarez.coder.skills.SkillImporter(
        tree = { r, ref -> agent.tools.github.treeEntries(r, ref) },
        read = { r, p, ref -> agent.tools.github.readFileRaw(r, p, ref) },
    )
    var starred by mutableStateOf<List<com.suarez.coder.tools.GitHub.StarredRepo>?>(null)
        private set
    var candidates by mutableStateOf<List<com.suarez.coder.skills.SkillImporter.Candidate>?>(null)
        private set
    var importBusy by mutableStateOf<String?>(null)
        private set
    var importStatus by mutableStateOf<String?>(null)
        private set

    /** Starred repos; likely skill repositories (name, description or topics mention skills/agents) first. */
    fun loadStarred() {
        if (importBusy != null) return
        importBusy = "Yıldızlı repolar alınıyor"
        viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { agent.tools.github.starredRepos() } }
            starred = r.getOrNull()?.sortedByDescending { s ->
                val text = (s.fullName + " " + s.description + " " + s.topics.joinToString(" ")).lowercase()
                listOf("skill", "claude", "agent", "prompt").count { text.contains(it) }
            }
            importStatus = r.exceptionOrNull()?.let { "Alınamadı: ${it.message?.take(160)}" }
            importBusy = null
        }
    }

    /** Lists the skills of a starred repo or of a pasted GitHub link (repo, folder or SKILL.md). */
    fun scanForSkills(input: String) {
        val ref = com.suarez.coder.skills.SkillImporter.parseRef(input) ?: run { importStatus = "Geçerli bir GitHub adresi değil."; return }
        if (importBusy != null) return
        importBusy = "${ref.repo} taranıyor"
        candidates = null
        viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { importer.scan(ref.repo, ref.ref, ref.path) } }
            candidates = r.getOrNull()
            importStatus = when {
                r.isFailure -> "Taranamadı: ${r.exceptionOrNull()?.message?.take(160)}"
                r.getOrNull().isNullOrEmpty() -> "${ref.repo} içinde SKILL.md bulunamadı."
                else -> "${r.getOrNull()!!.size} yetenek bulundu."
            }
            importBusy = null
        }
    }

    fun clearCandidates() { candidates = null; importStatus = null }

    fun importSkills(selected: List<com.suarez.coder.skills.SkillImporter.Candidate>) {
        if (selected.isEmpty() || importBusy != null) return
        importBusy = "İçe aktarılıyor"
        viewModelScope.launch {
            val done = ArrayList<String>(); val failed = ArrayList<String>(); val flagged = ArrayList<String>()
            withContext(Dispatchers.IO) {
                for (c in selected) {
                    val s = runCatching { importer.import(c) }.getOrNull()
                    if (s == null) { failed += c.name; continue }
                    agent.tools.skills?.saveGlobal(s)
                    // Suspicious content: imported but switched off until the user has looked at it.
                    if (com.suarez.coder.skills.SkillSafety.scan(s).isNotEmpty()) { agent.tools.skills?.setEnabled(s.name, false); flagged += s.name }
                    else { agent.tools.skills?.setEnabled(s.name, true); done += s.name }
                }
            }
            importStatus = "İçe aktarıldı: ${done.joinToString().ifBlank { "-" }}" +
                (if (flagged.isNotEmpty()) " · Uyarı nedeniyle kapalı eklendi (inceleyip açın): ${flagged.joinToString()}" else "") +
                if (failed.isNotEmpty()) " · Başarısız: ${failed.joinToString()}" else ""
            importBusy = null
            refreshSkills()
        }
    }

    /** Pulls the newest version of an imported skill from its source repository. */
    fun updateSkill(name: String) {
        val origin = agent.tools.skills?.global(name)?.origin ?: return
        viewModelScope.launch {
            skillsStatus = "$name güncelleniyor"
            val s = runCatching { withContext(Dispatchers.IO) { importer.update(origin) } }.getOrNull()
            skillsStatus = if (s == null) "$name güncellenemedi (kaynak bulunamadı)." else {
                agent.tools.skills?.saveGlobal(s); "$name güncellendi."
            }
            refreshSkills()
        }
    }

    fun refreshSkills(fetchRepo: Boolean = false) {
        val repo = agent.tools.activeRepo()
        viewModelScope.launch {
            val s = agent.tools.skills ?: return@launch
            if (fetchRepo) runCatching { withContext(Dispatchers.IO) { s.refreshRepo(repo, force = true) } }
            skills = withContext(Dispatchers.IO) { s.all(repo) }
        }
    }

    fun setSkillEnabled(name: String, on: Boolean) {
        agent.tools.skills?.setEnabled(name, on); refreshSkills()
    }

    /** Saves a SKILL.md typed in the editor as a global skill; returns an error or null. */
    fun saveSkill(markdown: String, fallbackName: String): String? {
        val parsed = com.suarez.coder.skills.SkillParser.parse(markdown, fallbackName)
            ?: return "Geçersiz ad: küçük harf, rakam ve '-' kullanın (ör. godot-scene)."
        val old = skills.firstOrNull { it.name == parsed.name && it.source != com.suarez.coder.skills.Skill.Source.REPO }
        agent.tools.skills?.saveGlobal(parsed.copy(source = old?.source ?: com.suarez.coder.skills.Skill.Source.GLOBAL))
        refreshSkills()
        return null
    }

    fun deleteSkill(name: String) { agent.tools.skills?.deleteGlobal(name); refreshSkills() }

    fun refreshCloudTools() {
        val repo = agent.tools.activeRepo()
        if (repo.isBlank()) { cloudInstalls = emptyList(); return }
        viewModelScope.launch {
            cloudInstalls = runCatching { withContext(Dispatchers.IO) { agent.tools.cloud?.installState(repo, null) } }.getOrNull()
        }
    }

    fun installCloudTool(t: com.suarez.coder.cloudtools.CloudWorkflowTemplates.CloudTool) {
        val repo = agent.tools.activeRepo()
        viewModelScope.launch {
            skillsStatus = "${t.file} kuruluyor"
            skillsStatus = runCatching { withContext(Dispatchers.IO) { agent.tools.cloud!!.install(repo, t, null, force = true) } }
                .getOrElse { "Kurulamadı: ${it.message?.take(160)}" }
            refreshCloudTools()
        }
    }

    /** "Revize Et": the rendered preview goes into the image attachments, ready for a short instruction. */
    fun attachPreview(file: File) {
        if (attachments.size >= ImagePrep.MAX_IMAGES) return
        viewModelScope.launch(Dispatchers.IO) {
            val path = runCatching { ImagePrep.prepare(getApplication(), android.net.Uri.fromFile(file)) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) { attachments += path }
        }
    }

    /** Saves a cloud output to Downloads/Coder (Android 10+), else opens the share sheet. Returns a message. */
    fun saveAsset(file: File): String {
        val ctx = getApplication<Application>()
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Coder")
                }
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return shareAsset(file)
                ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                "İndirilenler/Coder klasörüne kaydedildi: ${file.name}"
            } else shareAsset(file)
        } catch (e: Exception) {
            "Kaydedilemedi: ${e.message}"
        }
    }

    fun shareAsset(file: File): String {
        val ctx = getApplication<Application>()
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(android.content.Intent.createChooser(send, file.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Paylaşım menüsü açıldı."
    }

    /** "/blender-3d rest": tells the agent to load that skill first. */
    private fun expandSkillCommand(text: String): String {
        val m = Regex("^/([a-z0-9][a-z0-9-]*)\\b\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(text.trim()) ?: return text
        val name = m.groupValues[1]
        val skill = skills.firstOrNull { it.name == name && it.enabled } ?: return text
        return m.groupValues[2].ifBlank { skill.description } + "\n\n(Önce load_skill(\"$name\") ile \"$name\" yeteneğini yükle ve talimatlarına uy.)"
    }

    // ---- Self-improvement manager ----

    var crashNotice by mutableStateOf<String?>(null)
        private set
    var experiments by mutableStateOf<List<com.suarez.coder.evolution.Experiment>>(emptyList())
        private set
    var weekly by mutableStateOf<com.suarez.coder.evolution.WeeklyReport?>(null)
    /** Last weekly project health check (report only). */
    var health by mutableStateOf<List<String>>(emptyList())
        private set
    var goalPlan by mutableStateOf<com.suarez.coder.evolution.GoalPlan?>(null)
        private set

    /** Thumbs on the latest answer: the strongest learning signal (changes the model statistics at once). */
    fun rate(value: Int) {
        val id = lastTraceId ?: return
        lastRating = if (lastRating == value) 0 else value
        val v = lastRating
        viewModelScope.launch(Dispatchers.IO) {
            evoStore.traces().firstOrNull { it.id == id }?.let { before ->
                val after = before.copy(rating = v, processed = before.processed && v == before.rating)
                evoStore.updateTrace(id) { after }
                manager.relearn(before, after)
            }
        }
    }

    // ---- Model report card, cache hits, price watch (InsightsController) ----

    val insights = InsightsController(viewModelScope, settings, evoStore, ledger, { agent }, { modelOptions() }, { refreshSettings() })
    val modelRows get() = insights.modelRows
    val cacheRows get() = insights.cacheRows
    val suggestedChain get() = insights.suggestedChain
    val priceAlerts get() = insights.priceAlerts
    fun refreshReport() = insights.refreshReport()
    fun applySuggestedChain() = insights.applySuggestedChain()
    fun checkPrices() = insights.checkPrices()
    fun dismissPriceAlert(text: String) = insights.dismissPriceAlert(text)

    // Kotlin runs initializers in source order: this must come after the controller above.
    init { refreshReport(); checkPrices() }

    // ---- Release evaluation (fixed task set, statistics) ----

    var evalRuns by mutableStateOf<List<com.suarez.coder.evolution.EvalRun>>(emptyList())
        private set
    var evalSuiteSize by mutableStateOf(0)
        private set

    fun refreshEval() {
        viewModelScope.launch(Dispatchers.IO) {
            val s = com.suarez.coder.evolution.EvalSuite(getApplication(), settings, evoStore)
            val runs = s.evals.runs(); val size = s.evals.suite().size
            withContext(Dispatchers.Main) { evalRuns = runs; evalSuiteSize = size }
        }
    }

    fun runEvalNow() { com.suarez.coder.evolution.EvalWorker.start(getApplication()); evoStatus = "Değerlendirme arka planda başladı; bitince bildirim gelir." }

    fun rebuildEvalSuite() {
        viewModelScope.launch(Dispatchers.IO) {
            com.suarez.coder.evolution.EvalSuite(getApplication(), settings, evoStore).ensureSuite(refresh = true)
            refreshEval()
        }
    }

    /** Off-policy comparison of model-choice rules on the logged decisions (empty until there are enough). */
    var policyRows by mutableStateOf<List<String>>(emptyList())
        private set

    fun refreshManager() {
        refreshReport()
        refreshEval()
        viewModelScope.launch(Dispatchers.IO) { val r = runCatching { manager.policyReport() }.getOrDefault(emptyList()); withContext(Dispatchers.Main) { policyRows = r } }
        viewModelScope.launch(Dispatchers.IO) {
            val exps = evoStore.experiments().asReversed()
            val best = listOf("SIMPLE_EDIT", "COMPLEX_ARCHITECT", "QUESTION_ONLY").mapNotNull { r ->
                com.suarez.coder.evolution.ModelBandit.rolesFor(r).mapNotNull { role ->
                    manager.bandit.best(r, role, settings.costWeight)?.let { "${r.substringBefore('_').lowercase()}/${if (role == "architect") "mimar" else "uyg"}: ${it.substringAfterLast('/')}" }
                }.takeIf { it.isNotEmpty() }?.joinToString(", ")
            }.joinToString(" · ")
            val since = System.currentTimeMillis() - com.suarez.coder.evolution.WeeklyReport.WEEK_MS
            val report = com.suarez.coder.evolution.WeeklyReport.compute(evoStore.traces(), evoStore.log(), exps,
                com.suarez.coder.evolution.CrashRecorder.recent(getApplication(), since), best)
            val h = runCatching {
                val a = org.json.JSONArray(java.io.File(evoStore.dir, "health.json").readText())
                (0 until a.length()).map { com.suarez.coder.evolution.ProjectHealth.lineFromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { experiments = exps; weekly = report; health = h }
        }
    }

    /** "Haftalık döngüyü şimdi çalıştır": crash watch, GitHub scout, exams (within the weekly exam budget). */
    fun runWeeklyNow() {
        if (evoBusy) return
        evoBusy = true
        viewModelScope.launch {
            evoStatus = runCatching {
                withContext(Dispatchers.IO) { com.suarez.coder.evolution.WeeklyCycle(getApplication(), settings, evoStore) { s -> viewModelScope.launch { evoStatus = s } }.run() }
            }.getOrElse { "Hata: ${it.message?.take(160)}" }
            evoBusy = false
            refreshEvolution(); refreshManager(); refreshSkills()
        }
    }

    /** A PROPOSED experiment (autonomy level 1): the user applies or rejects it. */
    fun decideExperiment(e: com.suarez.coder.evolution.Experiment, apply: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val skills = com.suarez.coder.skills.SkillRepository(File(getApplication<Application>().filesDir, "skills"), { null }, { _, _ -> null })
            if (apply) com.suarez.coder.evolution.ExperimentApplier(settings, evoStore, skills).apply(e)
            evoStore.saveExperiment(e.copy(state = if (apply) com.suarez.coder.evolution.Experiment.State.ADOPTED else com.suarez.coder.evolution.Experiment.State.REJECTED,
                decidedAt = System.currentTimeMillis()))
            refreshEvolution(); refreshManager(); refreshSkills()
            withContext(Dispatchers.Main) { refreshSettings() }
        }
    }

    /** The goal in the user's words → bounded settings (shown before applying). Uses the evolution model, else the editor. */
    fun planGoal(goal: String) {
        if (goal.isBlank() || evoBusy) return
        settings.managerGoal = goal
        evoBusy = true
        viewModelScope.launch {
            val available = (settings.modelCatalog + listOf(settings.architectModel, settings.editorModel)).filter { it.isNotBlank() }.distinct()
            val plan = runCatching {
                withContext(Dispatchers.IO) {
                    val a = com.suarez.coder.agent.Agent(settings).apply {
                        isEvolutionSession = true
                        budgetDeferred = true
                        if (settings.openrouterKey.isNotBlank()) modelOverride = com.suarez.coder.llm.ModelRef(com.suarez.coder.data.Provider.OPENROUTER, settings.evoModel)
                    }
                    val silent = object : com.suarez.coder.agent.AgentUi {
                        override fun update() {}
                        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String) = false
                    }
                    val local = ArrayList<ChatMessage>()
                    val reply = a.callModel(local, listOf(ChatMessage.User(com.suarez.coder.evolution.GoalPlan.prompt(goal, available, settings))),
                        a.editorRef(), "reflector", com.suarez.coder.evolution.GoalPlan.SYSTEM, emptyList(), silent, useCache = false, maxOut = 1_500, jsonMode = true)
                    val cost = local.filterIsInstance<ChatMessage.UsageRecord>().sumOf { a.costOf(it) }
                    if (cost > 0) evoStore.addLog(EvoTier.POLICY, "Hedef yorumlandı: ${goal.take(80)}", cost)
                    com.suarez.coder.evolution.GoalPlan.parse(reply.text, available)
                }
            }.getOrNull()
            goalPlan = plan
            evoStatus = if (plan == null) "Hedef ayarlara çevrilemedi; daha açık yazmayı deneyin." else null
            evoBusy = false
        }
    }

    fun applyGoal() {
        val plan = goalPlan ?: return
        val before = org.json.JSONObject().put("cw", settings.costWeight).put("al", settings.autonomyLevel).put("eb", settings.examWeeklyBudget)
            .put("db", settings.evoDailyBudget).put("pool", settings.modelPool.joinToString("\n")).put("lr", settings.learnedRouting).put("sc", settings.scoutEnabled)
        plan.apply(settings)
        evoStore.addLog(EvoTier.POLICY, "Hedef uygulandı: " + plan.summary, undo = org.json.JSONObject().put("kind", "GOAL").put("before", before).toString())
        goalPlan = null
        refreshSettings(); refreshEvolution()
        if (settings.evoEnabled) com.suarez.coder.evolution.WeeklyWorker.schedule(getApplication(), settings.evoWifiOnly)
    }

    fun dismissGoal() { goalPlan = null }

    fun rollbackLastMerge() {
        if (evoBusy) return
        evoBusy = true
        viewModelScope.launch {
            evoStatus = runCatching {
                withContext(Dispatchers.IO) {
                    val cycle = com.suarez.coder.evolution.WeeklyCycle(getApplication(), settings, evoStore)
                    val m = cycle.actions.lastMerge() ?: return@withContext "Geri alınacak merge yok."
                    cycle.actions.revert(m, "kullanıcı geri aldı")
                }
            }.getOrElse { "Geri alınamadı: ${it.message?.take(160)}" }
            crashNotice = null
            evoBusy = false
            refreshEvolution()
        }
    }

    // ---- Self-evolution ----

    /** Saves a trace of the finished run (never for evolution runs) and queues the background evolution worker. */
    private fun recordTrace(work: List<ChatMessage>, runStart: Int, sid: String, a: Agent, base: Pair<String, String>?) {
        val run = work.drop(runStart)
        val request = (work.take(runStart).lastOrNull { it is ChatMessage.User } as? ChatMessage.User)?.text.orEmpty()
        val trace = SessionTraceRecorder.record(run, request, sid, a.tools.normalizeRepo(repoNow()),
            a.isEvolutionSession, a::costOf, oscillationEscalations = a.tools.oscillation.escalations)?.copy(arm = a.lastArm, route = a.lastRoute, ctx = a.lastCtx?.toList(), prop = a.lastProp,
            baseSha = base?.first, branch = base?.second?.ifBlank { null }) ?: return
        if (sid == currentSessionId) canRate = true
        viewModelScope.launch(Dispatchers.IO) {
            evoStore.addTrace(trace)
            if (settings.abEnabled) runCatching {
                val ab = com.suarez.coder.evolution.StrategyAbOptimizer(java.io.File(evoStore.dir, "ab.json"), settings.architectModel)
                ab.record(sid, trace.totalCostUsd, trace.success)
                val report = ab.evaluate()
                if (report.rolledBack) evoStore.addLog(com.suarez.coder.evolution.EvoTier.INFO, "Strateji A/B geri alındı: ${report.note}")
                if (report.promoted) evoStore.addLog(com.suarez.coder.evolution.EvoTier.INFO, "Strateji A/B terfi etti: ${report.note}")
            }
            manager.learn(trace)
            lastTraceId = trace.id
            if (settings.evoEnabled) EvolutionWorker.schedule(getApplication(), settings.evoWifiOnly)
        }
    }

    fun refreshEvolution() {
        viewModelScope.launch(Dispatchers.IO) {
            val p = evoStore.pending()
            val log = evoStore.log().asReversed()
            val clusters = evoStore.clusters.all().sortedByDescending { it.occurrenceCount }
            val repo = settings.contextRepo.ifBlank { settings.defaultRepo }
            val rules = evoStore.scopedRules.all().filter { repo.isBlank() || it.repo.equals(repo, true) }
            val scores = evoStore.ctg.scores(null)
            val general = com.suarez.coder.evolution.CrossRepoKnowledge(java.io.File(evoStore.dir, "general_rules.json")).all()
            val ab = com.suarez.coder.evolution.StrategyAbOptimizer(java.io.File(evoStore.dir, "ab.json"), settings.architectModel).snapshot()
            val sentinel = com.suarez.coder.evolution.SentinelReports.read(evoStore.dir)
            withContext(Dispatchers.Main) {
                evoPending = p
                evoLog = log
                evoClusters = clusters
                evoRules = rules
                evoCtg = scores
                evoGeneral = general
                evoAb = ab
                evoSentinel = sentinel
            }
        }
    }

    fun evoSummary(): Map<String, String> = mapOf(
        "İncelenen seans" to evoStore.counter("reviewed").toString(),
        "Öğrenilen kural" to evoStore.lessonCount().toString(),
        "Yönlendirme ayarı" to evoLog.count { it.tier == EvoTier.ROUTING && !it.undone }.toString(),
        "Açılan / merge edilen PR" to "${evoStore.counter("prs")} / ${evoStore.counter("merged")}",
        "Toplam evrim maliyeti" to Pricing.formatUsd(evoLog.sumOf { it.costUsd }),
        "Bugün" to Pricing.formatUsd(evoStore.spentToday()) + " / " + Pricing.formatUsd(settings.evoDailyBudget),
    )

    /** "Son seansı şimdi değerlendir": ignores the switch and the cooldown, never the budget. */
    fun evoRunNow() {
        if (evoBusy) return
        evoBusy = true
        viewModelScope.launch {
            val msg = runCatching {
                withContext(Dispatchers.IO) { EvolutionEngine(getApplication(), settings, evoStore).run(force = true) { s -> viewModelScope.launch { evoStatus = s } } }
            }.getOrElse { "Hata: ${it.message}" }
            evoStatus = msg
            evoBusy = false
            refreshEvolution()
        }
    }

    fun evoUndo(entry: EvolutionLogEntry) {
        val prev = entry.undo ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val o = runCatching { org.json.JSONObject(prev) }.getOrNull()
            when (o?.optString("kind")) {
                "GOAL" -> o.optJSONObject("before")?.let { b ->
                    settings.costWeight = b.optDouble("cw", 0.5); settings.autonomyLevel = b.optInt("al", 1)
                    settings.examWeeklyBudget = b.optDouble("eb", 1.0); settings.evoDailyBudget = b.optDouble("db", 0.15)
                    settings.modelPool = b.optString("pool").lines(); settings.learnedRouting = b.optBoolean("lr", true); settings.scoutEnabled = b.optBoolean("sc", true)
                }
                "MODEL", "RULE", "SKILL" -> com.suarez.coder.evolution.ExperimentApplier(settings, evoStore,
                    com.suarez.coder.skills.SkillRepository(File(getApplication<Application>().filesDir, "skills"), { null }, { _, _ -> null })).undo(prev)
                else -> evoStore.routing.undo(prev)
            }
            withContext(Dispatchers.Main) { refreshSettings(); refreshSkills() }
            evoStore.markUndone(entry.id)
            evoStore.addLog(EvoTier.ROUTING, "Geri alındı: ${entry.summary.take(160)}")
            refreshEvolution()
        }
    }

    suspend fun evoPrFiles(): List<String> = evoPending?.let { p ->
        withContext(Dispatchers.IO) { runCatching { agent.tools.github.pullRequestFiles(EvolutionGuard.SELF_REPO, p.number) }.getOrDefault(p.files) }
    }.orEmpty()

    /** Merges the self-PR (squash), deletes its branch and waits for the release build so the APK can be installed. */
    fun evoMerge() {
        val p = evoPending ?: return
        if (evoBusy) return
        evoBusy = true
        viewModelScope.launch {
            try {
                evoStatus = "PR #${p.number} merge ediliyor"
                val gh = agent.tools.github
                val (code, msg) = withContext(Dispatchers.IO) { gh.tryMerge(EvolutionGuard.SELF_REPO, p.number, "squash") }
                if (code == 405 || code == 409) {
                    evoStore.savePending(p.copy(state = PrState.CONFLICT))
                    evoStatus = "Merge yapılamadı (çakışma, HTTP $code): $msg. Dal korunuyor."
                    return@launch
                }
                if (code !in 200..299) { evoStatus = "Merge hatası (HTTP $code): $msg"; return@launch }
                withContext(Dispatchers.IO) {
                    runCatching { gh.deleteBranch(EvolutionGuard.SELF_REPO, p.branch) }
                    evoStore.savePending(p.copy(state = PrState.MERGED))
                    if (p.clusterKey.isNotBlank()) evoStore.clusters.mark(p.clusterKey, com.suarez.coder.evolution.ClusterStatus.RESOLVED)
                    evoStore.bump("merged")
                    // A merged topic is done: never propose the same fix again.
                    evoStore.reject(p.topic)
                    evoStore.addLog(EvoTier.SELF_PR, "PR #${p.number} merge edildi: ${p.title}")
                }
                refreshEvolution()
                evoStatus = "Merge edildi. Release derlemesi bekleniyor"
                val ci = withContext(Dispatchers.IO) {
                    agent.tools.execute(com.suarez.coder.llm.ToolCall("evo_release", "ci_wait",
                        org.json.JSONObject().put("repo", EvolutionGuard.SELF_REPO).put("ref", msg).put("timeout_minutes", 30)), allowOps = true)
                }
                val apk = Regex("APK: (\\S+)").find(ci)?.groupValues?.get(1)
                withContext(Dispatchers.IO) { evoStore.savePending(p.copy(state = PrState.MERGED, apkUrl = apk)) }
                evoStatus = if (apk != null) "Yeni sürüm hazır." else "Release derlemesi: " + ci.lines().take(3).joinToString(" ")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                evoStatus = "Hata: ${e.message?.take(200)}"
            } finally {
                evoBusy = false
                refreshEvolution()
            }
        }
    }

    /** Closes the self-PR, deletes its branch and never proposes the same topic again. */
    fun evoReject() {
        val p = evoPending ?: return
        if (evoBusy) return
        evoBusy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val gh = agent.tools.github
                    runCatching { gh.closePullRequest(EvolutionGuard.SELF_REPO, p.number) }
                    runCatching { gh.deleteBranch(EvolutionGuard.SELF_REPO, p.branch) }
                    evoStore.reject(p.topic)
                    if (p.clusterKey.isNotBlank()) evoStore.clusters.mark(p.clusterKey, com.suarez.coder.evolution.ClusterStatus.DISMISSED)
                    evoStore.savePending(null)
                    evoStore.addLog(EvoTier.SELF_PR, "PR #${p.number} reddedildi; \"${p.topic}\" konusu tekrar açılmayacak.")
                }
                evoStatus = "PR #${p.number} kapatıldı."
            } finally {
                evoBusy = false
                refreshEvolution()
            }
        }
    }

    fun evoDismissCluster(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            evoStore.clusters.dismiss(key)
            refreshEvolution()
        }
    }

    fun evoArchiveRule(id: String) {
        viewModelScope.launch(Dispatchers.IO) { evoStore.scopedRules.archive(id); refreshEvolution() }
    }

    fun evoRestoreRule(id: String) {
        viewModelScope.launch(Dispatchers.IO) { evoStore.scopedRules.restore(id); refreshEvolution() }
    }

    fun evoAddRule(globs: String, text: String) {
        val repo = settings.contextRepo.ifBlank { settings.defaultRepo }.ifBlank { "local" }
        viewModelScope.launch(Dispatchers.IO) {
            evoStore.scopedRules.add(repo, globs.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }, text, com.suarez.coder.evolution.RuleSource.MANUAL)
            refreshEvolution()
        }
    }

    fun evoResetCtg() {
        viewModelScope.launch(Dispatchers.IO) { evoStore.ctg.reset(); refreshEvolution() }
    }

    fun evoPinImplementer(modelId: String) {
        settings.pinnedImplementer = if (settings.pinnedImplementer == modelId) "" else modelId
        refreshSettings()
    }

    fun evoApproveSkill(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            agent.tools.skills?.approve(name)
            withContext(Dispatchers.Main) { refreshSkills(); refreshEvolution() }
        }
    }

    fun evoResetAb() {
        viewModelScope.launch(Dispatchers.IO) {
            com.suarez.coder.evolution.StrategyAbOptimizer(java.io.File(evoStore.dir, "ab.json"), settings.architectModel).reset()
            refreshEvolution()
        }
    }

    fun setAbEnabled(on: Boolean) {
        settings.abEnabled = on
        if (on) com.suarez.coder.evolution.StrategyAbOptimizer(java.io.File(evoStore.dir, "ab.json"), settings.architectModel).ensureActive()
        refreshSettings(); refreshEvolution()
    }

    fun setSentinelEnabled(on: Boolean) {
        settings.sentinelEnabled = on
        if (on) com.suarez.coder.evolution.RepoSentinelWorker.schedule(getApplication(), settings.sentinelHours)
        else com.suarez.coder.evolution.RepoSentinelWorker.cancel(getApplication())
        refreshSettings()
    }

    fun setSentinelHours(hours: Int) {
        settings.sentinelHours = hours
        if (settings.sentinelEnabled) com.suarez.coder.evolution.RepoSentinelWorker.schedule(getApplication(), settings.sentinelHours)
        refreshSettings()
    }

    fun setSentinelRepos(text: String) { settings.sentinelRepos = text; refreshSettings() }

    fun setSentinelBudget(usd: Double) { settings.sentinelBudget = usd; refreshSettings() }

    fun setSentinelDraft(on: Boolean) { settings.sentinelDraft = on; refreshSettings() }

    /** Hides a merged PR card once its APK was offered. */
    fun evoDismiss() {
        viewModelScope.launch(Dispatchers.IO) { evoStore.savePending(null); refreshEvolution() }
    }

    private fun sync(work: List<ChatMessage>) {
        if (messages.size <= work.size && messages.indices.all { messages[it] === work[it] }) {
            for (i in messages.size until work.size) messages += work[i]
        } else {
            messages.clear(); messages.addAll(work)
        }
    }

    fun answer(ok: Boolean) { pending?.answer?.complete(ok) }

    // ---- Orchestrator ----

    fun setOrchestratorMode(mode: OrchestratorMode) { settings.orchestratorMode = mode.name; refreshSettings() }

    /** "Planı Başlat" / "Devam Et". */
    fun startPlan() {
        val p = activePlan ?: return
        if (running || p.state.finished || p.state == PlanState.RUNNING) return
        pauseRequested = false
        launchRun { work, agentUi -> agent.executePlan(work, agentUi, p) }
    }

    /** "Duraklat": finishes the current subtask, then stops cleanly. */
    fun pausePlan() { if (activePlan?.state == PlanState.RUNNING) pauseRequested = true }

    fun cancelPlan() {
        val p = activePlan ?: return
        if (p.state == PlanState.RUNNING) stop()
        pauseRequested = false
        val cancelled = p.copy(state = PlanState.CANCELLED, note = "İptal edildi.", staged = emptyMap())
        planStore.save(currentSessionId, cancelled)
        activePlan = cancelled
    }

    /** Saves the user's edits (skipped subtasks, changed descriptions) before starting. */
    fun editPlan(updated: OrchestratorPlan) {
        if (running) return
        planStore.save(currentSessionId, updated)
        activePlan = updated
    }

    /**
     * "Mimar'a Sor": the only way the architect sees an implementation failure, and only when the user asks.
     * The paused plan is closed and a new architect turn starts with the error summary.
     */
    fun askArchitect() {
        val p = activePlan ?: return
        if (running) return
        val failed = p.tasks.filter { it.status == com.suarez.coder.orchestrator.TaskStatus.FAILED || it.error != null }
        val text = "Uygulayıcı şu işi tamamlayamadı; planı düzelt veya daha net talimat ver.\n\n" +
            failed.joinToString("\n\n") { t -> "${t.title} [${t.ownedFiles.joinToString()}]\nHata: ${t.error?.take(800)}" }
        val cancelled = p.copy(state = PlanState.CANCELLED, note = "Mimar'a soruldu.")
        planStore.save(currentSessionId, cancelled)
        activePlan = cancelled
        messages += ChatMessage.User(text)
        save()
        start(Route.COMPLEX_ARCHITECT)
    }

    fun dismissPlan() {
        if (activePlan?.state == PlanState.RUNNING) return
        planStore.delete(currentSessionId)
        activePlan = null
    }

    fun stop() { job?.cancel() }

    /** Starts a fresh chat; the current one stays in the history. */
    fun newChat() {
        timeline = emptyList()
        save(now = true)
        releaseForegroundAgent()
        running = false; status = null
        currentSessionId = sessionStore.newId()
        messages.clear()
        refreshBackground()
        preview = null
        activePlan = null
        pauseRequested = false
    }

    fun openSession(id: String) {
        if (id == currentSessionId) return
        viewModelScope.launch(Dispatchers.IO) { val t = spanStore.load(id); withContext(Dispatchers.Main) { if (currentSessionId == id) timeline = t } }
        save(now = true)
        releaseForegroundAgent()
        currentSessionId = id
        messages.clear()
        val live = runs[id]
        if (live != null) {
            // Back to a chat that is still running: show its live messages and give it back its agent.
            agent = live.agent
            messages.addAll(live.work)
            running = true
        } else {
            messages.addAll(sessionStore.load(id))
            messages.addAll(com.suarez.coder.security.Guard.closeDangling(messages))
            running = false; status = null
        }
        refreshBackground()
        preview = null
        activePlan = planStore.recover(id)
        pauseRequested = false
    }

    fun deleteSession(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionStore.delete(id)
            planStore.delete(id)
            withContext(Dispatchers.Main) {
                sessions = sessionStore.list()
                runs[id]?.job?.cancel()
                if (id == currentSessionId) { currentSessionId = sessionStore.newId(); messages.clear(); releaseForegroundAgent(); running = false; activePlan = null }
            }
        }
    }

    suspend fun searchSessions(query: String): List<SessionMeta> = withContext(Dispatchers.IO) { sessionStore.search(query) }

    // ---- Project profiles ----

    fun activateProfile(id: String) {
        settings.activateProfile(id)
        afterProfileChange()
    }

    fun saveProfile(p: ProjectProfile) {
        settings.upsertProfile(p)
        afterProfileChange()
    }

    fun deleteProfile(id: String) {
        settings.deleteProfile(id)
        afterProfileChange()
    }

    private fun afterProfileChange() {
        profiles = settings.profiles()
        agent.context.invalidate()
        agent.invalidateSnapshot()
        refreshSettings()
    }

    // ---- Cost dashboard ----

    suspend fun finOpsSummary(): com.suarez.coder.cost.FinOpsReport.Summary = withContext(Dispatchers.IO) {
        com.suarez.coder.cost.FinOpsReport.summarize(evoStore.traces())
    }

    suspend fun costBreakdown(): CostBreakdown = withContext(Dispatchers.IO) {
        // Evolution runs bill outside the chat; they appear as their own role.
        val evo = evoStore.log().filter { it.costUsd > 0 }.map {
            com.suarez.coder.cost.LedgerEntry(it.at, "evolution", "(öz-gelişim)", "evolution", settings.evoModel, 0, 0, 0, it.costUsd)
        }
        Ledger.aggregate(ledger.readAll() + evo)
    }

    private fun repoNow() = settings.contextRepo.ifBlank { settings.defaultRepo }

    /** Loads the logged keys; on first use imports the costs of the existing chats once. */
    private fun initLedger() {
        val fresh = !File(getApplication<Application>().filesDir, "ledger.jsonl").exists()
        if (fresh) {
            val imported = sessionStore.list().flatMap { meta ->
                sessionStore.load(meta.id).filterIsInstance<ChatMessage.UsageRecord>().map { r -> entryFor(meta.id, meta.repo, r) }
            }
            ledger.append(imported)
        }
        synchronized(loggedKeys) { loggedKeys.addAll(ledger.keys()) }
    }

    private fun entryFor(session: String, repo: String, r: ChatMessage.UsageRecord) = LedgerEntry(
        at = r.at, session = session, repo = repo, role = r.role, model = r.model,
        input = r.usage.input, cached = r.usage.cached, output = r.usage.output + r.usage.thoughts, usd = agent.costOf(r),
    )

    /** Debounced, free countTokens preview of what the next request would cost. */
    fun onInputChanged(text: String) {
        previewJob?.cancel()
        if (!settings.tokenPreview || text.isBlank() || running) { preview = null; return }
        previewJob = viewModelScope.launch {
            delay(1200)
            preview = try {
                val p = agent.preview(messages.toList(), text)
                val c = p.costUsd
                (if (p.exact) "≈ " else "~ ") + "${Pricing.formatTokens(p.tokens)} giriş token · ${Pricing.formatUsd(c)} " +
                    "(${Pricing.formatTry(c * settings.usdTry)}) · ${p.model}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Token önizlemesi yok: ${e.message?.take(80)}"
            }
        }
    }

    suspend fun fetchModels(provider: Provider): List<String> {
        val client = agent.client(agent.singleRef(provider))
        if (provider != Provider.OPENROUTER || client !is OpenAiCompatClient) return client.listModels()
        // OpenRouter lists hundreds of models with prices: keep those that can call tools and remember their prices.
        val models = withContext(Dispatchers.IO) { OpenRouterCatalog.parse(client.modelsBody()) }.filter { it.tools }
        settings.openrouterMeta = OpenRouterCatalog.toMeta(models)
        return models.map { it.id }.sorted()
    }

    /** Stores fetched models; picks architect/editor defaults by price when the current ones are not available. */
    fun mergeCatalog(p: Provider, models: List<String>) {
        val prefix = p.name.lowercase() + "/"
        settings.modelCatalog = settings.modelCatalog.filter { !it.startsWith(prefix) } + models.map { prefix + it }
        settings.catalogAt = System.currentTimeMillis()
        // The user's choices are never replaced by a list refresh; only empty roles get a sensible default.
        if (p == Provider.GEMINI && models.isNotEmpty()) {
            if (settings.architectModel.isBlank()) {
                (models.firstOrNull { it == "gemini-4-neon" } ?: models.firstOrNull { it.contains("pro") })?.let { settings.architectModel = prefix + it }
            }
            if (settings.editorModel.isBlank()) {
                models.filter { it.contains("flash") && !it.contains("tts") && !it.contains("image") && !it.contains("live") }
                    .minByOrNull { Pricing.blended(agent.price(it)) }?.let { settings.editorModel = prefix + it }
            }
        }
        refreshSettings()
    }

    var catalogStatus by mutableStateOf<String?>(null)
        private set

    /** Fetches model lists from every provider that has a key, so both dropdowns can mix providers. */
    fun refreshCatalog(quiet: Boolean = false) {
        viewModelScope.launch {
            if (quiet && Provider.entries.none { settings.keyFor(it).isNotBlank() }) return@launch
            val parts = ArrayList<String>()
            for (p in Provider.entries) {
                val hasKey = settings.keyFor(p).isNotBlank()
                if (!hasKey) continue
                parts += runCatching { fetchModels(p).also { mergeCatalog(p, it) }.size.let { "${p.label}: $it" } }
                    .getOrElse { "${p.label}: hata" }
            }
            if (!quiet) catalogStatus = if (parts.isEmpty()) "Önce API anahtarı girin" else parts.joinToString(", ")
            checkPrices()
        }
    }

    /** Pings the single, architect and editor models. */
    suspend fun testConnections(): String {
        val refs = listOf(agent.singleRef(), agent.architectRef(), agent.editorRef()).distinctBy { it.key }
        return refs.map { agent.ping(it) }.joinToString("\n")
    }

    suspend fun loadTree(repo: String, ref: String?): List<String> =
        agent.tools.filteredTree(agent.tools.normalizeRepo(repo), ref?.ifBlank { null }).map { it.path }

    fun saveContext(repo: String, ref: String, files: List<String>) {
        settings.contextRepo = repo
        settings.contextRef = ref
        settings.contextFiles = files
        agent.context.invalidate()
        agent.invalidateSnapshot()
        refreshSettings()
    }

    // ---- Timeline: where a task's time and money went ----

    private val spanStore = com.suarez.coder.agent.SpanStore(File(app.filesDir, "chats/spans"))
    var timeline by mutableStateOf<List<com.suarez.coder.agent.Span>>(emptyList())
        private set

    // Kotlin runs initializers in source order: after the timeline state.
    init { viewModelScope.launch(Dispatchers.IO) { val t = spanStore.load(currentSessionId); withContext(Dispatchers.Main) { timeline = t } } }

    // ---- Saving (SessionSaver) ----

    private val saver = SessionSaver(viewModelScope, sessionStore, ledger, File(app.filesDir, "ledger.jsonl"), loggedKeys,
        { id, repo, r -> entryFor(id, repo, r) }, { agent.sessionCost(it) }, { repoNow() },
        { list -> withContext(Dispatchers.Main) { sessions = list } })

    private fun save(now: Boolean = false) = saver.save(currentSessionId, messages, now)
    private fun saveSession(sid: String, work: List<ChatMessage>, now: Boolean = false) = saver.save(sid, work, now)

    // ---- Task queue (QueueController) ----

    val queueCtl = QueueController(app, viewModelScope) { agent }
    val queue get() = queueCtl.queue
    fun refreshQueue() = queueCtl.refresh()
    fun enqueue(text: String, repo: String) = queueCtl.enqueue(text, repo)
    fun runQueueNow() = queueCtl.runNow()
    fun removeQueued(id: String) = queueCtl.remove(id)
    fun requeue(id: String) = queueCtl.requeue(id)
    fun queueSettingsChanged() = queueCtl.settingsChanged()

    /** Called when the app goes to the background: nothing typed or generated may wait in memory. */
    fun flushSaves() = saver.flush()

    override fun onCleared() {
        saver.flushBlocking()
        super.onCleared()
    }
}
