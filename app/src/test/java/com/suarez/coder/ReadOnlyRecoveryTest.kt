package com.suarez.coder

import com.suarez.coder.agent.ReadOnlyMismatchRecovery
import com.suarez.coder.decision.Route
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyRecoveryTest {
    @Test fun recognizesWriteIntentVerbStems() {
        assertTrue(ReadOnlyMismatchRecovery.hasWriteIntent("Uygula"))
        assertTrue(ReadOnlyMismatchRecovery.hasWriteIntent("düzeltin"))
        assertTrue(ReadOnlyMismatchRecovery.hasWriteIntent("ekle ve commit et"))
        assertTrue(ReadOnlyMismatchRecovery.hasWriteIntent("github_apply_diff ile push et"))
    }

    @Test fun explicitDenyWins() {
        assertFalse(ReadOnlyMismatchRecovery.hasWriteIntent("sadece incele, repoyu değiştirme"))
        assertFalse(ReadOnlyMismatchRecovery.hasWriteIntent("kod yazma, push etme"))
    }

    @Test fun readOnlyOnlyForPureQuestion() {
        assertTrue(ReadOnlyMismatchRecovery.readOnlyForRoute(Route.QUESTION_ONLY, "bu kod ne yapıyor açıkla"))
        assertFalse(ReadOnlyMismatchRecovery.readOnlyForRoute(Route.QUESTION_ONLY, "düzelt"))
    }

    @Test fun mismatchDetected() {
        assertTrue(ReadOnlyMismatchRecovery.isMismatch("yetkim yok", writeIntent = true, readOnly = true))
        assertFalse(ReadOnlyMismatchRecovery.isMismatch("yetkim yok", writeIntent = false, readOnly = true))
        assertFalse(ReadOnlyMismatchRecovery.isMismatch("yetkim yok", writeIntent = true, readOnly = false))
    }
}
