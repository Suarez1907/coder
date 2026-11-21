package com.suarez.coder

import com.suarez.coder.cost.Pricing
import com.suarez.coder.decision.DecisionChain
import com.suarez.coder.decision.DecisionClient
import com.suarez.coder.decision.DecisionException
import com.suarez.coder.decision.DecisionProvider
import com.suarez.coder.decision.DecisionResult
import com.suarez.coder.decision.FilePreview
import com.suarez.coder.decision.Route
import com.suarez.coder.decision.RouterLogic
import com.suarez.coder.llm.Usage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterDecisionTest {
    // Response shape from OpenRouter's Decisions reference: noul answers carry no confidence.
    private val sample = """
        {
          "id": "gen-dec-1",
          "model": "typesafe/jev-1.14-20260917",
          "provider": "TypeSafe",
          "answers": {
            "route": { "type": "choice", "choice": "SIMPLE_EDIT",
                       "probabilities": { "SIMPLE_EDIT": 0.70, "COMPLEX_ARCHITECT": 0.25, "QUESTION_ONLY": 0.05 },
                       "confidence": 0.70 },
            "complexity": { "type": "score", "score": 1.2,
                            "legend": { "0": "a", "1": "b", "2": "c", "3": "d" },
                            "probabilities": { "0": 0.1, "1": 0.6, "2": 0.3, "3": 0 }, "confidence": 0.6 },
            "f0": { "type": "noul", "noul": 0.96 },
            "f1": { "type": "noul", "noul": 0.10 }
          },
          "usage": { "input_tokens": 476, "output_tokens": 70, "cost": 0.000019992 }
        }
    """.trimIndent()

    @Test fun requestUsesStateAndDocumentedQuestionShapes() {
        val q = listOf(RouterLogic.routeQuestion(), RouterLogic.complexityQuestion(), RouterLogic.fileQuestion(0, FilePreview("a.kt", null)))
        val body = DecisionClient.buildBody("typesafe/jev-1.14", "İstek", q, DecisionProvider.OPENROUTER)
        assertEquals("typesafe/jev-1.14", body.getString("model"))
        assertEquals("İstek", body.getString("state"))
        assertFalse(body.has("input"))
        val qs = body.getJSONObject("questions")
        // choice: criteria is a map; score: ordered 4-level array without min/max; noul: no criteria needed.
        assertEquals(3, qs.getJSONObject("route").getJSONObject("criteria").length())
        val score = qs.getJSONObject("complexity")
        assertEquals(4, score.getJSONArray("criteria").length())
        assertFalse(score.has("min") || score.has("max"))
        assertEquals("noul", qs.getJSONObject("f0").getString("type"))
        // Gemini keeps its own field name.
        assertTrue(DecisionClient.buildBody("gemini-decision-1", "x", q).has("input"))
    }

    @Test fun parsesAnswersCostAndNoulWithoutConfidence() {
        val r = DecisionClient.parse(sample, 120)
        assertEquals(476L, r.inputTokens)
        assertEquals(0.000019992, r.costUsd!!, 1e-12)
        assertEquals("typesafe/jev-1.14-20260917", r.model)
        assertEquals(0.96, r.answers.getValue("f0").noul!!, 1e-9)
        assertEquals(0.96, r.answers.getValue("f0").confidence, 1e-9) // derived from noul
        assertEquals(0.90, r.answers.getValue("f1").confidence, 1e-9)
        assertEquals(1.2, r.answers.getValue("complexity").score!!, 1e-9)
    }

    @Test fun lowConfidenceSimpleEditGoesToArchitectAndKeepsComplexity() {
        val d = RouterLogic.interpret(DecisionClient.parse(sample, 120),
            listOf(FilePreview("a.kt", null), FilePreview("b.kt", null)), false, "me/app", null)
        assertEquals(Route.SIMPLE_EDIT, d.rawRoute)
        assertEquals(Route.COMPLEX_ARCHITECT, d.route) // 0.70 < 0.75
        assertTrue(d.upgraded)
        assertEquals(listOf("a.kt"), d.selected.map { it.path })
        assertEquals(1.2, d.complexity!!, 1e-9) // < 1.5 → medium architect in Agent
        assertEquals(0.000019992, d.costUsd!!, 1e-12)
        assertEquals("typesafe/jev-1.14-20260917", d.decisionModel)
    }

    @Test fun stateIsKeptUnderTheFastPathLimit() {
        val request = "Giriş ekranındaki butonu düzelt"
        val input = RouterLogic.buildInput(request, "x".repeat(5_000), "me/app", (1..3000).map { "src/module$it/File$it.kt" })
        assertTrue(input.length > DecisionClient.MAX_STATE_CHARS)
        val state = DecisionClient.buildBody("typesafe/jev-1.14", input, listOf(RouterLogic.routeQuestion()), DecisionProvider.OPENROUTER)
            .getString("state")
        assertTrue(state.length <= DecisionClient.MAX_STATE_CHARS)
        assertTrue(state.contains(request))
        assertTrue(state.endsWith("(kısaltıldı)"))
        assertEquals("kısa", DecisionClient.compactState("kısa"))
    }

    @Test fun clientSendsBearerToOpenRouterAndReadsCost() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(sample))
        server.start()
        try {
            val c = DecisionClient("sk-or", "typesafe/jev-1.14", 2_000, DecisionProvider.OPENROUTER, server.url("/api/alpha/decisions").toString())
            val r = c.decide("istek", listOf(RouterLogic.routeQuestion()))
            val req = server.takeRequest()
            assertEquals("Bearer sk-or", req.getHeader("Authorization"))
            assertNull(req.getHeader("x-goog-api-key"))
            assertTrue(JSONObject(req.body.readUtf8()).has("state"))
            assertEquals(0.000019992, r.costUsd!!, 1e-12)
        } finally { server.shutdown() }
    }

    private fun ok(model: String) = DecisionResult(emptyMap(), 10, 5, null, model)

    @Test fun chainFallsBackOnQuotaErrorAndTimeout() = runBlocking {
        val chain = DecisionChain(listOf(
            "Google" to { _, _ -> throw DecisionException("kota aşıldı (429)") },
            "OpenRouter" to { _, _ -> ok("typesafe/jev-1.14") },
        ))
        assertEquals("typesafe/jev-1.14", chain.decide("x", emptyList()).model)
        assertEquals(1, chain.skipped.size)
        assertTrue(chain.skipped[0].contains("429"))

        val slow = DecisionChain(listOf(
            "Google" to { _, _ -> withTimeout(10) { delay(1_000); ok("never") } },
            "OpenRouter" to { _, _ -> ok("jev") },
        ))
        assertEquals("jev", slow.decide("x", emptyList()).model)
        assertTrue(slow.skipped.single().contains("zaman aşımı"))
    }

    @Test fun chainThrowsWhenAllFailSoCheapRouterTakesOver() = runBlocking {
        val chain = DecisionChain(listOf(
            "Google" to { _, _ -> throw DecisionException("HTTP 500") },
            "OpenRouter" to { _, _ -> throw java.io.IOException("ağ yok") },
        ))
        val e = runCatching { chain.decide("x", emptyList()) }.exceptionOrNull()
        assertTrue(e is DecisionException)
        assertTrue(e!!.message!!.contains("HTTP 500") && e.message!!.contains("ağ yok"))
        assertTrue(runCatching { DecisionChain(emptyList()).decide("x", emptyList()) }.exceptionOrNull() is DecisionException)
    }

    @Test fun jevFallbackPriceIsInputOnly() {
        val p = Pricing.defaultFor("typesafe/jev-1.14")
        assertEquals(0.042, p.input, 1e-12)
        assertEquals(0.0, p.output, 1e-12)
        assertEquals(0.042, Pricing.defaultFor("~typesafe/jev-latest").input, 1e-12)
        assertEquals(0.042 * 476 / 1e6, Pricing.cost(Usage(input = 476), p), 1e-15)
    }
}
