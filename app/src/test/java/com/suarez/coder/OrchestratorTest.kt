package com.suarez.coder

import com.suarez.coder.orchestrator.OrchestratorMode
import com.suarez.coder.orchestrator.OrchestratorPlan
import com.suarez.coder.orchestrator.OrchestratorStore
import com.suarez.coder.orchestrator.OrchestratorTools
import com.suarez.coder.orchestrator.PlanJson
import com.suarez.coder.orchestrator.PlanState
import com.suarez.coder.orchestrator.Review
import com.suarez.coder.orchestrator.ReviewAction
import com.suarez.coder.orchestrator.ReviewIssue
import com.suarez.coder.orchestrator.Scheduler
import com.suarez.coder.orchestrator.SubTask
import com.suarez.coder.orchestrator.TaskFile
import com.suarez.coder.orchestrator.TaskStatus
import com.suarez.coder.llm.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrchestratorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun task(id: String, vararg files: String, deps: List<String> = emptyList(), status: TaskStatus = TaskStatus.PENDING) =
        SubTask(id, "T $id", "d", files.map { TaskFile(it, "modify", "x") }, dependsOn = deps, status = status)

    private fun plan(vararg tasks: SubTask, mode: OrchestratorMode = OrchestratorMode.FULL_MULTI_AGENT) =
        OrchestratorPlan(mode = mode, request = "r", repo = "me/app", branch = "feat/x", base = null, commitMessage = "m", tasks = tasks.toList())

    // ---- FULL: file ownership decides parallelism ----

    @Test fun disjointFilesRunInParallelUpToTheLimit() {
        val p = plan(task("a", "A.kt"), task("b", "B.kt"), task("c", "C.kt"))
        assertEquals(listOf("a", "b"), Scheduler.nextWave(p, 2).map { it.id })
        assertEquals(listOf("a", "b", "c"), Scheduler.nextWave(p, 3).map { it.id })
        assertEquals(listOf("a"), Scheduler.nextWave(p, 1).map { it.id })
    }

    @Test fun overlappingFilesAreForcedToRunOneAfterAnother() {
        val p = plan(task("a", "A.kt", "Shared.kt"), task("b", "Shared.kt", "B.kt"), task("c", "C.kt"))
        val wave1 = Scheduler.nextWave(p, 3)
        assertEquals(listOf("a", "c"), wave1.map { it.id }) // b shares Shared.kt with a
        assertTrue(Scheduler.overlaps(p.tasks[0], p.tasks[1]))
        val after = p.withTask(p.tasks[0].copy(status = TaskStatus.DONE)).withTask(p.tasks[2].copy(status = TaskStatus.DONE))
        assertEquals(listOf("b"), Scheduler.nextWave(after, 3).map { it.id })
    }

    @Test fun dependenciesWaitAndSkippedCountsAsFinished() {
        val p = plan(task("a", "A.kt"), task("b", "B.kt", deps = listOf("a")))
        assertEquals(listOf("a"), Scheduler.nextWave(p, 3).map { it.id })
        val skipped = p.withTask(p.tasks[0].copy(status = TaskStatus.SKIPPED))
        assertEquals(listOf("b"), Scheduler.nextWave(skipped, 3).map { it.id })
        val failed = p.withTask(p.tasks[0].copy(status = TaskStatus.FAILED))
        assertTrue(Scheduler.nextWave(failed, 3).isEmpty())
    }

    @Test fun sequentialOrderPutsDependenciesFirst() {
        val order = Scheduler.sequentialOrder(listOf(task("b", "B.kt", deps = listOf("a")), task("a", "A.kt"), task("c", "C.kt")))
        assertEquals(listOf("a", "b", "c"), order.map { it.id })
    }

    // ---- FULL: review rounds are capped ----

    @Test fun reviewRoundsStopAtTheCap() {
        val bad = Review(false, listOf(ReviewIssue("a", "A.kt", "null check eksik")), "1 sorun")
        assertEquals(ReviewAction.FIX, OrchestratorTools.afterReview(bad, 0, 2))
        assertEquals(ReviewAction.FIX, OrchestratorTools.afterReview(bad, 1, 2))
        assertEquals(ReviewAction.ASK_USER, OrchestratorTools.afterReview(bad, 2, 2))
        assertEquals(ReviewAction.ASK_USER, OrchestratorTools.afterReview(bad, 0, 0))
        assertEquals(ReviewAction.COMMIT, OrchestratorTools.afterReview(Review(true, emptyList(), "ok"), 5, 2))
        assertEquals(ReviewAction.ASK_USER, OrchestratorTools.afterReview(Review(false, emptyList(), "?", readable = false), 0, 2))
    }

    @Test fun reviewParsingAndTicketsOnePerFile() {
        val call = ToolCall("1", "submit_review", JSONObject("""{"approved":true,"summary":"s","issues":[
            {"task_id":"a","path":"A.kt","problem":"p1"},{"task_id":"a","path":"A.kt","problem":"p2"},{"path":"B.kt","problem":"p3"},{"path":"","problem":"x"}]}"""))
        val r = OrchestratorTools.parseReview(call, "")
        assertFalse(r.approved) // issues override approved=true
        assertEquals(3, r.issues.size)
        val tickets = OrchestratorTools.ticketsFor(r, 1, plan(task("a", "A.kt")))
        assertEquals(listOf("r1-1", "r1-2"), tickets.map { it.id })
        assertEquals(listOf("A.kt"), tickets[0].ownedFiles)
        assertTrue(tickets[0].description.contains("p1") && tickets[0].description.contains("p2"))
        assertTrue(tickets.all { it.fix })
        assertFalse(OrchestratorTools.parseReview(null, "metin").readable)
    }

    // ---- Planner output ----

    @Test fun parsesPlanLenientlyAndDropsUnknownDependencies() {
        val a = JSONObject("""{"repo":"https://github.com/me/app","branch":"feat/x","commit_message":"Skor",
            "tasks":[{"id":"t1","title":"Model","files":[{"path":"./src/Score.kt","action":"create","instructions":"..."}],
                      "acceptance_criteria":"- derlenir\n- test geçer","requires_ci":true},
                     {"title":"UI","files":["src/Ui.kt"],"depends_on":["t1","yok"]}]}""")
        val (p, err) = PlanJson.parse(a, "", OrchestratorMode.LIGHT_SEQUENTIAL, "istek")
        assertNull(err)
        p!!
        assertEquals("me/app", p.repo)
        assertEquals(listOf("t1", "t2"), p.tasks.map { it.id })
        assertEquals(listOf("src/Score.kt"), p.tasks[0].ownedFiles)
        assertEquals(listOf("derlenir", "test geçer"), p.tasks[0].acceptance)
        assertTrue(p.tasks[0].requiresCi)
        assertEquals(listOf("t1"), p.tasks[1].dependsOn)
        assertEquals(PlanState.AWAITING_APPROVAL, p.state)
    }

    @Test fun rejectsCyclesAndMissingFiles() {
        val cyc = JSONObject("""{"repo":"me/a","branch":"b","tasks":[
            {"id":"a","files":["A.kt"],"depends_on":["b"]},{"id":"b","files":["B.kt"],"depends_on":["a"]}]}""")
        assertTrue(PlanJson.parse(cyc, "", OrchestratorMode.FULL_MULTI_AGENT, "")!!.second!!.contains("döngü"))
        val noFiles = JSONObject("""{"repo":"me/a","branch":"b","tasks":[{"id":"a","files":[]}]}""")
        assertNotNull(PlanJson.parse(noFiles, "", OrchestratorMode.FULL_MULTI_AGENT, "").second)
    }

    // ---- Persistence and recovery ----

    @Test fun planRoundTripKeepsStatusCostsAndStagedFiles() {
        val p = plan(task("a", "A.kt", status = TaskStatus.DONE).copy(handoff = "h", costUsd = 0.01), task("b", "B.kt"))
            .addCost("İşçi #1", 0.01).copy(state = PlanState.PAUSED, staged = mapOf("A.kt" to "yeni", "Old.kt" to null), reviewRound = 1)
        val back = PlanJson.fromJson(PlanJson.toJson(p))!!
        assertEquals(p.tasks, back.tasks)
        assertEquals(p.roleCosts, back.roleCosts)
        assertEquals(p.staged, back.staged)
        assertEquals(1, back.reviewRound)
        assertEquals(OrchestratorMode.FULL_MULTI_AGENT, back.mode)
    }

    @Test fun killedRunRecoversAsPausedAtTheSameSubtask() {
        val store = OrchestratorStore(tmp.newFolder("orch"))
        val p = plan(task("a", "A.kt", status = TaskStatus.DONE), task("b", "B.kt", status = TaskStatus.RUNNING), task("c", "C.kt"),
            mode = OrchestratorMode.LIGHT_SEQUENTIAL).copy(state = PlanState.RUNNING)
        store.save("s1", p)
        val r = store.recover("s1")!!
        assertEquals(PlanState.PAUSED, r.state)
        assertEquals(listOf(TaskStatus.DONE, TaskStatus.PENDING, TaskStatus.PENDING), r.tasks.map { it.status })
        assertNotNull(r.note)
        assertEquals(PlanState.PAUSED, store.load("s1")!!.state) // the fix is saved
        assertNull(store.load("other"))
        store.delete("s1")
        assertNull(store.load("s1"))
    }

    @Test fun diffForReviewerShowsOnlyChangedRegions() {
        val before = (1..200).joinToString("\n") { "line $it" }
        val after = before.replace("line 100", "line 100 changed")
        val d = OrchestratorTools.renderDiff(mapOf("A.kt" to before, "N.kt" to null), mapOf("A.kt" to after, "N.kt" to "new"), setOf("Gone.kt"))
        assertTrue(d.contains("- line 100\n+ line 100 changed"))
        assertFalse(d.contains("line 50"))
        assertTrue(d.contains("=== N.kt (yeni)"))
        assertTrue(d.contains("Gone.kt (silindi)"))
        assertEquals("", OrchestratorTools.renderDiff(mapOf("A.kt" to "x"), mapOf("A.kt" to "x"), emptySet()))
    }
}
