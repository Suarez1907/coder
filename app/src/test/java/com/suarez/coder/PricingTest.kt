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
        assertEquals(0.28, Pricing.priceFor("deepseek-chat", emptyMap()).input, 1e-9)
    }
}
