package com.suarez.coder

import com.suarez.coder.cost.Price
import com.suarez.coder.cost.Pricing
import com.suarez.coder.llm.Usage
import org.junit.Assert.assertEquals
import org.junit.Test

class PricingTest {
    @Test fun costSplitsCachedAndThinking() {
        val p = Price(1.0, 10.0, 0.1)
        // 1M input of which 500k cached, 100k output + 100k thinking
        val c = Pricing.cost(Usage(1_000_000, 100_000, 500_000, 100_000), p)
        assertEquals(0.5 + 0.05 + 2.0, c, 1e-9)
    }

    @Test fun overridesMatchLongestKey() {
        val o = Pricing.parseOverrides("gemini = 9, 9\ngemini-2.5-flash = 0.3, 2.5, 0.03, 1 # yorum")
        assertEquals(0.3, Pricing.priceFor("gemini-2.5-flash", o).input, 1e-9)
        assertEquals(9.0, Pricing.priceFor("gemini-2.5-pro", o).input, 1e-9)
        assertEquals(0.14, Pricing.priceFor("deepseek-chat", emptyMap()).input, 1e-9) // DeepSeek V4-Flash standard rate
    }
}

class PriceTableTest {
    @Test fun publishedPrices() {
        assertEquals(3.00, Pricing.defaultFor("gemini-4-neon").input, 1e-9)
        assertEquals(7.50, Pricing.defaultFor("gemini-4-hydrogen").output, 1e-9)
        assertEquals(0.10, Pricing.defaultFor("gemini-2.5-flash-lite").input, 1e-9)
        assertEquals(0.30, Pricing.defaultFor("gemini-2.5-flash").input, 1e-9) // not confused with flash-lite
        assertEquals(0.25, Pricing.defaultFor("gemini-3.1-flash-lite").input, 1e-9)
        assertEquals(0.0, Pricing.defaultFor("gemini-decision-1").output, 1e-9)
        assertEquals(0.30, Pricing.defaultFor("gemini-2.5-flash-preview-09-2025").input, 1e-9)
    }

    @Test fun explicitCacheSupport() {
        org.junit.Assert.assertFalse(Pricing.supportsExplicitCache("gemini-4-neon"))
        org.junit.Assert.assertTrue(Pricing.supportsExplicitCache("gemini-3.8-flash"))
        org.junit.Assert.assertFalse(Pricing.supportsExplicitCache("deepseek-chat"))
    }

    @Test fun minimalThinkingConfig() {
        val c = com.suarez.coder.llm.GeminiClient("k", "gemini-3.1-flash-lite").thinkingConfig(null, minimal = true)!!
        assertEquals("minimal", c.getString("thinkingLevel"))
        val f = com.suarez.coder.llm.GeminiClient("k", "gemini-2.5-flash-lite").thinkingConfig(null, minimal = true)!!
        assertEquals(0, f.getInt("thinkingBudget"))
        val n = com.suarez.coder.llm.GeminiClient("k", "gemini-4-neon").thinkingConfig(null, minimal = true)!!
        assertEquals("low", n.getString("thinkingLevel")) // Gemini 4 has no "minimal"
    }
}
