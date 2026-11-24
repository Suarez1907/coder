package com.suarez.coder

import com.suarez.coder.cost.Ledger
import com.suarez.coder.cost.LedgerEntry
import com.suarez.coder.data.SessionStore
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.Usage
import com.suarez.coder.ui.SessionSaver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionSaverTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun coalescesWritesAndLogsEachCostOnce() = runBlocking {
        val store = SessionStore(tmp.newFolder("chats"))
        val ledgerFile = java.io.File(tmp.root, "ledger.jsonl")
        val ledger = Ledger(ledgerFile)
        var lists = 0
        val saver = SessionSaver(this, store, ledger, ledgerFile, HashSet(),
            { id, repo, r -> LedgerEntry(r.at, id, repo, r.role, r.model, r.usage.input, r.usage.cached, r.usage.output, 0.01) },
            { 0.0 }, { "me/app" }, { lists++ }, debounceMs = 50)
        val rec = ChatMessage.UsageRecord("m", "agent", Usage(10, 1), at = 1)
        val msgs = mutableListOf<ChatMessage>(ChatMessage.User("merhaba"), rec)
        repeat(20) { msgs += ChatMessage.Notice("adım $it"); saver.save("s1", msgs) }
        saver.writePending()
        assertEquals(1, lists) // twenty updates, one write
        assertEquals(msgs.size, store.load("s1").size)
        // Another writer (the queue worker) appends to the ledger: the same record is not logged twice.
        ledger.append(listOf(LedgerEntry(2, "q-1", "me/app", "agent", "m", 5, 0, 1, 0.02)))
        saver.save("s1", msgs + ChatMessage.Notice("son"), now = true)
        saver.writePending()
        kotlinx.coroutines.delay(100)
        assertEquals(2, ledger.readAll().size) // s1's record once + the worker's
    }
}
