package com.suarez.coder

import com.suarez.coder.cache.MerkleAstCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MerkleAstCacheTest {
    @Test
    fun matchesIsomorphicFunctionsWithDifferentIdentifiersAndAdaptsAtZeroCost() {
        val cache = MerkleAstCache()
        val aBefore = "fun calc(alpha: Int): Int { return alpha + 1 }"
        val aAfter = "fun calc(alpha: Int): Int { return alpha.coerceAtLeast(0) + 1 }"
        val bBefore = "fun compute(beta: Int): Int { return beta + 1 }"

        assertEquals(cache.normalize(aBefore).astHash, cache.normalize(bBefore).astHash)

        // Needs verifiedCount >= 2 before zero-cost adaptation triggers
        cache.recordVerified(aBefore, aAfter)
        assertFalse(cache.tryAdapt("B.kt", bBefore).applied)

        cache.recordVerified(aBefore, aAfter)
        val adapted = cache.tryAdapt("B.kt", bBefore)
        assertTrue(adapted.applied)
        assertEquals(0.0, adapted.costUsd, 1e-9)
        assertTrue(adapted.adaptedCode!!.contains("compute"))
        assertTrue(adapted.adaptedCode!!.contains("beta.coerceAtLeast(0)"))
    }
}
