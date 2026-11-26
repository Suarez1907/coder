package com.suarez.coder.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.QueuedTask
import com.suarez.coder.queue.QueueWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The unattended task queue as the UI sees it: list, add, run now, retry, remove. */
class QueueController(private val app: Application, private val scope: CoroutineScope, private val agent: () -> Agent) {
    private val store = QueueWorker.store(app)
    var queue by mutableStateOf<List<QueuedTask>>(emptyList())
        private set

    fun refresh() {
        scope.launch(Dispatchers.IO) { val l = store.all(); withContext(Dispatchers.Main) { queue = l } }
    }

    fun enqueue(text: String, repo: String) {
        val tools = agent().tools
        val r = tools.normalizeRepo(repo.ifBlank { tools.activeRepo() })
        if (text.isBlank() || !r.contains('/')) return
        store.add(QueuedTask("t${System.currentTimeMillis()}", r, text.trim()))
        QueueWorker.schedule(app)
        refresh()
    }

    fun runNow() = QueueWorker.schedule(app, now = true)
    fun remove(id: String) { store.remove(id); refresh() }
    fun requeue(id: String) {
        store.update(id) { it.copy(state = QueuedTask.State.QUEUED, note = "") }
        QueueWorker.schedule(app)
        refresh()
    }
    fun settingsChanged() = QueueWorker.schedulePeriodic(app)
}
