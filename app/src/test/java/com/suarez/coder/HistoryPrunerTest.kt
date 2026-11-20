package com.suarez.coder

import com.suarez.coder.context.HistoryPruner
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.llm.Usage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPrunerTest {
    private var id = 0
    private fun turn(n: Int, resultSize: Int = 5000): List<ChatMessage> {
        val c = ToolCall("c${id++}", "github_read_file", JSONObject().put("path", "f$n.kt"))
        return listOf(
            ChatMessage.User("istek $n"),
            ChatMessage.Assistant("", listOf(c)),
            ChatMessage.ToolResult(c.id, c.name, "x".repeat(resultSize)),
            ChatMessage.Assistant("cevap $n\n```kotlin\n" + "satir\n".repeat(20) + "```"),
            ChatMessage.UsageRecord("m", "agent", Usage(1, 1)),
        )
    }

    @Test fun withoutCheckpointEverythingIsSentUnchanged() {
        val all = (1..6).flatMap { turn(it) }
        val p = HistoryPruner.prepare(all)
        assertTrue(p.messages.none { it is ChatMessage.UsageRecord })
        assertEquals(6, p.messages.count { it is ChatMessage.ToolResult })
        assertTrue(p.messages.filterIsInstance<ChatMessage.ToolResult>().all { it.content.length == 5000 })
        assertTrue(p.summarizable.isEmpty())
    }

    @Test fun messagesBeforeTheMarkAreCompactedAfterItUnchanged() {
        val all = ArrayList<ChatMessage>()
        (1..3).forEach { all += turn(it) }
        all += ChatMessage.CompactMark
        (4..5).forEach { all += turn(it) }
        val p = HistoryPruner.prepare(all)
        val results = p.messages.filterIsInstance<ChatMessage.ToolResult>()
        assertTrue(results.take(3).all { it.content.length < 400 })
        assertTrue(results.drop(3).all { it.content.length == 5000 })
        assertTrue(p.messages.filterIsInstance<ChatMessage.Assistant>().first { it.text.startsWith("cevap 1") }.text.contains("kod bloğu çıkarıldı"))
        assertEquals(12, p.summarizable.size)
    }

    @Test fun appendOnlyBetweenCheckpoints() {
        // A new step must not change anything already sent: the previous request is a prefix of the next.
        val all = ArrayList<ChatMessage>()
        (1..4).forEach { all += turn(it) }
        val before = HistoryPruner.prepare(all).messages
        all += turn(5)
        val after = HistoryPruner.prepare(all).messages
        assertEquals(before, after.take(before.size))
    }

    @Test fun checkpointOnlyWhenTailIsLargeAndKeepsABoundedRecentPart() {
        val all = ArrayList<ChatMessage>()
        (1..3).forEach { all += turn(it, 1000) }
        assertNull(HistoryPruner.checkpointIndex(all, keepTurns = 2, maxTailChars = 50_000))
        (4..10).forEach { all += turn(it, 10_000) }
        val idx = HistoryPruner.checkpointIndex(all, keepTurns = 2, maxTailChars = 50_000)
        assertNotNull(idx)
        all.add(idx!!, ChatMessage.CompactMark)
        val p = HistoryPruner.prepare(all).messages
        val full = p.filterIsInstance<ChatMessage.ToolResult>().filter { it.content.length == 10_000 }
        // At least the last MIN_FULL_RESULTS results stay complete; the rest was compacted in one go.
        assertTrue(full.size >= HistoryPruner.MIN_FULL_RESULTS)
        assertTrue(full.size <= 3)
        assertNull(HistoryPruner.checkpointIndex(all, keepTurns = 2, maxTailChars = 50_000))
    }

    @Test fun hugeSingleTurnKeepsLastResultsFull() {
        val msgs = ArrayList<ChatMessage>()
        msgs += ChatMessage.User("tek istek")
        repeat(12) { i ->
            msgs += ChatMessage.Assistant("", listOf(ToolCall("c$i", "t", JSONObject())))
            msgs += ChatMessage.ToolResult("c$i", "t", "y".repeat(15_000))
        }
        val idx = HistoryPruner.checkpointIndex(msgs, keepTurns = 4, maxTailChars = 100_000)!!
        msgs.add(idx, ChatMessage.CompactMark)
        val r = HistoryPruner.prepare(msgs).messages.filterIsInstance<ChatMessage.ToolResult>()
        assertEquals(12, r.size)
        assertEquals(HistoryPruner.MIN_FULL_RESULTS, r.count { it.content.length == 15_000 })
    }

    @Test fun summaryAndContextBlocks() {
        val all = turn(1) + ChatMessage.Summary("özet metni") + listOf(
            ChatMessage.User("istek 2"),
            ChatMessage.ContextBlock("### a.kt\n```\nfun a()\n```", "a.kt"),
        )
        val p = HistoryPruner.prepare(all)
        val first = p.messages.first() as ChatMessage.User
        assertTrue(first.text.startsWith("[Önceki konuşmanın özeti]"))
        assertTrue(first.text.contains("istek 2") && first.text.contains("fun a()"))
        assertEquals(1, p.messages.size)
    }
}

class RepairTest {
    @Test fun fillsMissingResultsAndDropsOrphans() {
        val list: MutableList<ChatMessage> = mutableListOf(
            ChatMessage.ToolResult("x", "t", "orphan"),
            ChatMessage.User("u"),
            ChatMessage.Assistant("", listOf(ToolCall("a", "t", JSONObject()), ToolCall("b", "t", JSONObject()))),
            ChatMessage.ToolResult("a", "t", "ok"),
            ChatMessage.User("next"),
        )
        HistoryPruner.repairToolPairs(list)
        assertEquals(listOf("User", "Assistant", "ToolResult", "ToolResult", "User"), list.map { it::class.simpleName })
        assertEquals("b", (list[3] as ChatMessage.ToolResult).callId)
    }
}
