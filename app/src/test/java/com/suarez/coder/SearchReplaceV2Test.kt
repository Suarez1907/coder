package com.suarez.coder

import com.suarez.coder.diff.SearchReplace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchReplaceV2Test {

    @Test
    fun testTier1ExactMatch() {
        val fileContent = "fun hello() {\n    println(\"hello world\")\n}\n"
        val search = "    println(\"hello world\")"
        val replace = "    println(\"hi world\")"
        val match = SearchReplace.replaceOnce(fileContent, search, replace)
        assertTrue(match is SearchReplace.Match.Ok)
        val ok = match as SearchReplace.Match.Ok
        assertEquals(SearchReplace.MatchTier.EXACT, ok.tierUsed)
        assertTrue(ok.text.contains("hi world"))
    }

    @Test
    fun testTier1MultipleExactMatchesReportsLineNumbers() {
        val fileContent = "foo\nbar\nfoo\nbaz\nfoo"
        val search = "foo"
        val replace = "qux"
        val match = SearchReplace.replaceOnce(fileContent, search, replace)
        assertTrue(match is SearchReplace.Match.Err)
        val err = match as SearchReplace.Match.Err
        assertTrue(err.reason.contains("3 kez geçiyor"))
        assertTrue(err.reason.contains("1, 3, 5"))
    }

    @Test
    fun testTier2WhitespaceNormalizedMaintainsIndentation() {
        val fileContent = "class Foo {\n        val a = 1\n        val b = 2\n}\n"
        val search = "    val a = 1  \n    val b = 2"
        val replace = "    val c = 3"
        val match = SearchReplace.replaceOnce(fileContent, search, replace)
        assertTrue(match is SearchReplace.Match.Ok)
        val ok = match as SearchReplace.Match.Ok
        assertEquals(SearchReplace.MatchTier.WHITESPACE_NORMALIZED, ok.tierUsed)
        assertTrue(ok.text.contains("        val c = 3"))
    }

    @Test
    fun testTier3LineAnchorWithinWindow() {
        val lines = (1..60).map { "line $it: println($it)" }.toMutableList()
        lines[34] = "    targetHeadOne()"
        lines[35] = "    targetHeadTwo()"
        lines[36] = "    targetBodyOld()"
        lines[37] = "    targetTailOne()"
        lines[38] = "    targetTailTwo()"
        val content = lines.joinToString("\n")
        val search = "targetHeadOne()\ntargetHeadTwo()\ntargetBodyModified()\ntargetTailOne()\ntargetTailTwo()"
        val replace = "targetHeadOne()\ntargetHeadTwo()\ntargetBodyNEW()\ntargetTailOne()\ntargetTailTwo()"
        val match = SearchReplace.replaceOnce(content, search, replace, startLine = 35)
        assertTrue(match is SearchReplace.Match.Ok)
        val ok = match as SearchReplace.Match.Ok
        assertEquals(SearchReplace.MatchTier.LINE_ANCHOR, ok.tierUsed)
        assertTrue(ok.text.contains("targetBodyNEW()"))
    }

    @Test
    fun testTier3LineAnchorFailsOutsideWindowAndReturnsClosestWindow() {
        val lines = (1..100).map { "val x$it = $it" }.toMutableList()
        lines[80] = "val target = 80"
        val content = lines.joinToString("\n")
        val search = "val target = 80\nval nonExistent = 999"
        val replace = "val target = 99"
        val match = SearchReplace.replaceOnce(content, search, replace, startLine = 10)
        assertTrue(match is SearchReplace.Match.Err)
        val err = match as SearchReplace.Match.Err
        assertTrue(err.reason.contains("SEARCH metni dosyada bulunamadı"))
        assertTrue(err.reason.contains("En yakın dosya penceresi"))
    }

    @Test
    fun testParseLineHintFromSearchHeader() {
        val openMarker = "<<<<<<< SEARCH"
        val closeMarker = ">>>>>>> REPLACE"
        val patch = "app/Foo.kt:42\n$openMarker\nval x = 1\n=======\nval x = 2\n$closeMarker"
        val parsed = SearchReplace.parse(patch)
        assertEquals(1, parsed.blocks.size)
        assertEquals("app/Foo.kt", parsed.blocks[0].path)
        assertEquals(42, parsed.blocks[0].startLine)
    }
}
