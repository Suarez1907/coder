package com.suarez.coder

import com.suarez.coder.formal.FsmModelChecker
import com.suarez.coder.formal.OrchestrationFsm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FsmModelCheckerTest {
    @Test
    fun verifiesAllFiveSafetyPropertiesExhaustivelyAndCatchesInjectedBug() {
        val report = FsmModelChecker.verifyAll()
        assertTrue(report.summary(), report.verified)
        assertEquals(5, report.propertiesPassed)
        assertTrue(report.statesExplored > 10)

        // Inject a broken transition allowing merge during evolution session
        val broken = FsmModelChecker.verifyAll { s, ev ->
            if (ev == OrchestrationFsm.FsmEvent.REQUEST_AUTO_MERGE && s.isEvolutionSession) {
                s.copy(mergeExecuted = true, phase = OrchestrationFsm.Phase.MERGING)
            } else {
                OrchestrationFsm.transition(s, ev)
            }
        }
        assertFalse(broken.verified)
        assertNotNull(broken.counterexample)
        assertTrue(broken.counterexample!!.eventTrace.isNotEmpty())
    }
}
