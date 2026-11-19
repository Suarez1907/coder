package com.suarez.coder

import com.suarez.coder.context.HistoryPruner
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simulates a realistic session and compares the input size the model receives
 * before (full history every step, whole-file writes) and after (pruning + SEARCH/REPLACE diffs).
 */
class SavingsSimulationTest {
    private val fileSize = 12_000 // a typical source file, ~3k tokens

    @Test fun pruningAndDiffsCutInputSize() {
        val oldHistory = ArrayList<ChatMessage>()
        val newHistory = ArrayList<ChatMessage>()
        var oldSent = 0L
        var newSent = 0L
        val system = 6_000 // system prompt + tool schemas, same for both

        fun step(oldMsgs: List<ChatMessage>, newMsgs: List<ChatMessage>) {
            oldHistory += oldMsgs; newHistory += newMsgs
            oldSent += system + HistoryPruner.charSize(oldHistory)
            newSent += system + HistoryPruner.charSize(HistoryPruner.prepare(newHistory, 4).messages)
        }

        var id = 0
        for (turn in 1..5) {
            val u = ChatMessage.User("istek $turn: bir özellik ekle")
            oldHistory += u; newHistory += u
            // 3 file reads
            repeat(3) {
                val c = ToolCall("c${id++}", "github_read_file", JSONObject().put("path", "src/F$it.kt"))
                val call = ChatMessage.Assistant("", listOf(c))
                val res = ChatMessage.ToolResult(c.id, c.name, "x".repeat(fileSize))
                step(listOf(call, res), listOf(call, res))
            }
            // write 2 files: old way = whole file content in args, new way = small diff
            val wOld = ToolCall("c${id}", "github_write_files", JSONObject().put("files", "y".repeat(2 * fileSize)))
            val wNew = ToolCall("c${id++}", "github_apply_diff", JSONObject().put("diff", "z".repeat(1_500)))
            step(
                listOf(ChatMessage.Assistant("", listOf(wOld)), ChatMessage.ToolResult(wOld.id, wOld.name, "commit ok")),
                listOf(ChatMessage.Assistant("", listOf(wNew)), ChatMessage.ToolResult(wNew.id, wNew.name, "commit ok")),
            )
            val answer = ChatMessage.Assistant("Tamamlandı. Değişiklikler: ...")
            step(listOf(answer), listOf(answer))
        }
        // Output tokens also drop: the model writes ~1.5k-char diffs instead of 24k-char files.
        val ratio = newSent.toDouble() / oldSent
        println("SIMULATION input chars: old=$oldSent new=$newSent saving=${"%.0f".format((1 - ratio) * 100)}%")
        assertTrue("saving too small: $ratio", ratio < 0.6)
    }
}
