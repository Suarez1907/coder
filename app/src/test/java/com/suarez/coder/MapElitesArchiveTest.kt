package com.suarez.coder

import com.suarez.coder.evolution.MapElitesArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapElitesArchiveTest {
    @Test
    fun enforcesFivePercentNonRegressivePromotionIsolationAndNearestFallback() {
        val archive = MapElitesArchive()
        val k1 = MapElitesArchive.NicheKey(MapElitesArchive.Complexity.SIMPLE, MapElitesArchive.Risk.LOW_RISK, MapElitesArchive.TaskDomain.KOTLIN_CORE)
        val k2 = MapElitesArchive.NicheKey(MapElitesArchive.Complexity.MODERATE, MapElitesArchive.Risk.LOW_RISK, MapElitesArchive.TaskDomain.KOTLIN_CORE)

        assertTrue(archive.tryPromote(k1, MapElitesArchive.EliteGenome("m1", fitnessScore = 0.80), zeroRegressions = true))
        // <5% gain must be rejected
        assertFalse(archive.tryPromote(k1, MapElitesArchive.EliteGenome("m2", fitnessScore = 0.82), zeroRegressions = true))
        // Regression must be rejected even with high fitness
        assertFalse(archive.tryPromote(k1, MapElitesArchive.EliteGenome("m3", fitnessScore = 0.99), zeroRegressions = false))
        // >=5% gain with zero regressions promotes cleanly
        assertTrue(archive.tryPromote(k1, MapElitesArchive.EliteGenome("m4", fitnessScore = 0.85), zeroRegressions = true))
        assertEquals("m4", archive.lookupOrNearest(k1)!!.implementerModelId)

        // Empty neighbor k2 safely inherits from nearest populated cell k1
        val inherited = archive.lookupOrNearest(k2)
        assertNotNull(inherited)
        assertEquals("m4", inherited!!.implementerModelId)
    }
}
