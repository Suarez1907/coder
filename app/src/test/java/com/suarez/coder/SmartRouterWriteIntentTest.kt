package com.suarez.coder

import com.suarez.coder.decision.Route
import com.suarez.coder.decision.RouterAccessPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartRouterWriteIntentTest {
    private fun phase(request: String, route: Route = Route.QUESTION_ONLY): String =
        RouterAccessPolicy.decide(request, "locked-architect", "implementer", forcedRoute = route).phase

    @Test fun writeVerbForcesReadWriteEvenForQuestionRoute() {
        assertEquals("READ_WRITE", phase("Bu hatayı düzelt"))
        assertEquals("READ_WRITE", phase("Yeni test ekle"))
        assertEquals("READ_WRITE", phase("github_apply_diff kullanarak değişikliği uygula"))
    }

    @Test fun explicitInspectOnlyRequestStaysReadOnly() {
        assertEquals("READ_ONLY", phase("sadece incele ve sorunları raporla"))
        assertEquals("READ_ONLY", phase("yalnızca analiz et"))
    }

    @Test fun negatedWriteRequestStaysReadOnly() {
        assertEquals("READ_ONLY", phase("değiştirme, sadece incele"))
        assertEquals("READ_ONLY", phase("commit etme"))
    }
}