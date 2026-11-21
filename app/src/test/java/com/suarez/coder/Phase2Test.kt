package com.suarez.coder

import com.suarez.coder.diff.DiffLine
import com.suarez.coder.diff.DiffLines
import com.suarez.coder.ui.diffFilesFor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffLinesTest {
    @Test fun unchangedRemovedAndAddedLinesWithNumbers() {
        val lines = DiffLines.block("fun a() {\n    old()\n}", "fun a() {\n    new()\n    extra()\n}")
        assertEquals(
            listOf(
                DiffLine(' ', 1, 1, "fun a() {"),
                DiffLine('-', 2, null, "    old()"),
                DiffLine('+', null, 2, "    new()"),
                DiffLine('+', null, 3, "    extra()"),
                DiffLine(' ', 3, 4, "}"),
            ),
            lines,
        )
    }

    @Test fun newFileIsAllAddedAndDeletionAllRemoved() {
        assertTrue(DiffLines.block("", "a\nb").all { it.kind == '+' })
        assertTrue(DiffLines.block("a\nb", "").all { it.kind == '-' })
    }

    @Test fun hugeBlocksFallBackWithoutLcs() {
        val big = (1..1000).joinToString("\n") { "line $it" }
        val lines = DiffLines.block(big, big + "\nx")
        assertEquals(2001, lines.size) // fallback: everything removed, then everything added (no O(n*m) table)
    }

    @Test fun toolCallsBecomeDiffFiles() {
        val diff = "app/A.kt\n<<<<<<< SEARCH\nold\n=======\nnew\n>>>>>>> REPLACE\n"
        val files = diffFilesFor("github_apply_diff", JSONObject().put("diff", diff))!!
        assertEquals("app/A.kt", files.single().path)
        val written = diffFilesFor("github_write_files", JSONObject().put("files", JSONArray()
            .put(JSONObject().put("path", "B.kt").put("content", "x\ny"))
            .put(JSONObject().put("path", "C.kt").put("delete", true))))!!
        assertEquals(listOf("B.kt", "C.kt"), written.map { it.path })
        assertNull(diffFilesFor("github_read_file", JSONObject()))
        assertTrue(DiffLines.toText(files).contains("-old\n+new"))
    }
}
