package com.suarez.coder.ui

import android.app.Application
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

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    val agent = Agent(settings)
    private val chatFile = File(app.filesDir, "chat.json")

    val messages = mutableStateListOf<ChatMessage>()
    var running by mutableStateOf(false)
        private set
    var pending by mutableStateOf<PendingConfirm?>(null)
        private set
    var preview by mutableStateOf<String?>(null)
        private set
    /** Bumped whenever settings shown on the chat screen change. */
    var settingsVersion by mutableStateOf(0)
        private set

    private var job: Job? = null
    private var previewJob: Job? = null

    init {
        runCatching { if (chatFile.exists()) messages.addAll(MessageJson.fromJson(chatFile.readText())) }
    }

    fun refreshSettings() { settingsVersion++ }

    val sessionCostText: String
        get() {
            val c = agent.sessionCost(messages)
            return "Oturum ${Pricing.formatUsd(c)} (${Pricing.formatTry(c * settings.usdTry)})"
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

    // ---- Sending ----

    private val ui = object : AgentUi {
        override fun update() {}
        override suspend fun confirm(title: String, body: String, okText: String, cancelText: String): Boolean {
            val p = PendingConfirm(title, body, okText, cancelText, CompletableDeferred())
            pending = p
            return try { p.answer.await() } finally { pending = null }
        }
    }

    fun send(text: String) {
        if (text.isBlank() || running) return
        previewJob?.cancel(); preview = null
        messages += ChatMessage.User(text.trim())
        save()
        running = true
        job = viewModelScope.launch {
            val work = messages.toMutableList()
            val agentUi = object : AgentUi by ui {
                override fun update() { sync(work); save() }
            }
            try {
                agent.run(work, agentUi)
            } catch (e: CancellationException) {
                work += ChatMessage.Notice("Durduruldu.")
            } catch (e: StopException) {
                work += ChatMessage.Notice(e.message ?: "Durduruldu.")
            } catch (e: Exception) {
                work += ChatMessage.Notice("Hata: ${e.message ?: e.toString()}", isError = true)
            } finally {
                sync(work)
                pending = null
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

    suspend fun loadTree(repo: String, ref: String?): List<String> =
        agent.tools.filteredTree(agent.tools.normalizeRepo(repo), ref?.ifBlank { null }).map { it.path }

    fun saveContext(repo: String, ref: String, files: List<String>) {
        settings.contextRepo = repo
        settings.contextRef = ref
        settings.contextFiles = files
        agent.context.invalidate()
        refreshSettings()
    }

    private fun save() {
        val snapshot = MessageJson.toJson(messages.toList())
        viewModelScope.launch(Dispatchers.IO) { runCatching { chatFile.writeText(snapshot) } }
    }
}
