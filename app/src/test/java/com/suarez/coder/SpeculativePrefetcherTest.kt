package com.suarez.coder

import com.suarez.coder.prefetch.SpeculativePrefetcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeculativePrefetcherTest {
    @Test
    fun debouncesDraftSkipsWarmOnMeteredAndExpiresAfterTtl() = runBlocking {
        var clock = 1_000L
        val prefetcher = SpeculativePrefetcher(debounceMs = 50L, minChars = 18, ttlMs = 90_000L, nowProvider = { clock })
        var resolveCalls = 0
        var warmCalls = 0
        val envMetered = SpeculativePrefetcher.EnvironmentState(enabled = true, isMetered = true, isPowerSaveMode = false)

        prefetcher.onDraftChanged(this, "OpenAiCompatClient streaming bug", envMetered, { resolveCalls++; listOf("OpenAiCompatClient.kt") }, { warmCalls++; true })
        prefetcher.onDraftChanged(this, "OpenAiCompatClient streaming bug fix", envMetered, { resolveCalls++; listOf("OpenAiCompatClient.kt") }, { warmCalls++; true })
        delay(120L)

        assertEquals(1, resolveCalls)
        assertEquals(0, warmCalls)
        val snap = prefetcher.consumeIfFresh()
        assertNotNull(snap)
        assertFalse(snap!!.cloudLlmCalled)
        assertFalse(snap.warmedSandbox)

        clock += 95_000L
        assertNull(prefetcher.consumeIfFresh())
        assertTrue(snap.statusLine().contains("0 ms"))
    }
}
