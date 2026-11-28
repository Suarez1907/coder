package com.suarez.coder

import com.suarez.coder.formal.CegisVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CegisVerifierTest {
    @Test
    fun findsCounterexampleOnBuggyPricingAndVerifiesFixedFunction() {
        val buggy = CegisVerifier.verifyNumericContract(
            contractName = "PricingNonNegative",
            invariantDescription = "Maliyet NaN veya negatif olamaz",
            fn = { tokens -> tokens * 0.001 },
            predicate = { _, out -> !out.isNaN() && out >= 0.0 },
        )
        assertFalse(buggy.verified)
        assertNotNull(buggy.counterexample)

        val fixed = CegisVerifier.verifyNumericContract(
            contractName = "PricingNonNegative",
            invariantDescription = "Maliyet NaN veya negatif olamaz",
            fn = { tokens -> if (tokens.isNaN() || tokens <= 0.0) 0.0 else tokens * 0.001 },
            predicate = { _, out -> !out.isNaN() && out >= 0.0 },
        )
        assertTrue(fixed.verified)
    }
}
