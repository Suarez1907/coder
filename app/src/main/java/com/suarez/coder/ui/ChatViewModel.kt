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
    val agent = Agent(settings, app.filesDir)
    private val sessionStore = SessionStore(File(app.filesDir, "chats"), legacyChat = File(app.filesDir, "chat.json"))
    private val ledger = Ledger(File(app.filesDir, "ledger.jsonl"))
    private val loggedKeys = HashSet<String>()
    private val planStore = OrchestratorStore(File(app.filesDir, "orchestrator"))
    val evoStore = EvolutionEngine.storeFor(app)

    /** Self-evolution screen state. */
    var evoPending by mutableStateOf<PendingSelfPr?>(null)
        private set
    var evoLog by mutableStateOf<List<EvolutionLogEntry>>(emptyList())
        private set
    var evoStatus by mutableStateOf<String?>(null)
        private set
    var evoBusy by mutableStateOf(false)
        private set
    /** Last trace of this app run, so a corrective follow-up message can be attached to it. */
    private var lastTraceId: String? = null

    /** Orchestrator plan of the chat on screen (shown as a card), or null. */
    var activePlan by mutableStateOf<OrchestratorPlan?>(null)
        private set
    /** Set by "Duraklat": the run stops after the current subtask. */
    var pauseRequested by mutableStateOf(false)
        private set
    /** Session the running job belongs to (the user may switch chats while it finishes). */
    private var jobSession = ""

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

    private var job: Job? = null
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
        sessions = sessionStore.list()
        profiles = settings.profiles()
        activePlan = planStore.recover(currentSessionId)
        agent.lessonsFor = { repo -> if (settings.evoEnabled && settings.evoMemory) evoStore.lessons(repo) else null }
        agent.adaptiveRouting = { if (settings.evoEnabled && settings.evoRouting) evoStore.routing.current() else AdaptiveRouting() }
        refreshEvolution()
        refreshSkills()
        agent.orchestratorHooks = object : OrchestratorHooks {
            override fun save(plan: OrchestratorPlan) {
                val session = jobSession.ifBlank { currentSessionId }
                planStore.save(session, plan)
                if (session == currentSessionId) activePlan = plan
            }
            override fun pauseRequested() = pauseRequested
            override fun clearPause() { pauseRequested = false }
        }
        viewModelScope.launch(Dispatchers.IO) { initLedger() }
        // Keep the model lists fresh in the background (once a day), so nobody has to press "Modelleri getir".
        if (System.currentTimeMillis() - settings.catalogAt > 24 * 3_600_000L) refreshCatalog(quiet = true)
        agent.tools.ciListener = object : ToolRegistry.CiListener {
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
        if ((text.isBlank() && attachments.isEmpty()) || running) return
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
        messages += ChatMessage.User(expandSkillCommand(text.trim()).ifBlank { "(Ekran görüntüsüne bak.)" }, images)
        save()
        // "Hayır, olmadı" right after a run is a correction signal for the previous trace.
        lastTraceId?.takeIf { SessionTraceRecorder.isCorrection(text) }?.let { id ->
            viewModelScope.launch(Dispatchers.IO) { evoStore.updateTrace(id) { t -> if (t.processed) t else t.copy(userCorrection = true) } }
        }
        lastTraceId = null
        start(null)
    }

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
        running = true
        jobSession = currentSessionId
        job = viewModelScope.launch {
            val work = messages.toMutableList()
            val runStart = work.size
            val agentUi = object : AgentUi by ui {
                override fun update() { sync(work); save() }
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
                sync(work)
                pending = null
                status = null
                running = false
                save()
                recordTrace(work, runStart)
            }
        }
    }

    // ---- Skills & cloud tools ----

    var skills by mutableStateOf<List<com.suarez.coder.skills.Skill>>(emptyList())
        private set
    var cloudInstalls by mutableStateOf<List<com.suarez.coder.cloudtools.CloudToolRunner.Install>?>(null)
        private set
    var skillsStatus by mutableStateOf<String?>(null)
        private set

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

    // ---- Self-evolution ----

    /** Saves a trace of the finished run (never for evolution runs) and queues the background evolution worker. */
    private fun recordTrace(work: List<ChatMessage>, runStart: Int) {
        val run = work.drop(runStart)
        val request = (work.take(runStart).lastOrNull { it is ChatMessage.User } as? ChatMessage.User)?.text.orEmpty()
        val trace = SessionTraceRecorder.record(run, request, currentSessionId, agent.tools.normalizeRepo(repoNow()),
            agent.isEvolutionSession, agent::costOf) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            evoStore.addTrace(trace)
            lastTraceId = trace.id
            if (settings.evoEnabled) EvolutionWorker.schedule(getApplication(), settings.evoWifiOnly)
        }
    }

    fun refreshEvolution() {
        viewModelScope.launch(Dispatchers.IO) {
            val p = evoStore.pending()
            val log = evoStore.log().asReversed()
            withContext(Dispatchers.Main) { evoPending = p; evoLog = log }
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
            evoStore.routing.undo(prev)
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
                    evoStore.bump("merged")
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
        stop()
        save()
        currentSessionId = sessionStore.newId()
        messages.clear()
        agent.resetSession()
        preview = null
        activePlan = null
        pauseRequested = false
    }

    fun openSession(id: String) {
        if (id == currentSessionId) return
        stop()
        save()
        currentSessionId = id
        messages.clear()
        messages.addAll(sessionStore.load(id))
        agent.resetSession()
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
                if (id == currentSessionId) { currentSessionId = sessionStore.newId(); messages.clear(); agent.resetSession(); activePlan = null }
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

    private fun save() {
        val snapshot = messages.toList()
        val id = currentSessionId
        val repo = repoNow()
        val cost = agent.sessionCost(snapshot)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sessionStore.save(id, snapshot, repo, cost) }
            // New cost records go to the permanent ledger exactly once.
            val fresh = snapshot.filterIsInstance<ChatMessage.UsageRecord>().filter { r ->
                synchronized(loggedKeys) { loggedKeys.add(Ledger.key(id, r.at, r.role, r.model)) }
            }
            runCatching { ledger.append(fresh.map { entryFor(id, repo, it) }) }
            val list = sessionStore.list()
            withContext(Dispatchers.Main) { sessions = list }
        }
    }
}
