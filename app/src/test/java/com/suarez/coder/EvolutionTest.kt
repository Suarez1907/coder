package com.suarez.coder

import com.suarez.coder.decision.LocalRouter
import com.suarez.coder.decision.Route
import com.suarez.coder.evolution.AdaptiveRouting
import com.suarez.coder.evolution.EscalationNote
import com.suarez.coder.evolution.EvoTier
import com.suarez.coder.evolution.EvolutionGuard
import com.suarez.coder.evolution.EvolutionGuard.Block
import com.suarez.coder.evolution.EvolutionReport
import com.suarez.coder.evolution.EvolutionStore
import com.suarez.coder.evolution.LocalHint
import com.suarez.coder.evolution.PendingSelfPr
import com.suarez.coder.evolution.ReflectorEngine
import com.suarez.coder.evolution.SelfPrOrchestrator
import com.suarez.coder.evolution.SessionTraceRecorder
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.Usage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EvolutionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun run(vararg extra: ChatMessage) = listOf<ChatMessage>(
        ChatMessage.UsageRecord("gemini-3.8-flash", "editor", Usage(input = 1000, cached = 600, output = 50), actualUsd = 0.002),
        ChatMessage.Notice("✏️ Uygulayıcı: 2 blok, 1 dosya (1 sorun)", "x", isError = true),
        ChatMessage.Notice("⤴️ Onarım denemesi 4: Yükseltme modeli (gemini-3.8-flash)."),
        ChatMessage.ToolResult("c1", "ci_wait", "Commit abc:\n- Build: failure\n\nHatalar (Build, run_id 1):\n## build (failure)\ne: Unresolved reference 'Foo'\n"),
        ChatMessage.RouteInfo("Decision 1 → Mimar Modu (basit düzenleme %60 < %75, yükseltildi)", null, "COMPLEX_ARCHITECT", true),
        ChatMessage.Assistant("Bitti"),
    ) + extra

    private fun trace() = SessionTraceRecorder.record(run(), "Skor ekle", "s1", "me/app", isEvolutionSession = false, costOf = { it.actualUsd ?: 0.0 }, now = 1_000L)!!

    // ---- Infinite loop guard ----

    @Test fun evolutionSessionsAreNeverRecordedOrRun() {
        assertNull(SessionTraceRecorder.record(run(), "x", "s", "me/app", isEvolutionSession = true, costOf = { 0.0 }))
        assertEquals(Block.EVOLUTION_SESSION, EvolutionGuard.mayRun(true, isEvolutionSession = true, hasKey = true, spentToday = 0.0,
            dailyBudget = 1.0, lastRunAt = 0, now = 10_000_000, force = true))
        assertFalse(EvolutionGuard.mayRecord(isEvolutionSession = true, hadWork = true))
    }

    @Test fun traceCapturesErrorsCiRoutingAndCost() {
        val t = trace()
        assertEquals(listOf("gemini-3.8-flash"), t.modelsUsed)
        assertEquals(60, t.cacheHitPercent)
        assertEquals(0.002, t.totalCostUsd, 1e-12)
        assertEquals(1, t.diffErrorCount)
        assertEquals(1, t.ciFailureCount)
        assertTrue(t.ciSignatures.single().contains("Unresolved reference 'Foo'"))
        assertEquals(1, t.escalations.size)
        assertTrue(t.upgradedToArchitect)
        assertTrue(t.troubleScore >= EvolutionGuard.MIN_TROUBLE_FOR_PR)
        // Runs without any coding step are not recorded.
        assertNull(SessionTraceRecorder.record(listOf(ChatMessage.Assistant("Merhaba")), "selam", "s", "", false, { 0.0 }))
        assertTrue(SessionTraceRecorder.isCorrection("Hayır, olmadı tekrar dene"))
        assertFalse(SessionTraceRecorder.isCorrection("Harika, teşekkürler"))
    }

    // ---- Budget, cooldown and the single open PR rule ----

    @Test fun budgetCooldownAndSwitch() {
        val now = 100_000_000L
        assertEquals(Block.BUDGET, EvolutionGuard.mayRun(true, false, true, spentToday = 0.15, dailyBudget = 0.15, lastRunAt = 0, now = now))
        assertEquals(Block.COOLDOWN, EvolutionGuard.mayRun(true, false, true, 0.0, 0.15, lastRunAt = now - 60_000, now = now))
        assertEquals(Block.OK, EvolutionGuard.mayRun(true, false, true, 0.0, 0.15, lastRunAt = now - 60_000, now = now, force = true))
        assertEquals(Block.DISABLED, EvolutionGuard.mayRun(false, false, true, 0.0, 0.15, 0, now))
        assertEquals(Block.NO_KEY, EvolutionGuard.mayRun(true, false, false, 0.0, 0.15, 0, now))
        assertEquals(Block.OK, EvolutionGuard.mayRun(true, false, true, 0.10, 0.15, 0, now))
    }

    @Test fun noNewPrWhileOneIsOpenOrOverBudget() {
        fun gate(open: Int = 0, local: Boolean = false, spent: Double = 0.0, topic: String = "diff-ellipsis", rejected: Set<String> = emptySet(), trouble: Int = 5) =
            EvolutionGuard.mayOpenPr(open, local, spent, 0.15, topic, rejected, trouble)
        assertEquals(Block.OK, gate())
        assertEquals(Block.OPEN_PR, gate(open = 1))
        assertEquals(Block.OPEN_PR, gate(local = true))
        assertEquals(Block.BUDGET, gate(spent = 0.2))
        assertEquals(Block.REJECTED_TOPIC, gate(topic = "Diff Ellipsis", rejected = setOf("diff-ellipsis")))
        assertEquals(Block.NO_EVIDENCE, gate(trouble = 1))
        // The same problem under another slug (what produced PR #2 and #3) is blocked too.
        assertEquals(Block.REJECTED_TOPIC, gate(topic = "logfilter-false-positive-license", rejected = setOf("logfilter-legal-boilerplate-false-positive")))
        assertEquals(Block.OK, gate(topic = "search-replace-ellipsis", rejected = setOf("logfilter-legal-boilerplate")))
    }

    @Test fun storeTracksSpendPendingAndRejections() {
        val store = EvolutionStore(tmp.newFolder("evo"))
        store.addLog(EvoTier.MEMORY, "a", 0.05)
        store.addLog(EvoTier.INFO, "b", 0.04)
        assertEquals(0.09, store.spentToday(), 1e-9)
        assertEquals(0.0, store.spentSince(System.currentTimeMillis() + 1_000), 1e-9)
        val pr = PendingSelfPr(7, "t", "u", "evo/x-1", "c", listOf("A.kt"), true, 0.03, "topic", 1)
        store.savePending(pr)
        assertEquals(pr, store.pending())
        store.reject("Diff Ellipsis")
        assertTrue("diff-ellipsis" in store.rejectedTopics())
        store.savePending(null)
        assertNull(store.pending())
        val t = trace()
        store.addTrace(t)
        assertEquals(1, store.unprocessed().size)
        store.updateTrace(t.id) { it.copy(processed = true) }
        assertTrue(store.unprocessed().isEmpty())
    }

    // ---- Notes pruning ----

    @Test fun notesAreKeptUnderTheLimit() = runBlocking {
        val long = ReflectorEngine.LESSONS_HEADING + "\n" + (1..200).joinToString("\n") { "- Ders numarası $it: bu repoda kural $it geçerli" }
        assertTrue(long.length > EvolutionGuard.NOTES_LIMIT)
        assertTrue(EvolutionGuard.hardPrune(long).length <= EvolutionGuard.NOTES_LIMIT)
        assertTrue(EvolutionGuard.hardPrune(long).startsWith(ReflectorEngine.LESSONS_HEADING))
        // The model is asked to merge first; even a useless answer cannot exceed the limit.
        var asked = 0
        val bad = ReflectorEngine { _, _, _ -> asked++; long + long }
        assertTrue(bad.pruneIfNeeded(long).length <= EvolutionGuard.NOTES_LIMIT)
        assertEquals(1, asked)
        val good = ReflectorEngine { _, _, _ -> "${ReflectorEngine.LESSONS_HEADING}\n- Gradle JDK 21 gerektirir" }
        assertEquals("${ReflectorEngine.LESSONS_HEADING}\n- Gradle JDK 21 gerektirir", good.pruneIfNeeded(long))
        // Short notes are not sent to the model at all.
        val none = ReflectorEngine { _, _, _ -> error("not called") }
        assertEquals("kısa", none.pruneIfNeeded("kısa"))
    }

    @Test fun lessonsMergeWithoutDuplicates() {
        val a = ReflectorEngine.mergeLessons("", listOf("Gradle JDK 21 gerektirir"))
        assertEquals("${ReflectorEngine.LESSONS_HEADING}\n- Gradle JDK 21 gerektirir", a)
        assertEquals(a, ReflectorEngine.mergeLessons(a, listOf("gradle jdk 21 gerektirir")))
        assertTrue(ReflectorEngine.mergeLessons(a, listOf("TopAppBar için sabit height kullanma")).endsWith("- TopAppBar için sabit height kullanma"))
    }

    @Test fun reflectorReportParsesFromFencedText() {
        val text = "İşte:\n```json\n{\"lessons\":[\"Gradle JDK 21 gerektirir\",\"x\"],\"routing\":{\"complexity_threshold\":9,\"local_hints\":" +
            "[{\"pattern\":\"rengini değiştir\",\"route\":\"SIMPLE\"}],\"escalation_notes\":[]},\"self_improvement\":{\"title\":\"Elips eşleşmesi\"," +
            "\"problem\":\"... içeren SEARCH eşleşmiyor\",\"files\":[\"app/src/main/java/com/suarez/coder/diff/SearchReplace.kt\"],\"topic\":\"ellipsis\",\"repeatable\":true}}\n```"
        val r = ReflectorEngine.parse(text)!!
        assertEquals(listOf("Gradle JDK 21 gerektirir"), r.lessons) // too-short lesson dropped
        assertEquals(9.0, r.threshold!!, 1e-9)
        assertEquals("ellipsis", r.selfImprovement!!.topic)
        assertNull(ReflectorEngine.parse("JSON yok"))
        assertNull(ReflectorEngine.parse("{\"lessons\":[],\"self_improvement\":{\"title\":\"t\",\"files\":[\"a.kt\"],\"repeatable\":false}}")!!.selfImprovement)
    }

    // ---- Adaptive routing: hard limits and undo ----

    private fun report(threshold: Double? = null, hints: List<LocalHint> = emptyList(), notes: List<EscalationNote> = emptyList()) =
        EvolutionReport(emptyList(), threshold, hints, notes, null)

    @Test fun thresholdNeverLeavesItsBoundsAndCanBeUndone() {
        val store = EvolutionStore(tmp.newFolder("evo")).routing
        val (before, after) = store.apply(report(threshold = 5.0))!!
        assertEquals(EvolutionGuard.DEFAULT_THRESHOLD, before.complexityThreshold, 1e-9)
        assertEquals(EvolutionGuard.MAX_THRESHOLD, after.complexityThreshold, 1e-9)
        store.apply(report(threshold = 0.1))
        assertEquals(EvolutionGuard.MIN_THRESHOLD, store.current().complexityThreshold, 1e-9)
        store.apply(report(threshold = Double.NaN))
        assertEquals(EvolutionGuard.MIN_THRESHOLD, store.current().complexityThreshold, 1e-9)
        // A tampered file is clamped on read as well.
        assertEquals(EvolutionGuard.MAX_THRESHOLD, AdaptiveRouting.fromJson("{\"threshold\": 99}").complexityThreshold, 1e-9)
        store.undo(before.toJson().toString())
        assertEquals(EvolutionGuard.DEFAULT_THRESHOLD, store.current().complexityThreshold, 1e-9)
        assertNull(store.apply(report())) // nothing changed
    }

    @Test fun onlySafeHintsAreLearnedAndUsedByTheLocalRouter() {
        val store = EvolutionStore(tmp.newFolder("evo")).routing
        store.apply(report(hints = listOf(
            LocalHint("animasyon hızı", "SIMPLE_EDIT"), LocalHint("x", "SIMPLE"), LocalHint("rm -rf /; drop", "SIMPLE"),
            LocalHint("geçiş efekti", "DELETE_ALL"),
        ), notes = listOf(EscalationNote("SIMPLE_EDIT", "gemini/gemini-3.1-flash-lite", "sık diff hatası"))))
        val r = store.current()
        assertEquals(listOf(LocalHint("animasyon hızı", "SIMPLE")), r.localHints)
        assertTrue(r.earlyEscalation("gemini/gemini-3.1-flash-lite"))
        val hints = r.localHints.map { it.pattern to it.route }
        assertEquals(Route.SIMPLE_EDIT, LocalRouter.classify("Menüdeki animasyon hızı biraz fazla, ayarlanabilir mi bilmiyorum", 0, hints)?.route)
        assertNull(LocalRouter.classify("Menüdeki animasyon hızı biraz fazla, ayarlanabilir mi bilmiyorum", 0))
    }

    // ---- Self-PR security lock ----

    @Test fun plansTouchingSecretsOrTheEngineAreRejected() {
        for (p in listOf("app/src/main/java/com/suarez/coder/data/Settings.kt", "app/src/main/java/com/suarez/coder/evolution/EvolutionGuard.kt",
            ".github/workflows/build.yml", "app/coder.keystore", "app/build.gradle.kts", "app/src/main/java/com/suarez/coder/cost/Pricing.kt",
            "app/src/main/java/com/suarez/coder/apk/ApkDownloader.kt", "app/src/main/java/com/suarez/coder/auth/TokenStore.kt")) {
            assertTrue(p, EvolutionGuard.isProtected(p))
        }
        assertFalse(EvolutionGuard.isProtected("app/src/main/java/com/suarez/coder/diff/SearchReplace.kt"))
        assertNotNull(EvolutionGuard.checkPlan(listOf("app/src/main/java/com/suarez/coder/diff/SearchReplace.kt"), "githubToken'ı loglara yaz"))
        assertNotNull(EvolutionGuard.checkPlan((1..5).map { "app/src/main/java/A$it.kt" }, "x"))
        assertNull(EvolutionGuard.checkPlan(listOf("app/src/main/java/com/suarez/coder/diff/SearchReplace.kt"), "elips eşleşmesini düzelt"))

        fun plan(src: String, test: String = "app/src/test/java/com/suarez/coder/EllipsisTest.kt") =
            "{\"title\":\"t\",\"root_cause\":\"r\",\"test\":{\"path\":\"$test\",\"action\":\"create\",\"instructions\":\"i\"}," +
                "\"files\":[{\"path\":\"$src\",\"action\":\"modify\",\"instructions\":\"i\"}]}"
        val ok = SelfPrOrchestrator.parsePlan(plan("app/src/main/java/com/suarez/coder/diff/SearchReplace.kt"))
        assertNull(ok.second)
        assertEquals("app/src/test/java/com/suarez/coder/EllipsisTest.kt", ok.first!!.files.first().path) // test first
        assertNotNull(SelfPrOrchestrator.parsePlan(plan("app/src/main/java/com/suarez/coder/evolution/SelfPrOrchestrator.kt")).second)
        assertNotNull(SelfPrOrchestrator.parsePlan(plan("app/src/main/java/com/suarez/coder/data/Settings.kt")).second)
        assertNotNull(SelfPrOrchestrator.parsePlan(plan("app/src/main/java/X.kt", test = "app/src/main/java/NotATest.kt")).second)
        assertTrue(SelfPrOrchestrator.branchName("Diff Ellipsis!", 0).startsWith("evo/diff-ellipsis-"))
    }
}
