package com.suarez.coder

import com.suarez.coder.agent.ReadOnlyMismatchRecovery
import com.suarez.coder.decision.LocalRouter
import com.suarez.coder.decision.Route
import com.suarez.coder.tools.ToolRegistry.Companion.accessError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAccessTest {
    @Test fun architectCanRunRepositoryOperationsButNotEditCode() {
        assertNull(accessError("github_create_pull_request", null, readOnly = true, allowOps = true))
        assertNull(accessError("github_merge_pull_request", null, readOnly = true, allowOps = true))
        assertNull(accessError("github_run_workflow", null, readOnly = true, allowOps = true))
        assertNull(accessError("github_api", "POST", readOnly = true, allowOps = true))
        assertNull(accessError("github_read_files", null, readOnly = true, allowOps = true))
        assertNotNull(accessError("github_apply_diff", null, readOnly = true, allowOps = true))
        assertNotNull(accessError("github_write_files", null, readOnly = true, allowOps = true))
    }

    @Test fun questionModeIsReadOnly() {
        assertNull(accessError("github_api", "GET", readOnly = true, allowOps = false))
        assertNull(accessError("github_list_symbols", null, readOnly = true, allowOps = false))
        assertNull(accessError("github_read_range", null, readOnly = true, allowOps = false))
        assertNull(accessError("github_list_symbols", null, readOnly = true, allowOps = true))
        assertNull(accessError("github_read_range", null, readOnly = true, allowOps = true))
        assertNotNull(accessError("github_api", "POST", readOnly = true, allowOps = false))
        assertNotNull(accessError("github_merge_pull_request", null, readOnly = true, allowOps = false))
    }

    @Test fun agentHasFullAccess() {
        assertNull(accessError("github_apply_diff", null, readOnly = false, allowOps = false))
        assertNull(accessError("github_api", "DELETE", readOnly = false, allowOps = false))
    }

    @Test fun repositoryOperationsGoToTheCheapAgent() {
        assertEquals(Route.SIMPLE_EDIT, LocalRouter.classify("feat/levels dalı için PR aç ve merge et", 0)?.route)
        assertEquals(Route.SIMPLE_EDIT, LocalRouter.classify("Android APK workflow'unu v1.3.0 ile çalıştır", 0)?.route)
        // A question about releases is still a question.
        assertEquals(Route.QUESTION_ONLY, LocalRouter.classify("release nasıl oluşturuluyor?", 0)?.route)
    }

    // ---- Yetki kurtarma (salt okunur / adaptif yönlendirme çakışması) ----

    @Test fun readOnlyIsBoundToThePhaseNotToTheModel() {
        // Gerçek soru: salt okunur kalır.
        assertTrue(ReadOnlyMismatchRecovery.readOnlyForRoute(Route.QUESTION_ONLY, "release nasıl oluşturuluyor?"))
        // Yazma niyetli görev QUESTION'a düşse bile salt okunur başlamaz.
        assertFalse(ReadOnlyMismatchRecovery.readOnlyForRoute(Route.QUESTION_ONLY, "bu hatayı düzelt ve commit et"))
        assertFalse(ReadOnlyMismatchRecovery.readOnlyForRoute(Route.SIMPLE_EDIT, "release notunu güncelle"))
        assertTrue(ReadOnlyMismatchRecovery.hasWriteIntent("README'yi güncelle"))
        assertFalse(ReadOnlyMismatchRecovery.hasWriteIntent("bu dosya ne yapıyor?"))
    }

    @Test fun readOnlyMismatchIsDetectedOnlyForWriteIntent() {
        val denial = "Üzgünüm, salt okunur moddayım; yazma yetkim yok."
        assertTrue(ReadOnlyMismatchRecovery.isMismatch(denial, writeIntent = true, readOnly = true))
        // Yazma niyeti yoksa veya oturum zaten yazabiliyorsa kurtarma tetiklenmez.
        assertFalse(ReadOnlyMismatchRecovery.isMismatch(denial, writeIntent = false, readOnly = true))
        assertFalse(ReadOnlyMismatchRecovery.isMismatch(denial, writeIntent = true, readOnly = false))
        assertFalse(ReadOnlyMismatchRecovery.isMismatch("Dosyayı düzelttim ve commit ettim.", writeIntent = true, readOnly = true))
    }

    @Test fun mismatchRecoveryUpgradesOnceThenHandsOff() {
        assertEquals(ReadOnlyMismatchRecovery.Action.UPGRADE, ReadOnlyMismatchRecovery.next(0))
        assertEquals(ReadOnlyMismatchRecovery.Action.HANDOFF, ReadOnlyMismatchRecovery.next(ReadOnlyMismatchRecovery.MAX_UPGRADES))
        assertTrue(ReadOnlyMismatchRecovery.statusLine(ReadOnlyMismatchRecovery.Action.UPGRADE).contains("READ_WRITE"))
        assertTrue(ReadOnlyMismatchRecovery.statusLine(ReadOnlyMismatchRecovery.Action.HANDOFF).contains("Uygulayıcı"))
        // Faz kaydı da tek satır ve şeffaf olmalı.
        val phase = ReadOnlyMismatchRecovery.phaseLine()
        assertTrue(phase.contains("READ_ONLY") && phase.contains("Uygulayıcı") && !phase.contains("\n"))
    }

    @Test fun surgicalReadToolsWorkInReadOnlyAndWriteTaskKeepsReadWriteOnEscalation() {
        assertNull(accessError("github_list_symbols", null, readOnly = true, allowOps = false))
        assertNull(accessError("github_read_range", null, readOnly = true, allowOps = false))
        assertFalse(ReadOnlyMismatchRecovery.readOnlyForRoute(Route.SIMPLE_EDIT, "salınım sonrası dosyayı düzelt"))
        assertFalse(ReadOnlyMismatchRecovery.readOnlyForRoute(Route.QUESTION_ONLY, "hatayı düzelt ve commit et"))
        assertNull(accessError("github_apply_diff", null, readOnly = false, allowOps = false))
    }

    @Test fun escalationKeepsReadWriteForWriteTasksAndSurgicalReadsStayAllowed() {
        // PreFlight/salınım fazında cerrahi okuma araçları READ_ONLY'de engellenmez.
        assertNull(accessError("github_list_symbols", null, readOnly = true, allowOps = false))
        assertNull(accessError("github_read_range", null, readOnly = true, allowOps = false))
        // Yazma görevi eskalasyonda READ_WRITE yetkisini korur (READ_ONLY'ye düşmez).
        assertNull(accessError("github_write_files", null, readOnly = false, allowOps = false))
        assertNull(accessError("github_apply_diff", null, readOnly = false, allowOps = false))
        // Rota eskalasyon sırasında QUESTION'a düşse bile yazma niyeti READ_WRITE'ı korur.
        assertFalse(ReadOnlyMismatchRecovery.readOnlyForRoute(Route.QUESTION_ONLY, "salınım sonrası hatayı düzelt ve commit et"))
        assertTrue(ReadOnlyMismatchRecovery.hasWriteIntent("dosyayı güncelle"))
        // Salt okunur fazda yazma araçları reddedilir.
        assertNotNull(accessError("github_write_files", null, readOnly = true, allowOps = false))
    }
}
