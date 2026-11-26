package com.suarez.coder

import com.suarez.coder.ci.AutoHealer
import com.suarez.coder.ci.CiKnowledgeBase
import org.junit.Assert.assertEquals
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
class AutoHealerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun kb() = CiKnowledgeBase(tmp.newFile("ci-lessons.json").also { it.delete() })

    @Test fun transientFailureIsRerun() {
        val d = AutoHealer.decide("Error: java.net.SocketException: Connection reset by peer", "me/app")
        assertEquals(AutoHealer.Action.RERUN, d.action)
        assertEquals(CiKnowledgeBase.Cause.INFRA, d.cause)
    }

    @Test fun knownFlakyTestsAreRerun() {
        val log = "FooTest > bar() FAILED\n"
        assertEquals(AutoHealer.Action.RERUN, AutoHealer.decide(log, "me/app", knownFlaky = setOf("FooTest.bar()")).action)
        assertEquals(AutoHealer.Action.ESCALATE, AutoHealer.decide(log, "me/app", knownFlaky = setOf("Other.x")).action)
    }

    @Test fun knownFixWinsOverEscalation() {
        val k = kb()
        k.record("me/app", "e: Main.kt:3:1 Unresolved reference: foo", "import eklendi", 1)
        val d = AutoHealer.decide("e: Main.kt:9:4 Unresolved reference: foo", "me/app", k)
        assertEquals(AutoHealer.Action.USE_KNOWN_FIX, d.action)
        assertEquals(CiKnowledgeBase.Cause.COMPILE, d.cause)
        assertNotNull(d.hint)
        assertTrue(AutoHealer.notice(d).startsWith("CI teşhisi · derleme"))
    }

    @Test fun repeatedRoundsAskTheUser() {
        val d = AutoHealer.decide("error TS2345: Argument of type 'string' is not assignable", "me/app", round = 3)
        assertEquals(AutoHealer.Action.ASK, d.action)
        assertNull(d.hint)
        assertEquals("error TS2345: Argument of type 'string' is not assignable", d.signature)
    }
}
