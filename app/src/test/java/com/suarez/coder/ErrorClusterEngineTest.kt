package com.suarez.coder

import com.suarez.coder.evolution.ClusterStatus
import com.suarez.coder.evolution.ClusterThresholds
import com.suarez.coder.evolution.ErrorClusterEngine
import com.suarez.coder.evolution.SessionTrace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ErrorClusterEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun engine() = ErrorClusterEngine(File(tmp.newFolder(), "clusters.json"))

    private val searchWindow = """{"kind":"search_replace","content":"fun a() {\n    val x = 1\n}\n","search":"    val x = 1","replace":"    val x = 2","expect":"ok","contains":"val x = 2"}"""
    private val logWindow = """{"kind":"log_filter","log":"Downloading\ne: Unresolved reference 'Foo'\nBUILD FAILED\n","mustContain":["e: Unresolved reference 'Foo'"],"mustNotContain":["(Belirgin hata satırı bulunamadı"]}"""

    private fun trace(id: String, session: String, diff: Int = 0, ci: Int = 0, cost: Double = 0.01, rounds: Int = 0, window: Boolean = true) = SessionTrace(
        id = id, sessionId = session, repo = "me/app", at = 1, request = "Skor tablosu", modelsUsed = listOf("m"),
        turnCount = 2, cacheHitPercent = null, totalCostUsd = cost, diffErrorCount = diff,
        diffErrorSamples = if (diff > 0) listOf("SEARCH metni dosyada bulunamadı") else emptyList(),
        ciFailureCount = ci, ciSignatures = if (ci > 0) listOf("e: Unresolved reference 'Foo'") else emptyList(),
        escalations = emptyList(), routing = null, upgradedToArchitect = false, stopped = false,
        oscillationEscalationsCount = rounds,
        failureWindows = if (!window) emptyList() else if (ci > 0) listOf(logWindow) else listOf(searchWindow),
    )

    @Test fun oneOffStaysCollectingUntilASecondSessionOrTheCostFloor() {
        val e = engine()
        e.ingest(trace("t1", "s1", diff = 1, cost = 0.01))
        val first = e.all().single { it.clusterKey.startsWith("search_replace_miss") }
        assertEquals(ClusterStatus.COLLECTING, first.status)
        assertFalse(e.shouldOpen(first))

        e.ingest(trace("t2", "s2", diff = 1, cost = 0.01))
        val ready = e.all().single { it.clusterKey.startsWith("search_replace_miss") }
        assertEquals(ClusterStatus.READY_FOR_PR, ready.status)
        assertEquals(2, ready.distinctSessionIds.size)
        assertTrue(e.shouldOpen(ready))

        val byCost = engine()
        byCost.ingest(trace("t3", "only", diff = 1, cost = 0.05))
        assertEquals(ClusterStatus.READY_FOR_PR, byCost.all().single { it.clusterKey.startsWith("search_replace_miss") }.status)
    }

    @Test fun dismissedClusterDoesNotRearm() {
        val e = engine()
        e.ingest(trace("t1", "s1", diff = 1, cost = 0.01))
        val key = e.all().single { it.clusterKey.startsWith("search_replace_miss") }.clusterKey
        e.dismiss(key)
        e.ingest(trace("t2", "s2", diff = 1, cost = 0.20))
        val cluster = e.all().single { it.clusterKey == key }
        assertEquals(ClusterStatus.DISMISSED, cluster.status)
        assertFalse(e.shouldOpen(cluster))
        assertTrue(cluster.distinctSessionIds.containsAll(listOf("s1", "s2")))
    }

    @Test fun threeFailedRoundsAreEnough() {
        val e = engine()
        e.ingest(trace("t1", "s1", ci = 3, cost = 0.001), ClusterThresholds(minSessions = 2, minCostUsd = 0.05, minFailedRounds = 3))
        val cluster = e.all().single { it.category == "ci" }
        assertEquals(ClusterStatus.READY_FOR_PR, cluster.status)
        assertTrue(cluster.failedFixRounds >= 3)
    }

    @Test fun withoutARealSnippetTheClusterStaysCollecting() {
        val e = engine()
        e.ingest(trace("t1", "s1", diff = 1, cost = 0.20, window = false))
        e.ingest(trace("t2", "s2", diff = 1, cost = 0.20, window = false))
        val cluster = e.all().single { it.clusterKey.startsWith("search_replace_miss") }
        assertEquals(ClusterStatus.COLLECTING, cluster.status)
        assertFalse(e.shouldOpen(cluster))
    }
}
