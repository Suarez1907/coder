package com.suarez.coder

import com.suarez.coder.harness.TypedHoleSynthesizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedHoleSynthesizerTest {
    @Test
    fun preservesBaseIndentReducesOutputAndCatchesBrokenBody() {
        val src = "package demo\nclass S {\n    fun existing() = 1\n}"
        val proposal = TypedHoleSynthesizer.proposeTypedHoles(
            "S.kt",
            src,
            listOf(TypedHoleSynthesizer.HoleSpec("h1", "fun filter(x: Int): Int", maxLines = 10, anchorLine = "fun existing() = 1")),
        )
        assertTrue(proposal.skeletonWithHoles.contains("@HOLE id=h1"))

        val good = TypedHoleSynthesizer.fillTypedHole("S.kt", proposal.skeletonWithHoles, "h1", "val y = x + 1\nreturn y")
        assertTrue(good.ok)
        assertTrue(good.updatedSource.contains("        val y = x + 1"))
        assertTrue(good.outputCharsSavedEstimate > 0)

        val bad = TypedHoleSynthesizer.fillTypedHole("S.kt", proposal.skeletonWithHoles, "h1", "if (x > 0) { return 1", attempt = 2)
        assertFalse(bad.ok)
        assertTrue(bad.fallbackToSearchReplace)
    }
}
