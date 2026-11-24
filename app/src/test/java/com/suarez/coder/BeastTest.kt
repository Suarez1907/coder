package com.suarez.coder

import androidx.test.core.app.ApplicationProvider
import com.suarez.coder.agent.Agent
import com.suarez.coder.agent.ArchitectEditor
import com.suarez.coder.agent.ErrorSig
import com.suarez.coder.agent.Escalation
import com.suarez.coder.agent.FixMemory
import com.suarez.coder.agent.PlanCritic
import com.suarez.coder.agent.QueuedTask
import com.suarez.coder.agent.QuickCheck
import com.suarez.coder.agent.References
import com.suarez.coder.agent.ReviewLearner
import com.suarez.coder.agent.TaskQueueStore
import com.suarez.coder.cloudtools.CloudWorkflowTemplates
import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.queue.IssueWatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BeastTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ed = ModelRef.parse("gemini/gemini-2.5-flash")!!
    private val a = ModelRef.parse("openrouter/a/strong")!!
    private val b = ModelRef.parse("openrouter/b/stronger")!!

    @Test fun ladderClimbsUserOrder() {
        // One escalation model: last two attempts, as before.
        assertEquals(listOf("editor", "editor", "editor", "escalation", "escalation"), Escalation.ladder(5, ed, listOf(a)).map { it.role })
        assertEquals(ArchitectEditor.executorLadder(5, ed, a), ArchitectEditor.executorLadder(5, ed, listOf(a)))
        // Two models: each once, in the user's order.
        assertEquals(listOf(ed, ed, ed, a, b), Escalation.ladder(5, ed, listOf(a, b)).map { it.ref })
        // Three models in five attempts.
        val c = ModelRef.parse("deepseek/deepseek-reasoner")!!
        assertEquals(listOf(ed, ed, a, b, c), Escalation.ladder(5, ed, listOf(a, b, c)).map { it.ref })
        // More models than attempts: the first ones, editor keeps one try.
        assertEquals(listOf(ed, a, b), Escalation.ladder(3, ed, listOf(a, b, c)).map { it.ref })
        // Early escalation, editor removed from the chain, empty chain.
        assertEquals(listOf(ed, a, b, b, b), Escalation.ladder(5, ed, listOf(a, b), early = true).map { it.ref })
        assertEquals(listOf(ed, ed, ed, a, a), Escalation.ladder(5, ed, listOf(ed, a)).map { it.ref })
        assertTrue(Escalation.ladder(5, ed, emptyList()).all { it.ref == ed })
        assertEquals(a, Escalation.next(listOf(a, b), ed))
        assertEquals(b, Escalation.next(listOf(a, b), a))
        assertNull(Escalation.next(listOf(a, b), b))
        assertEquals(listOf("x/y", "z/w"), Escalation.parse(" x/y\n\nbozuk\nz/w\nx/y"))
    }

    @Test fun sameErrorIsRecognized() {
        val e1 = "e: file:///home/runner/work/app/app/src/Main.kt:12:5 Unresolved reference 'foo'."
        val e2 = "e: file:///home/runner/work/app/app/src/Main.kt:40:9 Unresolved reference 'foo'."
        assertTrue(ErrorSig.similar(e1, e2))
        assertFalse(ErrorSig.similar(e1, "error TS2345: Argument of type 'string' is not assignable to parameter of type 'number'."))
    }

    @Test fun fixMemory() {
        val m = FixMemory(tmp.newFile("fixes.json").also { it.delete() })
        m.record(FixMemory.Fix("me/app", "e: Main.kt:12 Unresolved reference 'foo'", "Main.kt'e import eklendi [Main.kt]", 1))
        m.record(FixMemory.Fix("me/other", "e: X.kt:3 Unresolved reference 'foo'", "başka repo çözümü", 2))
        val found = m.lookup("me/app", "e: Main.kt:99 Unresolved reference 'foo'")
        assertEquals("me/app", found.first().repo) // same repo first
        assertTrue(m.hint(found)!!.contains("import eklendi"))
        m.record(FixMemory.Fix("me/app", "e: Main.kt:7 Unresolved reference 'foo'", "yeni çözüm", 3))
        assertEquals(2, m.all().size) // replaced, not duplicated
        assertTrue(m.lookup("me/app", "tamamen farklı bir şey").isEmpty())
    }

    @Test fun planCriticParse() {
        val v = PlanCritic.parse("""{"ok":false,"issues":["MainActivity calls save(id)"],"add_files":[{"path":"app/src/MainActivity.kt","action":"modify","instructions":"save(id, ts)"},{"path":"../x","action":"modify"}]}""")!!
        assertFalse(v.ok); assertEquals(1, v.issues.size); assertEquals(listOf("app/src/MainActivity.kt"), v.addFiles.map { it.path })
        assertTrue(PlanCritic.parse("```json\n{\"ok\":true}\n```")!!.ok)
        assertNull(PlanCritic.parse("iyi görünüyor"))
    }

    @Test fun references() {
        val src = "class ScoreRepo {\n  fun saveScore(s: Int) {}\n}\nval r = repo.saveScore(3)\nfun saveScoreLater() {}"
        val hits = References.lines(src, "saveScore")
        assertEquals(listOf(2, 4), hits.map { it.first }) // whole word only
        assertTrue(References.isDefinition(hits[0].second, "saveScore"))
        assertFalse(References.isDefinition(hits[1].second, "saveScore"))
        assertTrue(References.isDefinition("def save_score(x):", "save_score"))
        assertTrue(References.isDefinition("export function saveScore() {", "saveScore"))
    }

    @Test fun queueStoreAndPrompt() {
        val store = TaskQueueStore(tmp.newFile("queue.json").also { it.delete() })
        val t = QueuedTask("t123456789", "me/app", "Skoru kaydet")
        store.add(t); store.add(t.copy(id = "t2", text = "ikinci"))
        assertEquals("t123456789", store.next()!!.id)
        store.update(t.id) { it.copy(state = QueuedTask.State.RUNNING, at = 0) }
        assertEquals("t2", store.next()!!.id)
        store.recoverStale(now = 4 * 3_600_000L)
        assertEquals(QueuedTask.State.FAILED, store.all().first { it.id == t.id }.state)
        val p = TaskQueueStore.prompt(t)
        assertTrue(p.contains("coder/q-456789") && p.contains("Merge etme"))
        assertEquals(t, QueuedTask.fromJson(t.toJson()))
    }

    @Test fun issuesBecomeTasks() {
        val issue = JSONObject("""{"number":7,"title":"Skor kayboluyor","body":"Uygulama kapanınca skor sıfırlanıyor","author_association":"OWNER"}""")
        val t = IssueWatcher.task("me/app", issue)!!
        assertEquals("issue:me/app#7", t.source)
        assertTrue(t.text.contains("Closes #7"))
        assertNull(IssueWatcher.task("me/app", JSONObject("""{"number":8,"title":"PR","pull_request":{}}""")))
    }

    @Test fun reviewRulesAndQuickCheckRelevance() {
        assertEquals(listOf("- Hep viewModelScope kullan", "- Sabit metinleri strings.xml'e koy"),
            ReviewLearner.rules("İşte kurallar:\n- Hep viewModelScope kullan\n- Sabit metinleri strings.xml'e koy\n-"))
        val log = "e: file:///home/runner/work/app/app/src/main/java/a/Main.kt:3:1 Unresolved reference\n" +
            "e: file:///home/runner/work/app/app/src/main/java/a/Old.kt:9:1 Deprecated"
        assertEquals(1, QuickCheck.relevant(log, listOf("app/src/main/java/a/Main.kt")).size)
        assertTrue(QuickCheck.relevant(log, listOf("app/src/main/java/a/Other.kt")).isEmpty())
    }

    @Test fun unattendedRunsNeverMerge() {
        val agent = Agent(Settings(ApplicationProvider.getApplicationContext()))
        assertNotNull(agent.unattendedDenied("github_merge_pull_request", null))
        assertNotNull(agent.unattendedDenied("github_api", "DELETE"))
        assertNull(agent.unattendedDenied("github_api", "GET"))
        assertNull(agent.unattendedDenied("github_create_pull_request", null))
    }

    @Test fun newWorkflowsDumpForShellCheck() {
        val dir = java.io.File("build/wf").apply { mkdirs() }
        listOf(CloudWorkflowTemplates.QUICK, CloudWorkflowTemplates.ANDROID_SHOTS).forEach { java.io.File(dir, it.file).writeText(it.yaml) }
        assertTrue(CloudWorkflowTemplates.QUICK.scriptOptional && CloudWorkflowTemplates.ANDROID_SHOTS.scriptOptional)
        assertEquals(CloudWorkflowTemplates.ANDROID_SHOTS, CloudWorkflowTemplates.forTool("emulator"))
    }
}
