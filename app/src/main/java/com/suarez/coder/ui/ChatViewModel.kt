package com.suarez.coder.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suarez.coder.agent.Agent
import com.suarez.coder.data.Provider
import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.MessageJson
import com.suarez.coder.llm.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class PendingApproval(val call: ToolCall, val answer: CompletableDeferred<Boolean>)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val agent = Agent(settings)
    private val chatFile = File(app.filesDir, "chat.json")

    val messages = mutableStateListOf<ChatMessage>()
    var running by mutableStateOf(false)
        private set
    var pendingApproval by mutableStateOf<PendingApproval?>(null)
        private set
    var modelLabel by mutableStateOf(label())
        private set

    private var job: Job? = null

    init {
        runCatching { if (chatFile.exists()) messages.addAll(MessageJson.fromJson(chatFile.readText())) }
    }

    private fun label() = "${settings.provider.label} · ${settings.currentModel}"

    fun refreshLabel() { modelLabel = label() }

    fun setProvider(p: Provider) {
        settings.provider = p
        refreshLabel()
    }

    fun send(text: String) {
        if (text.isBlank() || running) return
        messages += ChatMessage.User(text.trim())
        save()
        running = true
        job = viewModelScope.launch {
            val work = messages.toMutableList()
            try {
                agent.run(work, onUpdate = {
                    // Mirror new items into the observable list.
                    for (i in messages.size until work.size) messages += work[i]
                    save()
                }, approve = { call ->
                    val p = PendingApproval(call, CompletableDeferred())
                    pendingApproval = p
                    try { p.answer.await() } finally { pendingApproval = null }
                })
            } catch (e: CancellationException) {
                messages += ChatMessage.Notice("Durduruldu.")
            } catch (e: Exception) {
                messages += ChatMessage.Notice("Hata: ${e.message ?: e.toString()}")
            } finally {
                pendingApproval = null
                running = false
                save()
            }
        }
    }

    fun answerApproval(ok: Boolean) {
        pendingApproval?.answer?.complete(ok)
    }

    fun stop() {
        job?.cancel()
    }

    fun newChat() {
        stop()
        messages.clear()
        save()
    }

    suspend fun fetchModels(provider: Provider): List<String> = agent.client(provider).listModels()

    private fun save() {
        val snapshot = MessageJson.toJson(messages.toList())
        viewModelScope.launch(Dispatchers.IO) { runCatching { chatFile.writeText(snapshot) } }
    }
}
