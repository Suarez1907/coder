package com.suarez.coder

import com.suarez.coder.tools.GitHub.ReadCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadCacheTest {
    @After fun reset() = ReadCache.clear()

    @Test fun ttlAndNegativeEntries() {
        val k = ReadCache.key("Me/App", "file", "main", "a.kt")
        ReadCache.put(k, "x", 1, now = 1_000)
        assertEquals(true to "x", ReadCache.get(k, ttlMs = 500, now = 1_400))
        assertFalse(ReadCache.get(k, ttlMs = 500, now = 1_600).first) // expired
        val missing = ReadCache.key("me/app", "file", "main", "yok.kt")
        ReadCache.put(missing, null, 8)
        val (hit, v) = ReadCache.get(missing, 60_000)
        assertTrue(hit); assertNull(v) // a 404 is remembered too (the read-for-edit fallback chain)
    }

    @Test fun writesInvalidateOnlyThatRepo() {
        ReadCache.put(ReadCache.key("me/a", "file", null, "x"), "1", 1)
        ReadCache.put(ReadCache.key("me/b", "file", null, "x"), "2", 1)
        ReadCache.invalidate("ME/A")
        assertFalse(ReadCache.get(ReadCache.key("me/a", "file", null, "x"), 60_000).first)
        assertTrue(ReadCache.get(ReadCache.key("me/b", "file", null, "x"), 60_000).first)
        ReadCache.invalidate("") // github_api writes: everything
        assertFalse(ReadCache.get(ReadCache.key("me/b", "file", null, "x"), 60_000).first)
    }

    @Test fun sizeBounds() {
        ReadCache.put("me/a|big", "x", ReadCache.MAX_ITEM_CHARS + 1)
        assertFalse(ReadCache.get("me/a|big", 60_000).first) // too large to keep
        val n = (ReadCache.MAX_CHARS / 300_000).toInt() + 3
        repeat(n) { ReadCache.put("me/a|f$it", "v", 300_000) }
        assertFalse(ReadCache.get("me/a|f0", 60_000).first) // oldest evicted
        assertTrue(ReadCache.get("me/a|f${n - 1}", 60_000).first)
    }
}
