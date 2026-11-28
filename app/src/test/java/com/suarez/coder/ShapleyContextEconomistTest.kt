package com.suarez.coder

import com.suarez.coder.evolution.ShapleyContextEconomist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapleyContextEconomistTest {
    @Test
    fun quarantinesHarmfulAutoRuleProtectsManualRuleAndPacksByRoi() {
        val good = ShapleyContextEconomist.ContextItem("r_good", "rule", ShapleyContextEconomist.Source.AUTO, 100)
        val badAuto = ShapleyContextEconomist.ContextItem("r_bad_auto", "rule", ShapleyContextEconomist.Source.AUTO, 500)
        val badManual = ShapleyContextEconomist.ContextItem("r_bad_manual", "rule", ShapleyContextEconomist.Source.MANUAL, 500)
        val records = ArrayList<ShapleyContextEconomist.AttributionRecord>()
        for (i in 1..6) {
            records += ShapleyContextEconomist.AttributionRecord("g$i", setOf("r_good"), setOf("r_good"), true, 0.01)
            records += ShapleyContextEconomist.AttributionRecord("b$i", setOf("r_bad_auto", "r_bad_manual"), emptySet(), false, 0.20)
        }
        val vals = ShapleyContextEconomist.evaluate(listOf(good, badAuto, badManual), records)
        val byId = vals.associateBy { it.itemId }
        assertEquals(ShapleyContextEconomist.Status.QUARANTINED, byId["r_bad_auto"]!!.status)
        assertEquals(ShapleyContextEconomist.Status.LOW_VALUE_WARNING, byId["r_bad_manual"]!!.status)
        assertEquals(ShapleyContextEconomist.Status.ACTIVE, byId["r_good"]!!.status)

        val packed = ShapleyContextEconomist.packByRoi(listOf(badAuto, good, badManual), vals, maxTokens = 200)
        assertEquals(listOf("r_good"), packed.map { it.id })
        assertTrue(byId["r_good"]!!.roiPer1kTokens > byId["r_bad_auto"]!!.roiPer1kTokens)
    }
}
