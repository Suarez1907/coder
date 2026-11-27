package com.suarez.coder

import com.suarez.coder.orchestrator.ContractDagOrchestrator
import com.suarez.coder.orchestrator.DagNode
import com.suarez.coder.orchestrator.FileEdit
import com.suarez.coder.orchestrator.NodeResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractDagOrchestratorTest {
    private val architect = "gemini/architect-lock"

    private fun node(id: String, file: String, depends: List<String> = emptyList()) =
        DagNode(id, depends, listOf(file), listOf("fun $id(): Unit", "test $id geçer"), id)

    @Test fun disjointNodesRunTogetherAndDependentsWait() = runBlocking {
        val orch = ContractDagOrchestrator(architect, 2) { architect }
        assertFalse(orch.retarget("other-model"))
        assertEquals(architect, orch.architectModelId)
        val nodes = listOf(node("a", "A.kt"), node("b", "B.kt"), node("c", "C.kt", listOf("a")))
        val run = orch.execute(nodes) { n, contract ->
            assertEquals(architect, contract.architectModelId)
            assertFalse(contract.implementers.values.any { it.equals(architect, true) })
            if (n.id == "a" || n.id == "b") delay(60)
            NodeResult.Ok(listOf(FileEdit(n.ownedFiles.single(), "", "ok")))
        }
        assertTrue(run.waves.any { it.containsAll(listOf("a", "b")) })
        assertTrue(run.waves.indexOfFirst { "c" in it } > run.waves.indexOfFirst { "a" in it })
        assertTrue(run.maxInFlight >= 2)
        assertTrue(run.committed)
        assertEquals(architect, run.architectModelId)
        assertEquals("parallel", run.mode)
    }

    @Test fun overlapFallsBackToSequentialAndArchitectStaysLocked() = runBlocking {
        val orch = ContractDagOrchestrator(architect, 2)
        val nodes = listOf(node("a", "A.kt"), node("b", "A.kt"))
        assertTrue(orch.lock(nodes).conflict)
        val run = orch.execute(nodes) { n, _ -> NodeResult.Ok(listOf(FileEdit(n.ownedFiles.single(), "", "x"))) }
        assertEquals("sequential", run.mode)
        assertTrue(run.fellBack)
        assertTrue(run.waves.all { it.size <= 1 })
        assertTrue(run.maxInFlight <= 1)
        assertEquals(architect, run.architectModelId)
        assertTrue(run.committed)
        assertFalse(run.staged.edits().isEmpty())
    }

    @Test fun repeatedFailureDoesNotCommit() = runBlocking {
        val orch = ContractDagOrchestrator(architect, 2)
        val run = orch.execute(listOf(node("a", "A.kt"))) { _, contract ->
            assertEquals(architect, contract.architectModelId)
            NodeResult.Fail("çakışma")
        }
        assertFalse(run.committed)
        assertTrue(run.staged.edits().isEmpty())
        assertEquals(architect, run.architectModelId)
        assertTrue(run.fellBack || run.mode == "sequential")
    }

    @Test fun concurrencyOneNeverOverlapsAndACycleDoesNotCommit() = runBlocking {
        val orch = ContractDagOrchestrator(architect, 1)
        val parallel = orch.execute(listOf(node("a", "A.kt"), node("b", "B.kt"))) { n, _ ->
            NodeResult.Ok(listOf(FileEdit(n.ownedFiles.single(), "", "x")))
        }
        assertTrue(parallel.waves.all { it.size == 1 })
        assertTrue(parallel.maxInFlight <= 1)
        assertEquals(architect, parallel.architectModelId)
        val cycle = ContractDagOrchestrator(architect, 2)
        val nodes = listOf(node("a", "A.kt", listOf("b")), node("b", "B.kt", listOf("a")))
        assertTrue(cycle.lock(nodes).conflict)
        val run = cycle.execute(nodes) { _, _ -> NodeResult.Ok(emptyList()) }
        assertFalse(run.committed)
        assertTrue(run.staged.edits().isEmpty())
        assertEquals(architect, run.architectModelId)
    }

    @Test fun missingTestExpectationFallsBackAndArchitectStays() = runBlocking {
        val orch = ContractDagOrchestrator(architect, 2)
        val bare = listOf(DagNode("a", emptyList(), listOf("A.kt"), listOf("fun a(): Unit"), "a"))
        val contract = orch.lock(bare)
        assertTrue(contract.signatures.any { it.contains("fun a") })
        assertTrue(contract.testExpectations.isEmpty())
        assertTrue(contract.conflict)
        assertTrue(contract.problems.any { it.contains("test") })
        assertEquals(architect, contract.architectModelId)
        val run = orch.execute(bare) { n, locked ->
            assertEquals(architect, locked.architectModelId)
            NodeResult.Ok(listOf(FileEdit(n.ownedFiles.single(), "", "x")))
        }
        assertEquals("sequential", run.mode)
        assertTrue(run.fellBack)
        assertEquals(architect, run.architectModelId)
        assertFalse(orch.retarget("other"))
    }
}
