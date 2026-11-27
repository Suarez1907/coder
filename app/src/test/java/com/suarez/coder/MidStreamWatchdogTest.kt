package com.suarez.coder

import com.suarez.coder.llm.MidStreamWatchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MidStreamWatchdogTest {
    private fun feed(dog: MidStreamWatchdog, text: String, tool: Boolean = false): MidStreamWatchdog.Verdict {
        var last = dog.observe("", tool)
        val step = 40
        var i = 0
        while (i < text.length) {
            val end = minOf(text.length, i + step)
            last = dog.observe(text.substring(i, end), tool)
            if (last.cut) return last
            i = end
        }
        return last
    }

    @Test fun cutsARepeatedLineAndARepeatedBlock() {
        val line = "x".repeat(70)
        val dog = MidStreamWatchdog(proseGuard = true, diffToolAvailable = true)
        val verdict = feed(dog, "$line\n$line\n$line\n")
        assertTrue(verdict.cut)
        assertEquals(MidStreamWatchdog.Reason.REPEAT, verdict.reason)

        val block = "blok ".repeat(20)
        val ngram = MidStreamWatchdog(proseGuard = false, diffToolAvailable = false)
        val again = feed(ngram, block + block + block)
        assertTrue(again.cut)
        assertEquals(MidStreamWatchdog.Reason.REPEAT, again.reason)
    }

    @Test fun cutsProseOnAWriteTurn() {
        val dog = MidStreamWatchdog(proseGuard = true, diffToolAvailable = true)
        val verdict = feed(dog, (1..400).joinToString(" ") { "kelime$it" })
        assertTrue(verdict.cut)
        assertEquals(MidStreamWatchdog.Reason.PROSE, verdict.reason)
    }

    @Test fun cutsAFullFileRewriteWhenADiffToolExists() {
        val lines = (1..220).map { "val line$it = $it" }
        val file = lines.joinToString("\n")
        val dog = MidStreamWatchdog(proseGuard = false, diffToolAvailable = true, largeFiles = mapOf("Big.kt" to file))
        val dumped = "```kotlin\n" + lines.take(90).joinToString("\n") + "\n```"
        val verdict = feed(dog, dumped)
        assertTrue(verdict.cut)
        assertEquals(MidStreamWatchdog.Reason.REWRITE, verdict.reason)
    }

    @Test fun questionModeAndNormalCodeAreNotCut() {
        val question = MidStreamWatchdog(proseGuard = false, diffToolAvailable = false)
        assertFalse(feed(question, (1..300).joinToString(" ") { "soru$it" }).cut)

        val code = MidStreamWatchdog(proseGuard = true, diffToolAvailable = true)
        val normal = "```kotlin\nfun saveScore(v: Int) = v > 0\n```\nKısa not."
        assertFalse(feed(code, normal).cut)

        val unique = MidStreamWatchdog(proseGuard = true, diffToolAvailable = true)
        assertFalse(feed(unique, (1..30).joinToString("\n") { "val v$it = $it" }).cut)
    }
}
