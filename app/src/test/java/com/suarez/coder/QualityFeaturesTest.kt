package com.suarez.coder

import androidx.test.core.app.ApplicationProvider
import com.suarez.coder.agent.Agent
import com.suarez.coder.data.Settings
import com.suarez.coder.decision.Route
import com.suarez.coder.decision.RouterLogic
import com.suarez.coder.decision.RoutingDecision
import com.suarez.coder.evolution.ProjectHealth
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.orchestrator.MultiAgentOrchestrator
import com.suarez.coder.orchestrator.VisualDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Clarify-first, visual regression, project health and best-of-two selection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QualityFeaturesTest {
    private fun decision(route: Route, clarity: Double?) = RoutingDecision(route, route, 0.9, false, emptyList(), 0, false, 0, 0, clarity = clarity)

    @Test fun clarificationOnlyForVagueChangeRequests() {
        val s = Settings(ApplicationProvider.getApplicationContext())
        s.clarifyFirst = true
        val agent = Agent(s)
        val h = listOf<ChatMessage>(ChatMessage.User("bunu daha iyi yap"))
        assertTrue(agent.needsClarification(decision(Route.SIMPLE_EDIT, 0.2), h))
        assertFalse(agent.needsClarification(decision(Route.SIMPLE_EDIT, 0.8), h))
        assertFalse(agent.needsClarification(decision(Route.QUESTION_ONLY, 0.1), h))
        assertFalse(agent.needsClarification(decision(Route.SIMPLE_EDIT, null), h))
        // The user just answered our questions: never ask twice.
        val answered = listOf(ChatMessage.User("bunu daha iyi yap"), ChatMessage.Assistant(Agent.CLARIFY_MARK + " Başlamadan önce..."), ChatMessage.User("1: evet"))
        assertFalse(agent.needsClarification(decision(Route.COMPLEX_ARCHITECT, 0.1), answered))
        s.clarifyFirst = false
        assertFalse(agent.needsClarification(decision(Route.SIMPLE_EDIT, 0.1), h))
    }

    @Test fun clarityQuestionIsAsked() {
        assertEquals(RouterLogic.CLARITY_KEY, RouterLogic.clarityQuestion().key)
    }

    @Test fun visualDiffRatio() {
        val a = IntArray(VisualDiff.SIZE * VisualDiff.SIZE) { 100 }
        assertEquals(0.0, VisualDiff.ratio(a, a.copyOf()), 1e-9)
        val b = a.copyOf().also { for (i in 0 until it.size / 2) it[i] = 250 }
        assertEquals(0.5, VisualDiff.ratio(a, b), 1e-9)
        val small = a.copyOf().also { for (i in it.indices) it[i] = 110 } // below the cell threshold
        assertEquals(0.0, VisualDiff.ratio(a, small), 1e-9)
    }

    @Test fun projectHealthHelpers() {
        assertTrue(ProjectHealth.isBehind("8.7.3", "8.10.0"))
        assertFalse(ProjectHealth.isBehind("2.0.21", "2.0.21"))
        assertFalse(ProjectHealth.isBehind("9.0", "8.9.1"))
        assertEquals("8.9", ProjectHealth.gradleWrapperVersion("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip"))
        assertEquals("2.0.21", ProjectHealth.kotlinVersion("[versions]\nagp = \"8.7.3\"\nkotlin = \"2.0.21\"\n"))
        assertEquals("1.9.24", ProjectHealth.kotlinVersion("plugins { id(\"org.jetbrains.kotlin.android\") version \"1.9.24\" }"))
        val deps = ProjectHealth.npmDeps("""{"dependencies":{"react":"^18.2.0"},"devDependencies":{"vite":"~5.1.0"}}""")
        assertEquals(listOf("react" to "^18.2.0", "vite" to "~5.1.0"), deps)
        assertTrue(ProjectHealth.npmDeps("bozuk").isEmpty())
        val r = ProjectHealth.RepoHealth("a/b", "failure", 2, 3, listOf("Gradle 7.6 → 8.10"))
        val back = ProjectHealth.fromJson(r.toJson())
        assertEquals(r, back)
        assertTrue(ProjectHealth.lineFromJson(r.toJson()).startsWith("! a/b: CI failure"))
        assertNull(ProjectHealth.fromJson(ProjectHealth.RepoHealth("x/y", null, 0, null, emptyList()).toJson()).ci)
    }

    @Test fun bestOfTwoSelection() {
        assertFalse(MultiAgentOrchestrator.pickSecond(2.0, 1.0, 1.0, 0.1))
        assertTrue(MultiAgentOrchestrator.pickSecond(1.0, 2.0, 0.1, 1.0))
        assertTrue(MultiAgentOrchestrator.pickSecond(1.0, 1.0, 0.5, 0.2)) // tie: cheaper wins
        assertFalse(MultiAgentOrchestrator.pickSecond(1.0, 1.0, 0.2, 0.5))
        assertTrue(MultiAgentOrchestrator.pickSecond(null, 0.0, 0.1, 0.1))
        assertFalse(MultiAgentOrchestrator.pickSecond(null, null, 0.5, 0.1))
    }
}
