package com.suarez.coder.ui

import com.suarez.coder.cost.Ledger
import com.suarez.coder.cost.LedgerEntry
import com.suarez.coder.data.SessionMeta
import com.suarez.coder.data.SessionStore
import com.suarez.coder.llm.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Coalesced chat saving: a long chat is megabytes of JSON and the agent updates many times per step, so writes are
 * gathered (at most one per [debounceMs]); [save] with now=true writes at once (end of a run, chat switch). New cost
 * records go to the permanent ledger exactly once, also when the queue worker appended its own meanwhile.
 */
class SessionSaver(
    private val scope: CoroutineScope,
    private val store: SessionStore,
    private val ledger: Ledger,
    private val ledgerFile: File,
    private val loggedKeys: MutableSet<String>,
    private val entryFor: (session: String, repo: String, ChatMessage.UsageRecord) -> LedgerEntry,
    private val sessionCost: (List<ChatMessage>) -> Double,
    private val repoNow: () -> String,
    private val onSessions: suspend (List<SessionMeta>) -> Unit,
    private val debounceMs: Long = 1_200L,
) {
    private class Pending(val messages: List<ChatMessage>, val repo: String, val cost: Double)
    private val pending = LinkedHashMap<String, Pending>()
    private var job: Job? = null
    private val lock = Mutex()
    private var ledgerLen = -1L

    fun save(sid: String, work: List<ChatMessage>, now: Boolean = false) {
        val snapshot = work.toList()
        synchronized(pending) { pending[sid] = Pending(snapshot, repoNow(), sessionCost(snapshot)) }
        if (now) { job?.cancel(); job = scope.launch(Dispatchers.IO) { writePending() }; return }
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            delay(debounceMs)
            writePending()
        }
    }

    /** Writes the latest snapshot of every chat with unsaved changes, and their new cost records to the ledger. */
    suspend fun writePending(refreshList: Boolean = true) = lock.withLock {
        val batch = synchronized(pending) { LinkedHashMap(pending).also { pending.clear() } }
        if (batch.isEmpty()) return@withLock
        // The queue worker appends to the ledger too: pick up its keys so nothing is counted twice.
        if (ledgerFile.length() != ledgerLen) runCatching { synchronized(loggedKeys) { loggedKeys.addAll(ledger.keys()) } }
        for ((id, p) in batch) {
            runCatching { store.save(id, p.messages, p.repo, p.cost) }
            val fresh = p.messages.filterIsInstance<ChatMessage.UsageRecord>().filter { r ->
                synchronized(loggedKeys) { loggedKeys.add(Ledger.key(id, r.at, r.role, r.model)) }
            }
            runCatching { ledger.append(fresh.map { entryFor(id, p.repo, it) }) }
        }
        ledgerLen = ledgerFile.length()
        if (refreshList) onSessions(store.list())
    }

    fun flush() { scope.launch(Dispatchers.IO) { writePending() } }

    /** On teardown, from the main thread: no hop back to it. */
    fun flushBlocking() { kotlinx.coroutines.runBlocking(Dispatchers.IO) { runCatching { writePending(refreshList = false) } } }
}
