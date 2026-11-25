package com.suarez.coder.diff

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SearchReplaceTest {

    @Test
    fun testParseMarkdownHeadingPath() {
        val input = "### path/to/file.kt\n<<<<<<< SEARCH\nold line\n=======\nnew line\n>>>>>>> REPLACE"
        val result = SearchReplace.parse(input)
        assertTrue(result.errors.isEmpty(), "Errors should be empty but got: ${result.errors}")
        assertEquals(1, result.blocks.size)
        assertEquals("path/to/file.kt", result.blocks[0].path)
    }

    @Test
    fun testParsePathWithSpaces() {
        val input = "path/with spaces/file.kt\n<<<<<<< SEARCH\nold line\n=======\nnew line\n>>>>>>> REPLACE"
        val result = SearchReplace.parse(input)
        assertTrue(result.errors.isEmpty(), "Errors should be empty but got: ${result.errors}")
        assertEquals(1, result.blocks.size)
        assertEquals("path/with spaces/file.kt", result.blocks[0].path)
    }

    @Test
    fun testParsePathAfterEmptyLines() {
        val input = "path/to/file.kt\n\n<<<<<<< SEARCH\nold line\n=======\nnew line\n>>>>>>> REPLACE"
        val result = SearchReplace.parse(input)
        assertTrue(result.errors.isEmpty(), "Errors should be empty but got: ${result.errors}")
        assertEquals(1, result.blocks.size)
        assertEquals("path/to/file.kt", result.blocks[0].path)
    }
}
