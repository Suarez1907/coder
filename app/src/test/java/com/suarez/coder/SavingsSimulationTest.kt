package com.suarez.coder

import com.suarez.coder.context.HistoryPruner
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simulates a coding session and compares input cost under a prompt-caching model:
 * the part of a request that is identical to the start of the previous request costs 5% (Gemini 4 cached price),
 * the rest costs 100%. Strategies:
 * - old: the previous sliding pruner (re-shortens earlier turns every turn and older results every step),
 * - new: append-only history with rare bulk checkpoints (HistoryPruner + checkpointIndex),
 * - naive: send everything unchanged forever.
 */
class SavingsSimulationTest {
    private val system = 24_000 // rules + tool schemas + stable context, identical for all strategies

    private fun key(m: ChatMessage): String = when (m) {
        is ChatMessage.User -> "U" + m.text
        is ChatMessage.Assistant -> "A" + m.text + m.toolCalls.joinToString { it.args.toString() }
        is ChatMessage.ToolResult -> "R" + m.content
        else -> ""
    }

    private class Meter {
        var prev: List<String> = emptyList()
        var cost = 0.0
        var chars = 0L
        fun send(systemChars: Int, msgs: List<String>) {
            var common = 0
            while (common < prev.size && common < msgs.size && prev[common] == msgs[common]) common++
            val cachedChars = systemChars + msgs.take(common).sumOf { it.length }
            val freshChars = msgs.drop(common).sumOf { it.length }
            cost += cachedChars * 0.05 + freshChars
            chars += systemChars + msgs.sumOf { it.length }
            prev = msgs
        }
    }

    /** The pruner as it was before this change: earlier turns capped at 2000 chars, only the last 6 current-turn results full. */
    private fun oldSliding(all: List<ChatMessage>, keepTurns: Int): List<ChatMessage> {
        val vis = all.filter { it is ChatMessage.User || it is ChatMessage.Assistant || it is ChatMessage.ToolResult }
        val users = vis.indices.filter { vis[it] is ChatMessage.User }
        val split = if (users.size > keepTurns) users[users.size - keepTurns] else 0
        val cur = users.lastOrNull() ?: 0
        val curResults = vis.indices.filter { it > cur && vis[it] is ChatMessage.ToolResult }.takeLast(6).toSet()
        return vis.mapIndexed { i, m ->
            when {
                i < split -> HistoryPruner.compactOld(m)
                m is ChatMessage.ToolResult && i > cur && i in curResults -> m
                m is ChatMessage.ToolResult && i > cur -> m.copy(content = HistoryPruner.truncate(m.content, 1_500, ""))
                m is ChatMessage.ToolResult -> m.copy(content = HistoryPruner.truncate(m.content, 2_000, ""))
                else -> m
            }
        }
    }

    @Test fun cacheFriendlyHistoryIsCheaper() {
        val history = ArrayList<ChatMessage>()
        val old = Meter(); val new = Meter(); val naive = Meter()
        var id = 0
        for (turn in 1..8) {
            history += ChatMessage.User("istek $turn: bir özellik ekle")
            repeat(8) { step ->
                HistoryPruner.checkpointIndex(history, keepTurns = 4, maxTailChars = 80_000)?.let { history.add(it, ChatMessage.CompactMark) }
                old.send(system, oldSliding(history, 4).map(::key))
                new.send(system, HistoryPruner.prepare(history).messages.map(::key))
                naive.send(system, history.filter { it !is ChatMessage.CompactMark }.map(::key))
                val c = ToolCall("c${id++}", if (step == 7) "github_apply_diff" else "github_read_file",
                    JSONObject().put("path", "src/F$step.kt").put("diff", if (step == 7) "d".repeat(1_500) else ""))
                history += ChatMessage.Assistant("", listOf(c))
                history += ChatMessage.ToolResult(c.id, c.name, if (step == 7) "commit ok" else "x".repeat(9_000))
            }
            history += ChatMessage.Assistant("Tamamlandı.")
        }
        fun pct(a: Double, b: Double) = "%.0f".format((1 - a / b) * 100)
        println("CACHE-SIM cost units: old=${old.cost.toLong()} new=${new.cost.toLong()} naive=${naive.cost.toLong()} | " +
            "new vs old: -${pct(new.cost, old.cost)}% | chars sent: old=${old.chars} new=${new.chars} naive=${naive.chars}")
        assertTrue("new=${new.cost} old=${old.cost}", new.cost < old.cost * 0.7)
        assertTrue("new=${new.cost} naive=${naive.cost}", new.cost < naive.cost)
    }
}
