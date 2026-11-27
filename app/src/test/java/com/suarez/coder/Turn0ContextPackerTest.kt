package com.suarez.coder

import com.suarez.coder.context.RepoDependencyGraph
import com.suarez.coder.context.Turn0ContextPacker
import com.suarez.coder.evolution.SessionTraceRecorder
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Turn0ContextPackerTest {
    private fun bigFile(): String {
        val body = (1..400).joinToString("\n") { n ->
            if (n == 40) "    fun saveScore(v: Int): Boolean {" else "    val pad$n = $n"
        }
        return "package app\n\nclass Score {\n$body\n        return v > 0\n    }\n    fun other() = 1\n}\n"
    }

    private fun caller() = "package app\n\nfun play(s: Score) {\n    s.saveScore(1)\n}\n"

    private fun testFile(): String {
        val noise = (1..120).joinToString("\n") { "    // filler $it" }
        return "package app\n\nclass ScoreTest {\n    fun saveScore_writes() {\n        check(Score().saveScore(2))\n    }\n$noise\n}\n"
    }

    private fun graph(files: Map<String, String>): RepoDependencyGraph {
        val g = RepoDependencyGraph()
        files.forEach { (p, t) -> g.update(p, p, t) }
        return g
    }

    @Test fun staysUnderTheCapAndDoesNotTouchTheSystemPrefix() {
        val files = mapOf(
            "app/Score.kt" to bigFile() + "\n".repeat(20_000),
            "app/Play.kt" to caller(),
            "app/ScoreTest.kt" to testFile() + "\n".repeat(20_000),
        )
        val rules = (1..8).map { "kural $it " + "x".repeat(2_000) }
        val pack = Turn0ContextPacker.pack(
            Turn0ContextPacker.Request(
                task = "saveScore yanlış yazıyor, düzelt",
                taskKind = Turn0ContextPacker.TaskKind.WRITE,
                sources = files.map { Turn0ContextPacker.Source(it.key, it.value) },
                graph = graph(files),
                ranked = listOf("app/Score.kt"),
                rules = rules,
                charCap = Turn0ContextPacker.DEFAULT_CHAR_CAP,
            ),
        )
        assertTrue(pack.used)
        assertTrue(pack.charsPacked <= 14_000)
        assertEquals(pack.charsPacked, pack.block.length)
        assertTrue(pack.block.startsWith("[TURN-0 BAĞLAM PAKETİ]"))
        assertTrue(pack.block.contains("saveScore"))
        assertTrue(pack.block.contains("Gerekli temel semboller"))
        val system = "SABİT SİSTEM ÖNEKİ"
        val (same, user) = Turn0ContextPacker.attach(system, "saveScore yanlış yazıyor, düzelt", pack)
        assertSame(system, same)
        assertFalse(same.contains("TURN-0"))
        assertTrue(user.contains("[TURN-0 BAĞLAM PAKETİ]"))
        assertTrue(user.startsWith("saveScore yanlış"))
    }

    @Test fun prefersTheNamedSymbolAndItsTestOverLowerPriorityRules() {
        val files = mapOf(
            "app/Score.kt" to bigFile(),
            "app/Play.kt" to caller(),
            "app/ScoreTest.kt" to testFile(),
            "app/Unrelated.kt" to "package app\nfun noise() = 0\n",
        )
        val pack = Turn0ContextPacker.pack(
            Turn0ContextPacker.Request(
                task = "saveScore fonksiyonunu düzelt",
                taskKind = Turn0ContextPacker.TaskKind.WRITE,
                sources = files.map { Turn0ContextPacker.Source(it.key, it.value) },
                graph = graph(files),
                ranked = listOf("app/Score.kt", "app/Play.kt"),
                rules = listOf("önce test yaz"),
                charCap = 2_500,
            ),
        )
        assertTrue(pack.block.contains("fun saveScore"))
        assertTrue(pack.block.contains("ScoreTest") || pack.block.contains("saveScore_writes"))
        assertTrue(pack.paths.contains("app/Score.kt"))
        val symbolAt = pack.block.indexOf("saveScore")
        val ruleAt = pack.block.indexOf("önce test yaz")
        if (ruleAt >= 0) assertTrue(symbolAt in 0 until ruleAt)
        assertTrue(pack.charsPacked <= 2_500)
    }

    @Test fun questionTasksAreNotPacked() {
        val pack = Turn0ContextPacker.pack(
            Turn0ContextPacker.Request(
                "bu ne işe yarar?",
                Turn0ContextPacker.TaskKind.QUESTION,
                listOf(Turn0ContextPacker.Source("a.kt", "fun a() = 1\n")),
            ),
        )
        assertFalse(pack.used)
        assertEquals(0, pack.charsPacked)
    }

    @Test fun recorderStoresTurn0AndAFirstTurnEdit() {
        val files = mapOf("a.kt" to "fun saveScore() = 1\n")
        val pack = Turn0ContextPacker.pack(
            Turn0ContextPacker.Request(
                "saveScore düzelt",
                Turn0ContextPacker.TaskKind.WRITE,
                files.map { Turn0ContextPacker.Source(it.key, it.value) },
                graph(files),
            ),
        )
        val run = listOf(
            ChatMessage.ContextBlock(pack.block, "TURN-0 BAĞLAM PAKETİ"),
            ChatMessage.Assistant("", listOf(ToolCall("c1", "github_apply_diff", JSONObject()))),
            ChatMessage.ToolResult("c1", "github_apply_diff", "committed"),
            ChatMessage.UsageRecord("m", "agent", com.suarez.coder.llm.Usage(input = 10, output = 4)),
        )
        val trace = SessionTraceRecorder.record(run, "saveScore düzelt", "s", "me/app", false, { 0.0 })
        assertTrue(trace!!.turn0PackUsed)
        assertEquals(pack.block.length, trace.turn0CharsPacked)
        assertTrue(trace.firstTurnEditSuccess)
    }

    @Test fun selectsThreeToSixTargetsPlusTheMatchingTestAndAtMostFiveRules() {
        val files = LinkedHashMap<String, String>()
        for (i in 1..8) files["src/F$i.kt"] = "package app\n\nfun f$i() = $i\n"
        files["src/F1Test.kt"] = "package app\n\nclass F1Test {\n    fun checks() = f1()\n}\n"
        val huge = bigFile() + "\nOUTSIDE_SYMBOL_TAIL\n"
        files["app/Score.kt"] = huge
        files["app/Play.kt"] = caller()
        val rules = (1..8).map { "kural-$it benzersiz" }
        val pack = Turn0ContextPacker.pack(
            Turn0ContextPacker.Request(
                task = "saveScore ve f1 fonksiyonunu düzelt",
                taskKind = Turn0ContextPacker.TaskKind.WRITE,
                sources = files.map { Turn0ContextPacker.Source(it.key, it.value) },
                graph = graph(files),
                ranked = listOf("app/Score.kt", "app/Play.kt", "src/F1.kt"),
                rules = rules,
                charCap = 14_000,
            ),
        )
        val targets = pack.paths.filter { !Turn0ContextPacker.isTestPath(it) }
        assertTrue(targets.size in 3..6)
        assertTrue(pack.paths.any { Turn0ContextPacker.isTestPath(it) })
        assertTrue(pack.block.contains("fun saveScore"))
        assertTrue(pack.block.contains("s.saveScore(1)"))
        assertFalse(pack.block.contains("OUTSIDE_SYMBOL_TAIL"))
        val kept = pack.block.lines().count { it.startsWith("- kural-") }
        assertTrue(kept in 1..5)
        assertFalse(pack.block.contains("kural-6"))
        assertTrue(pack.charsPacked <= 14_000)
    }
}
