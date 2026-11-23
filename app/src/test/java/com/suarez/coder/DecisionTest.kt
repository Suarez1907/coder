package com.suarez.coder

import com.suarez.coder.decision.DecisionClient
import com.suarez.coder.decision.DecisionQuestion
import com.suarez.coder.decision.DecisionResult
import com.suarez.coder.decision.FilePreview
import com.suarez.coder.decision.Route
import com.suarez.coder.decision.RouterLogic
import com.suarez.coder.decision.SmartRouter
import com.suarez.coder.tools.GitHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionTest {
    // Shapes from the Decisions API documentation.
    private val sample = """
        {
          "answers": {
            "route": { "type": "choice", "choice": "SIMPLE_EDIT",
                       "probabilities": { "SIMPLE_EDIT": 0.92, "COMPLEX_ARCHITECT": 0.05, "QUESTION_ONLY": 0.03 },
                       "confidence": 0.92 },
            "f0": { "type": "noul", "noul": 0.97, "confidence": 0.97 },
            "f1": { "type": "noul", "noul": 0.55, "confidence": 0.55 },
            "f2": { "type": "noul", "noul": 0.03, "confidence": 0.97 }
          },
          "usage": { "total_input_tokens": 1234, "total_output_tokens": 0, "total_tokens": 1234 }
        }
    """.trimIndent()

    private val files = listOf(FilePreview("a.kt", "x"), FilePreview("b.kt", null), FilePreview("c.kt", null))

    @Test fun buildsDocumentedRequestShape() {
        val body = DecisionClient.buildBody("gemini-decision-1", "input text",
            listOf(RouterLogic.routeQuestion(), DecisionQuestion("f0", "noul", "Q?")))
        assertEquals("gemini-decision-1", body.getString("model"))
        assertEquals("input text", body.getString("input"))
        val route = body.getJSONObject("questions").getJSONObject("route")
        assertEquals("choice", route.getString("type"))
        assertEquals(3, route.getJSONObject("criteria").length())
        assertFalse(body.getJSONObject("questions").getJSONObject("f0").has("criteria"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidQuestionKeys() {
        DecisionClient.buildBody("m", "in", listOf(DecisionQuestion("src/a.kt", "noul", "Q?")))
    }

    @Test fun parsesAnswersAndUsage() {
        val r = DecisionClient.parse(sample, 180)
        assertEquals(1234, r.inputTokens)
        assertEquals("SIMPLE_EDIT", r.answers["route"]!!.choice)
        assertEquals(0.92, r.answers["route"]!!.confidence, 1e-9)
        assertEquals(0.97, r.answers["f0"]!!.noul!!, 1e-9)
    }

    @Test fun highConfidenceSimpleEditStaysSimpleAndFilesAboveThresholdSelected() {
        val d = RouterLogic.interpret(DecisionClient.parse(sample, 180), files, false, "o/r", null)
        assertEquals(Route.SIMPLE_EDIT, d.route)
        assertFalse(d.upgraded)
        assertEquals(listOf("a.kt"), d.selected.map { it.path }) // 0.55 is below 0.60, 0.03 is "no"
        assertEquals(3, d.totalFiles)
    }

    @Test fun lowConfidenceSimpleEditIsUpgraded() {
        val json = JSONObject(sample)
        json.getJSONObject("answers").getJSONObject("route").put("confidence", 0.70)
        val d = RouterLogic.interpret(DecisionClient.parse(json.toString(), 10), files, false, "o/r", null)
        assertEquals(Route.COMPLEX_ARCHITECT, d.route)
        assertEquals(Route.SIMPLE_EDIT, d.rawRoute)
        assertTrue(d.upgraded)
    }

    @Test fun questionOnlyIsNotUpgradedEvenWithLowConfidence() {
        val json = JSONObject(sample)
        json.getJSONObject("answers").getJSONObject("route").put("choice", "QUESTION_ONLY").put("confidence", 0.5)
        val d = RouterLogic.interpret(DecisionClient.parse(json.toString(), 10), files, false, null, null)
        assertEquals(Route.QUESTION_ONLY, d.route)
    }

    private val github = GitHub { "" }
    private val tree: suspend (String, String?) -> List<GitHub.TreeEntry> = { _, _ -> listOf(GitHub.TreeEntry("a.kt", 10)) }

    @Test fun apiErrorFallsBackWithoutThrowing() = runBlocking {
        val router = SmartRouter(github, tree) { _, _ -> throw RuntimeException("HTTP 429") }
        val d = router.route("ekrana buton ekle", null, "o/r", null, false)
        assertNotNull(d.fallbackReason)
        assertTrue(d.selected.isEmpty())
    }

    @Test fun timeoutFallsBack() = runBlocking {
        val router = SmartRouter(github, tree) { _, _ -> withTimeout(20) { delay(1_000) }; error("unreachable") }
        val d = router.route("x", null, "o/r", null, false)
        assertEquals("zaman aşımı", d.fallbackReason)
    }

    @Test fun manualFilesSkipFileQuestions() = runBlocking {
        var asked: List<DecisionQuestion> = emptyList()
        val router = SmartRouter(github, tree) { _, q -> asked = q; DecisionClient.parse(sample, 5) }
        val d = router.route("x", null, "o/r", null, manualFiles = true)
        assertNull(d.fallbackReason)
        assertEquals(listOf("route", "complexity", "clear"), asked.map { it.key }) // no file questions
        assertTrue(d.manualFiles)
        assertTrue(d.selected.isEmpty())
    }

    @Test fun rankingPrefersPathsMentionedInRequest() {
        val paths = listOf("docs/readme.md", "src/game/Score.kt", "src/game/Player.kt")
        assertEquals("src/game/Score.kt", RouterLogic.rank("Score ekranındaki hatayı düzelt", paths).first())
        // Without a name match, source files still come before docs.
        assertEquals("docs/readme.md", RouterLogic.rank("hatayı düzelt", paths).last())
    }
}
