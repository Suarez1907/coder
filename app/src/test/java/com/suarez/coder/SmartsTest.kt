package com.suarez.coder

import com.suarez.coder.agent.Checkpoints
import com.suarez.coder.agent.CustomMode
import com.suarez.coder.agent.DepVersions
import com.suarez.coder.agent.Mentions
import com.suarez.coder.agent.SelfReview
import com.suarez.coder.agent.SimilarTasks
import com.suarez.coder.agent.Todos
import com.suarez.coder.context.SemanticIndex
import com.suarez.coder.evolution.SessionTrace
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.DocsLookup
import com.suarez.coder.tools.GitHub
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartsTest {
    @Test fun todosFromLatestCallOfThisRequest() {
        val args = JSONObject("""{"items":[{"text":"Oku","status":"done"},{"text":"Değiştir","status":"in_progress"},{"text":"CI","status":"pending"}]}""")
        val old = JSONObject("""{"items":[{"text":"eski","status":"pending"}]}""")
        val h = listOf(
            ChatMessage.User("ilk"), ChatMessage.Assistant("", listOf(ToolCall("1", "update_todos", old))),
            ChatMessage.User("ikinci"), ChatMessage.Assistant("", listOf(ToolCall("2", "update_todos", args))),
        )
        val items = Todos.latest(h)
        assertEquals(3, items.size)
        assertEquals(Todos.Status.IN_PROGRESS, items[1].status)
        assertTrue(Todos.ack(items).endsWith("Tamam."))
        assertTrue(Todos.latest(h + ChatMessage.User("üçüncü")).isEmpty())
        assertTrue(Todos.ack(Todos.parse(JSONObject("""{"items":[{"text":"a","status":"pending"}]}"""))).contains("in_progress değil"))
    }

    @Test fun mentions() {
        val m = Mentions.parse("@app/src/Main.kt'e bak, @ci hatasını düzelt, @diff ve @https://x.dev/doc ile karşılaştır; mail a@b.com @ahmet")
        assertEquals(listOf(Mentions.Mention.File("app/src/Main.kt"), Mentions.Mention.Ci, Mentions.Mention.Diff, Mentions.Mention.Url("https://x.dev/doc")), m)
        assertEquals(listOf(Mentions.Mention.Ci), Mentions.parse("@ci'daki hata"))
        assertTrue(m.none { it is Mentions.Mention.File && (it.path.contains("b.com") || it.path == "ahmet") })
        assertTrue(Mentions.parse("@../secret.txt").isEmpty())
        val s = Mentions.suggestions("bak @mai", listOf("app/src/Main.kt", "docs/domain.md", "x/y.kt"))
        assertEquals("@app/src/Main.kt", s.first())
        assertEquals(listOf("@ci"), Mentions.suggestions("@c", emptyList()))
    }

    private fun trace(req: String, ok: Boolean = true, repo: String = "o/r") = SessionTrace("t" + req.hashCode(), "s", repo, 0, req, emptyList(), 3, null, 0.01,
        if (ok) 0 else 2, emptyList(), 0, emptyList(), emptyList(), null, false, false)

    @Test fun similarTasks() {
        val ts = listOf(trace("skor tablosuna en yüksek skoru kaydet"), trace("menüye ayarlar butonu ekle"), trace("skor tablosu kaydetme hatası", ok = false))
        val got = SimilarTasks.pick(ts, "en yüksek skoru kalıcı olarak kaydet", "o/r")
        assertEquals(listOf("skor tablosuna en yüksek skoru kaydet"), got.map { it.request })
        assertTrue(SimilarTasks.hint(got)!!.contains("skor tablosuna"))
        assertNull(SimilarTasks.hint(emptyList()))
    }

    @Test fun checkpointMarker() {
        val p = Checkpoints.Point("o/r", "feature-x", "abc123")
        assertEquals(p, Checkpoints.find("Kontrol\n" + Checkpoints.marker(p)))
        assertNull(Checkpoints.find(Checkpoints.marker(p.copy(sha = null)))!!.sha)
        assertNull(Checkpoints.find("başka"))
    }

    @Test fun customModesRoundTrip() {
        val list = CustomMode.EXAMPLES + CustomMode("Godot", "Godot 4 uzmanı", CustomMode.Access.NO_OPS, "gemini/gemini-2.5-flash")
        assertEquals(list, CustomMode.list(CustomMode.toJson(list)))
        assertTrue(CustomMode.list("bozuk").isEmpty())
    }

    @Test fun dependencyVersions() {
        val deps = DepVersions.extract(mapOf(
            "gradle/libs.versions.toml" to "[versions]\nagp = \"8.7.3\"\nkotlin = \"2.0.21\"\n[libraries]\nx = { module = \"a:b\", version = \"9\" }",
            "app/build.gradle.kts" to "implementation(platform(\"androidx.compose:compose-bom:2024.10.01\"))\nimplementation(\"com.squareup.okhttp3:okhttp:4.12.0\")",
            "package.json" to """{"dependencies":{"remotion":"4.0.242"}}""",
            "project.godot" to "config/features=PackedStringArray(\"4.3\", \"Forward Plus\")",
        ))
        assertTrue(deps.containsAll(listOf("agp 8.7.3", "kotlin 2.0.21", "compose-bom (BOM) 2024.10.01", "com.squareup.okhttp3:okhttp 4.12.0", "remotion 4.0.242", "Godot 4.3")))
        assertFalse(deps.any { it.startsWith("x ") })
    }

    @Test fun selfReviewParseAndView() {
        assertTrue(SelfReview.parse("```json\n{\"ok\":true}\n```")!!.ok)
        val v = SelfReview.parse("""{"ok":false,"problems":["A.kt:3 - missing import"]}""")!!
        assertFalse(v.ok); assertEquals(1, v.problems.size)
        assertNull(SelfReview.parse("tamam"))
        val view = SelfReview.view(listOf(Triple("A.kt", "a\nb\nc\nd", "a\nb\nX\nd"), Triple("N.kt", null, "new")))
        assertTrue(view.contains("- c") && view.contains("+ X") && view.contains("### N.kt (new)"))
    }

    @Test fun docsLookupParsing() {
        val v1 = """{"results":[{"id":"/remotion-dev/remotion","title":"Remotion","versions":["v4.0.200","v4.0.242","v3.3.0"]}]}"""
        val libs = DocsLookup.parseLibraries(v1)
        assertEquals("/remotion-dev/remotion", libs.single().id)
        assertEquals("v4.0.242", DocsLookup.pickVersion(libs.single().versions, "^4.0.242"))
        assertEquals("v4.0.242", DocsLookup.pickVersion(libs.single().versions, "4.0.300"))
        assertEquals("v3.3.0", DocsLookup.pickVersion(libs.single().versions, "3.1"))
        assertNull(DocsLookup.pickVersion(libs.single().versions, "5.0"))
        assertEquals(1, DocsLookup.parseLibraries("""[{"libraryId":"/a/b","name":"B"}]""").size)
        assertTrue(DocsLookup.parseLibraries("<html>").isEmpty())
    }

    @Test fun semanticHelpers() {
        val text = (1..120).joinToString("\n") { "line $it" }
        val cs = SemanticIndex.chunks("a.kt", text)
        assertEquals(1, cs.first().first); assertEquals(120, cs.last().second)
        assertTrue(cs.size == 3)
        assertEquals(1.0, SemanticIndex.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-6)
        val q = floatArrayOf(1f, 0f)
        val chunks = listOf("a" to floatArrayOf(1f, 0f), "a" to floatArrayOf(0.9f, 0.1f), "a" to floatArrayOf(0.8f, 0.2f), "b" to floatArrayOf(0f, 1f))
            .map { (p, v) -> SemanticIndex.Chunk(p, 1, 2, "", v) }
        assertEquals(listOf("a", "a", "b"), SemanticIndex.top(chunks, q, 5).map { it.chunk.path }) // at most 2 per file
        assertTrue(SemanticIndex.indexable(GitHub.TreeEntry("src/A.kt", 100)))
        assertFalse(SemanticIndex.indexable(GitHub.TreeEntry("package-lock.json", 100)))
        assertFalse(SemanticIndex.indexable(GitHub.TreeEntry("img.png", 100)))
    }
}
