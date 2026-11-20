package com.suarez.coder

import com.suarez.coder.agent.ArchitectEditor
import com.suarez.coder.tools.Args
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LenientArgsTest {
    @Test fun pathsInEveryShape() {
        assertEquals(listOf("a.kt", "b/c.kt"), Args.stringList(JSONObject().put("paths", JSONArray(listOf("a.kt", "./b/c.kt"))), "paths"))
        assertEquals(listOf("a.kt", "b.kt"), Args.stringList(JSONObject().put("paths", "a.kt, b.kt"), "paths"))
        assertEquals(listOf("a.kt", "b.kt"), Args.stringList(JSONObject().put("paths", "[\"a.kt\",\"b.kt\"]"), "paths"))
        assertEquals(listOf("x/y.kt"), Args.stringList(JSONObject().put("paths", JSONArray().put(JSONObject().put("path", "x/y.kt"))), "paths"))
    }

    private fun plan(files: Any) = JSONObject().put("repo", "o/r").put("branch", "feat/ui").put("commit_message", "m").put("files", files)

    @Test fun planWithObjects() {
        val p = ArchitectEditor.parsePlan(plan(JSONArray().put(JSONObject().put("path", "app/A.kt").put("action", "modify").put("instructions", "1. x"))), "")
        assertNull(p.error)
        assertEquals("app/A.kt", p.files.single().path)
    }

    @Test fun planWithStringsAndJsonText() {
        val p = ArchitectEditor.parsePlan(plan(JSONArray(listOf("gradle/libs.versions.toml: compose sürümünü yükselt", "{\"path\":\"app/B.kt\",\"instructions\":\"y\"}"))), "")
        assertNull(p.error)
        assertEquals(listOf("gradle/libs.versions.toml", "app/B.kt"), p.files.map { it.path })
        assertEquals("compose sürümünü yükselt", p.files[0].instructions)
        val q = ArchitectEditor.parsePlan(plan("[{\"path\":\"app/C.kt\",\"action\":\"create\",\"instructions\":\"z\"}]"), "")
        assertEquals("create", q.files.single().action)
    }

    @Test fun unusablePlansReturnAnErrorForTheArchitect() {
        // The case from the phone: plain words instead of file objects must not become new files named "versions".
        val p = ArchitectEditor.parsePlan(plan(JSONArray(listOf("versions"))), "")
        assertNotNull(p.error)
        assertTrue(p.error!!.contains("NESNE"))
        assertNotNull(ArchitectEditor.parsePlan(plan(JSONArray().put(JSONObject().put("path", "a.kt"))), "").error) // no instructions
        assertNotNull(ArchitectEditor.parsePlan(JSONObject().put("repo", "o/r").put("files", JSONArray()), "").error) // no branch
    }
}
