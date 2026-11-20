package com.suarez.coder

import com.suarez.coder.decision.DecisionClient
import com.suarez.coder.decision.FilePreview
import com.suarez.coder.decision.Route
import com.suarez.coder.decision.RouterLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComplexityTest {
    @Test fun scoreQuestionUsesAnOrderedRubric() {
        val body = DecisionClient.buildBody("gemini-decision-1", "x", listOf(RouterLogic.complexityQuestion()))
        val q = body.getJSONObject("questions").getJSONObject("complexity")
        assertEquals("score", q.getString("type"))
        assertEquals(4, q.getJSONArray("criteria").length()) // array of levels, level 0 first
    }

    @Test fun complexityIsReadFromTheSameCall() {
        val response = """{"answers":{
            "route":{"type":"choice","choice":"COMPLEX_ARCHITECT","probabilities":{"COMPLEX_ARCHITECT":0.8,"SIMPLE_EDIT":0.15,"QUESTION_ONLY":0.05},"confidence":0.8},
            "complexity":{"type":"score","score":1.2,"probabilities":{"0":0.1,"1":0.6,"2":0.25,"3":0.05},"confidence":0.6}
        },"usage":{"total_input_tokens":500}}"""
        val d = RouterLogic.interpret(DecisionClient.parse(response, 100), emptyList<FilePreview>(), false, "o/r", null)
        assertEquals(Route.COMPLEX_ARCHITECT, d.route)
        assertEquals(1.2, d.complexity!!, 1e-9)
    }

    @Test fun missingComplexityIsNull() {
        val response = """{"answers":{"route":{"type":"choice","choice":"SIMPLE_EDIT","confidence":0.9}}}"""
        assertNull(RouterLogic.interpret(DecisionClient.parse(response, 1), emptyList(), false, null, null).complexity)
    }
}
