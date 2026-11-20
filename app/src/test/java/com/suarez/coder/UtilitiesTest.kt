package com.suarez.coder

import com.suarez.coder.ci.LogFilter
import com.suarez.coder.diff.SearchReplace
import com.suarez.coder.tools.WebFocus
import com.suarez.coder.validate.CodeValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchoredDiffTest {
    private val file = (1..30).joinToString("\n") { "    line$it()" }

    @Test fun ellipsisReplacesWholeRegion() {
        val r = SearchReplace.replaceOnce(file, "    line5()\n    line6()\n...\n    line20()", "    replaced()")
        assertTrue(r.toString(), r is SearchReplace.Match.Ok)
        val text = (r as SearchReplace.Match.Ok).text
        assertTrue(text.contains("    line4()\n    replaced()\n    line21()"))
        assertFalse(text.contains("line12()"))
    }

    @Test fun ellipsisNeedsUniqueHead() {
        val dup = "a\nb\nx\na\nb\ny"
        assertTrue(SearchReplace.replaceOnce(dup, "a\n...\ny", "z") is SearchReplace.Match.Err)
    }

    @Test fun literalDotsInCodeStillMatchExactly() {
        val code = "val s = 1\n...\nval t = 2"
        val r = SearchReplace.replaceOnce(code, "val s = 1\n...\nval t = 2", "x")
        assertEquals("x", (r as SearchReplace.Match.Ok).text)
    }
}

class CodeValidatorTest {
    @Test fun detectsBrokenBraces() {
        val before = "class A {\n    fun f() {\n        println(\"{\")\n    }\n}\n"
        val after = "class A {\n    fun f() {\n        println(\"{\")\n    \n}\n"
        assertNull(CodeValidator.validate("A.kt", before, before))
        assertNotNull(CodeValidator.validate("A.kt", before, after))
    }

    @Test fun ignoresBracesInStringsCommentsAndRawStrings() {
        val code = "val a = \"\"\"\n  { not code\n\"\"\"\n// }}}\n/* { */\nval b = '{'\nfun x() = listOf(1)[0]\n"
        assertEquals(Triple(0, 0, 0), CodeValidator.balance(code))
        val js = "const t = `a ${'$'}{b} {`;\nfunction f() { return t; }\n"
        assertEquals(Triple(0, 0, 0), CodeValidator.balance(js, "js"))
    }

    @Test fun doesNotJudgeFilesThatWereAlreadyUnbalancedForTheHeuristic() {
        val weird = "fun f() {\n"
        assertNull(CodeValidator.validate("A.kt", weird, weird + "}\n}\n"))
    }

    @Test fun jsonAndXml() {
        assertNotNull(CodeValidator.validate("a.json", "{\"a\":1}", "{\"a\":1,}"))
        assertNotNull(CodeValidator.validate("a.json", "{\"a\":1}", "{\"a\":1} x"))
        assertNotNull(CodeValidator.validate("a.json", "{\"a\":1}", "{a:1}"))
        assertNull(CodeValidator.validate("package.json", null, "{\"name\":\"x\",\"v\":[1,2.5e3,-0,true,null,\"\\u00e7\"]}"))
        assertNull(CodeValidator.validate("a.json", "{\"a\":1}", "{\"a\":2}"))
        assertNotNull(CodeValidator.validate("res/a.xml", "<a><b/></a>", "<a><b></a>"))
        assertNull(CodeValidator.validate("res/a.xml", null, "<?xml version=\"1.0\"?><a/>"))
    }
}

class LogFilterTest {
    @Test fun keepsOnlyTheErrorPart() {
        val log = buildString {
            repeat(500) { append("2026-11-19T11:14:5${it % 10}.1Z Download https://repo/x$it.pom\n") }
            append("2026-11-19T11:14:56.1Z e: file:///app/src/Main.kt:12:5 Unresolved reference 'foo'.\n")
            repeat(300) { append("2026-11-19T11:14:57.1Z noise line $it\n") }
            append("2026-11-19T11:14:58.1Z BUILD FAILED in 24s\n")
            append("2026-11-19T11:14:58.2Z ##[error]Process completed with exit code 1.\n")
        }
        val out = LogFilter.extract(log)
        assertTrue(out, out.contains("Unresolved reference 'foo'"))
        assertTrue(out, out.contains("BUILD FAILED"))
        assertFalse(out, out.contains("2026-11-19T"))
        assertTrue("${out.length} vs ${log.length}", out.length < log.length / 20)
    }
}

class WebFocusTest {
    @Test fun picksRelevantParagraphs() {
        val page = (1..40).joinToString("\n\n") { "Paragraph $it about unrelated cooking topics and gardening tips." } +
            "\n\nTo configure LazyVerticalGrid use GridCells.Fixed(2) inside the composable." +
            (41..80).joinToString("\n\n", prefix = "\n\n") { "Paragraph $it about travel." }
        val out = WebFocus.select(page, "LazyVerticalGrid GridCells", maxChars = 600)
        assertTrue(out, out.contains("GridCells.Fixed(2)"))
        assertTrue(out.length <= 700)
    }
}
