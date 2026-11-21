package com.suarez.coder.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import com.suarez.coder.apk.ApkDownloader
import com.suarez.coder.apk.ApkTarget
import com.suarez.coder.ci.CiWatchService
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
    private val chatFile = File(app.filesDir, "chat.json")

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
        runCatching { if (chatFile.exists()) messages.addAll(MessageJson.fromJson(chatFile.readText())) }
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

    fun refreshSettings() { settingsVersion++ }

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
        keys += listOf(agent.singleRef(Provider.GEMINI).key, agent.singleRef(Provider.DEEPSEEK).key, settings.architectModel, settings.editorModel)
        return keys.mapNotNull { ModelRef.parse(it) }.sortedWith(compareBy({ it.provider.ordinal }, { it.model }))
    }

    fun selectSingle(ref: ModelRef) {
        settings.provider = ref.provider
        if (ref.provider == Provider.GEMINI) settings.geminiModel = ref.model else settings.deepseekModel = ref.model
        refreshSettings()
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
            visionWarning = "Seçili model(ler)den biri görselleri göremiyor (DeepSeek). Görseller bu modellere gönderilmez; " +
                "yalnızca bir not eklenir. Yine de gönderilsin mi? Görsel için Gemini model seçin."
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
        return refs.all { it.provider == Provider.GEMINI }
    }

    private fun doSend(text: String) {
        previewJob?.cancel(); preview = null
        val images = attachments.toList()
        attachments.clear()
        messages += ChatMessage.User(text.trim().ifBlank { "(Ekran görüntüsüne bak.)" }, images)
        save()
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

    private fun start(override: Route?) {
        running = true
        job = viewModelScope.launch {
            val work = messages.toMutableList()
            val agentUi = object : AgentUi by ui {
                override fun update() { sync(work); save() }
            }
            try {
                agent.run(work, agentUi, override)
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
            }
        }
    }

    private fun sync(work: List<ChatMessage>) {
        if (messages.size <= work.size && messages.indices.all { messages[it] === work[it] }) {
            for (i in messages.size until work.size) messages += work[i]
        } else {
            messages.clear(); messages.addAll(work)
        }
    }

    fun answer(ok: Boolean) { pending?.answer?.complete(ok) }

    fun stop() { job?.cancel() }

    fun newChat() {
        stop()
        messages.clear()
        agent.resetSession()
        preview = null
        save()
    }

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

    suspend fun fetchModels(provider: Provider): List<String> = agent.client(agent.singleRef(provider)).listModels()

    /** Stores fetched models; picks architect/editor defaults by price when the current ones are not available. */
    fun mergeCatalog(p: Provider, models: List<String>) {
        val prefix = p.name.lowercase() + "/"
        settings.modelCatalog = settings.modelCatalog.filter { !it.startsWith(prefix) } + models.map { prefix + it }
        if (p == Provider.GEMINI && models.isNotEmpty()) {
            val known = models.toSet()
            if (settings.architectModel.startsWith(prefix) && settings.architectModel.removePrefix(prefix) !in known) {
                (models.firstOrNull { it == "gemini-4-neon" } ?: models.firstOrNull { it.contains("pro") })?.let { settings.architectModel = prefix + it }
            }
            if (settings.editorModel.startsWith(prefix) && settings.editorModel.removePrefix(prefix) !in known) {
                models.filter { it.contains("flash") && !it.contains("tts") && !it.contains("image") && !it.contains("live") }
                    .minByOrNull { Pricing.blended(agent.price(it)) }?.let { settings.editorModel = prefix + it }
            }
        }
        refreshSettings()
    }

    var catalogStatus by mutableStateOf<String?>(null)
        private set

    /** Fetches model lists from every provider that has a key, so both dropdowns can mix providers. */
    fun refreshCatalog() {
        viewModelScope.launch {
            val parts = ArrayList<String>()
            for (p in Provider.entries) {
                val hasKey = if (p == Provider.GEMINI) settings.geminiKey.isNotBlank() else settings.deepseekKey.isNotBlank()
                if (!hasKey) continue
                parts += runCatching { fetchModels(p).also { mergeCatalog(p, it) }.size.let { "${p.label}: $it" } }
                    .getOrElse { "${p.label}: hata" }
            }
            catalogStatus = if (parts.isEmpty()) "Önce API anahtarı girin" else parts.joinToString(", ")
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
        val snapshot = MessageJson.toJson(messages.toList())
        viewModelScope.launch(Dispatchers.IO) { runCatching { chatFile.writeText(snapshot) } }
    }
}
