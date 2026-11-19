package com.suarez.coder

import com.suarez.coder.diff.SearchReplace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchReplaceTest {
    private val file = """
        fun main() {
            val a = 1
            println(a)
        }
    """.trimIndent()

    @Test fun parsesBlocksWithPathsAndFences() {
        val text = """
            Değişiklik:
            ```kotlin
            src/Main.kt
            <<<<<<< SEARCH
                val a = 1
            =======
                val a = 2
            >>>>>>> REPLACE
            ```
            <<<<<<< SEARCH
                println(a)
            =======
                println("a=${'$'}a")
            >>>>>>> REPLACE
        """.trimIndent()
        val r = SearchReplace.parse(text)
        assertEquals(emptyList<String>(), r.errors)
        assertEquals(2, r.blocks.size)
        assertEquals("src/Main.kt", r.blocks[0].path)
        assertEquals("src/Main.kt", r.blocks[1].path) // reuses the previous path
    }

    @Test fun reportsUnterminatedBlock() {
        val r = SearchReplace.parse("a.kt\n<<<<<<< SEARCH\nx\n=======\ny\n")
        assertEquals(0, r.blocks.size)
        assertEquals(1, r.errors.size)
    }

    @Test fun exactReplace() {
        val r = SearchReplace.replaceOnce(file, "    val a = 1", "    val a = 2")
        assertTrue(r is SearchReplace.Match.Ok)
        assertTrue((r as SearchReplace.Match.Ok).text.contains("val a = 2"))
    }

    @Test fun ambiguousMatchIsRejected() {
        val r = SearchReplace.replaceOnce("x\nx\n", "x", "y")
        assertTrue(r is SearchReplace.Match.Err)
    }

    @Test fun indentationInsensitiveMatchReindents() {
        val r = SearchReplace.replaceOnce(file, "val a = 1\nprintln(a)", "val a = 5\nprintln(a + 1)")
        assertTrue(r.toString(), r is SearchReplace.Match.Ok)
        val text = (r as SearchReplace.Match.Ok).text
        assertTrue(text, text.contains("    val a = 5\n    println(a + 1)"))
    }

    @Test fun trailingWhitespaceAndCrlfTolerated() {
        val crlf = "a = 1   \r\nb = 2\r\n"
        val res = SearchReplace.applyToFile(crlf, listOf(SearchReplace.Block("f", "a = 1\nb = 2", "a = 3\nb = 4", 1)))
        assertEquals(0, res.failures.size)
        assertTrue(res.content.startsWith("a = 3\nb = 4"))
    }

    @Test fun missingSearchFailsWithoutChangingFile() {
        val res = SearchReplace.applyToFile(file, listOf(SearchReplace.Block("f", "nope()", "x", 1)))
        assertEquals(1, res.failures.size)
        assertEquals(file, res.content)
    }

    @Test fun emptySearchCreatesNewFile() {
        val res = SearchReplace.applyToFile(null, listOf(SearchReplace.Block("new.kt", "", "hello", 1)))
        assertEquals(0, res.failures.size)
        assertEquals("hello\n", res.content)
    }

    @Test fun emptySearchOnExistingFileFails() {
        val res = SearchReplace.applyToFile(file, listOf(SearchReplace.Block("f", "", "x", 1)))
        assertEquals(1, res.failures.size)
    }

    @Test fun deletionWithEmptyReplace() {
        val r = SearchReplace.replaceOnce(file, "    println(a)\n", "")
        assertEquals("fun main() {\n    val a = 1\n}", (r as SearchReplace.Match.Ok).text)
    }
}
