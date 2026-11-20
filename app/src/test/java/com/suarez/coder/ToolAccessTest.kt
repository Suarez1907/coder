package com.suarez.coder

import com.suarez.coder.decision.LocalRouter
import com.suarez.coder.decision.Route
import com.suarez.coder.tools.ToolRegistry.Companion.accessError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
