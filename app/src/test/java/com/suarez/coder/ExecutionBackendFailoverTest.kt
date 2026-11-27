package com.suarez.coder

import com.suarez.coder.execution.ExecResult
import com.suarez.coder.execution.ExecutionBackend
import com.suarez.coder.execution.HotSandboxBackend
import com.suarez.coder.execution.SandboxHttp
import com.suarez.coder.execution.SandboxResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ExecutionBackendFailoverTest {
    @Test fun unhealthySandboxFallsBackToGitHubActions() = runBlocking {
        val github = CountingBackend()
        val hot = HotSandboxBackend("https://sandbox.example", "tok", ScriptedHttp(false, 42), github)
        val result = hot.runBuildAndTest("me/app", "dev", "./gradlew test")
        assertEquals("github", result.backendId)
        assertEquals(HotSandboxBackend.FAILOVER, result.notice)
        assertEquals(1, github.builds)
        assertTrue(result.output.contains("github"))
    }

    @Test fun healthySandboxStaysPutAndReportsLatency() = runBlocking {
        val github = CountingBackend()
        val hot = HotSandboxBackend("https://sandbox.example", "tok", ScriptedHttp(true, 42), github)
        val result = hot.runCloudTool("blender", "tools/chest.py", "coder-out")
        assertEquals("sandbox", result.backendId)
        assertNull(result.notice)
        assertEquals(0, github.builds)
        assertEquals("Bağlantıyı Test Et · 42 ms", hot.pingLabel())
    }

    private class ScriptedHttp(private val up: Boolean, private val elapsed: Long) : SandboxHttp {
        override suspend fun request(method: String, url: String, token: String, body: String?, timeoutMs: Long): SandboxResponse {
            if (url.endsWith("/health")) {
                if (!up) throw IOException("down")
                return SandboxResponse(200, "{\"ok\":true}", elapsed)
            }
            return SandboxResponse(200, "{\"id\":\"job1\"}", 5)
        }
    }

    private class CountingBackend : ExecutionBackend {
        var builds = 0
        override val id = "github"
        override suspend fun runBuildAndTest(repo: String, branch: String, command: String): ExecResult {
            builds++
            return ExecResult(id, true, "github $command")
        }
        override suspend fun runCloudTool(tool: String, scriptPath: String, outputDir: String) =
            ExecResult(id, true, "github").also { builds++ }
        override suspend fun captureVisualSnapshots(repo: String, branch: String) = ExecResult(id, true, "github")
    }
}
