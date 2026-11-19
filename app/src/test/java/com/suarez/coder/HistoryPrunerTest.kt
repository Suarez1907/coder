package com.suarez.coder

import com.suarez.coder.context.HistoryPruner
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPrunerTest {
    private fun turn(n: Int, resultSize: Int = 5000): List<ChatMessage> = listOf(
        ChatMessage.User("istek $n"),
        ChatMessage.Assistant("", listOf(ToolCall("c$n", "github_read_file", JSONObject().put("path", "f$n.kt")))),
        ChatMessage.ToolResult("c$n", "github_read_file", "x".repeat(resultSize)),
        ChatMessage.Assistant("cevap $n\n```kotlin\n" + "satir\n".repeat(20) + "```"),
        ChatMessage.UsageRecord("m", "agent", com.suarez.coder.llm.Usage(1, 1)),
    )

    @Test fun keepsRecentTurnsFullAndCompactsOld() {
        val all = (1..6).flatMap { turn(it) }
        val p = HistoryPruner.prepare(all, keepTurns = 3)
        // No UsageRecords sent.
        assertTrue(p.messages.none { it is ChatMessage.UsageRecord })
        val results = p.messages.filterIsInstance<ChatMessage.ToolResult>()
        assertEquals(6, results.size) // pairing preserved
        assertTrue(results.take(3).all { it.content.length < 400 })
        assertTrue(results.takeLast(1).all { it.content.length == 5000 }) // current turn: full
        assertTrue(results.subList(3, 5).all { it.content.length in 2000..2200 }) // earlier turns in window: capped
        val oldAnswer = p.messages.filterIsInstance<ChatMessage.Assistant>().first { it.text.startsWith("cevap 1") }
        assertTrue(oldAnswer.text.contains("kod bloğu çıkarıldı"))
        assertEquals(12, p.summarizable.size)
        val sentSize = HistoryPruner.charSize(p.messages)
        val fullSize = HistoryPruner.charSize(all)
        assertTrue("sent=$sentSize full=$fullSize", sentSize < fullSize * 0.6)
    }

    @Test fun summaryReplacesEarlierMessages() {
        val all = turn(1) + ChatMessage.Summary("özet metni") + turn(2)
        val p = HistoryPruner.prepare(all, keepTurns = 4)
        val first = p.messages.first() as ChatMessage.User
        assertTrue(first.text.startsWith("[Önceki konuşmanın özeti]"))
        assertTrue(first.text.contains("istek 2"))
        assertTrue(p.messages.none { it is ChatMessage.Assistant && it.text.contains("cevap 1") })
    }

    @Test fun currentTurnKeepsOnlyLatestResultsFull() {
        val msgs = ArrayList<ChatMessage>()
        msgs += ChatMessage.User("tek istek")
        repeat(10) { i ->
            msgs += ChatMessage.Assistant("", listOf(ToolCall("c$i", "t", JSONObject())))
            msgs += ChatMessage.ToolResult("c$i", "t", "y".repeat(4000))
        }
        val p = HistoryPruner.prepare(msgs, 4)
        val r = p.messages.filterIsInstance<ChatMessage.ToolResult>()
        assertEquals(10, r.size)
        assertEquals(4, r.count { it.content.length < 2000 })
        assertEquals(6, r.count { it.content.length == 4000 })
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
