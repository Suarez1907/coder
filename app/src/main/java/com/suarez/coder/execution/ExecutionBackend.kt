package com.suarez.coder.execution

import com.suarez.coder.llm.Http
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Build, cloud-tool and snapshot entry points. GitHub Actions is the default.
 * The hot sandbox is optional and falls back without blocking the user.
 */
data class ExecResult(
    val backendId: String,
    val ok: Boolean,
    val output: String,
    val artifacts: List<String> = emptyList(),
    val notice: String? = null,
    val elapsedMs: Long? = null,
)

interface ExecutionBackend {
    val id: String
    suspend fun runBuildAndTest(repo: String, branch: String, command: String): ExecResult
    suspend fun runCloudTool(tool: String, scriptPath: String, outputDir: String): ExecResult
    suspend fun captureVisualSnapshots(repo: String, branch: String): ExecResult
}

class GitHubActionsBackend(
    private val dispatch: suspend (String, String, String) -> String = { repo, branch, command ->
        "GitHub Actions: $repo@$branch · $command"
    },
) : ExecutionBackend {
    override val id = "github"

    override suspend fun runBuildAndTest(repo: String, branch: String, command: String): ExecResult =
        ExecResult(id, true, dispatch(repo, branch, command))

    override suspend fun runCloudTool(tool: String, scriptPath: String, outputDir: String): ExecResult =
        ExecResult(id, true, dispatch("", "", "run_cloud_tool $tool $scriptPath $outputDir"))

    override suspend fun captureVisualSnapshots(repo: String, branch: String): ExecResult =
        ExecResult(id, true, dispatch(repo, branch, "visual-snapshots"))
}

data class SandboxResponse(val code: Int, val body: String, val elapsedMs: Long = 0)

fun interface SandboxHttp {
    suspend fun request(method: String, url: String, token: String, body: String?, timeoutMs: Long): SandboxResponse
}

class HotSandboxBackend(
    private val baseUrl: String,
    private val token: String,
    private val http: SandboxHttp,
    private val fallback: ExecutionBackend,
) : ExecutionBackend {
    override val id = "sandbox"

    data class Health(val ok: Boolean, val elapsedMs: Long)

    suspend fun health(): Health = runCatching {
        val res = http.request("GET", endpoint("/health"), token, null, 2_000)
        Health(res.code in 200..299, res.elapsedMs)
    }.getOrDefault(Health(false, 2_000))

    suspend fun pingLabel(): String {
        val h = health()
        return if (h.ok) "Bağlantıyı Test Et · ${h.elapsedMs} ms" else "Erişilemedi · GitHub Actions"
    }

    override suspend fun runBuildAndTest(repo: String, branch: String, command: String): ExecResult =
        remote("build", """{"repo":"$repo","branch":"$branch","command":${json(command)}}""") {
            fallback.runBuildAndTest(repo, branch, command)
        }

    override suspend fun runCloudTool(tool: String, scriptPath: String, outputDir: String): ExecResult =
        remote("tool", """{"tool":${json(tool)},"script":${json(scriptPath)},"output":${json(outputDir)}}""") {
            fallback.runCloudTool(tool, scriptPath, outputDir)
        }

    override suspend fun captureVisualSnapshots(repo: String, branch: String): ExecResult =
        remote("snapshots", """{"repo":"$repo","branch":"$branch","kind":"snapshots"}""") {
            fallback.captureVisualSnapshots(repo, branch)
        }

    private suspend fun remote(kind: String, payload: String, fallbackCall: suspend () -> ExecResult): ExecResult {
        val h = health()
        if (!h.ok) {
            val fb = fallbackCall()
            return fb.copy(notice = FAILOVER)
        }
        val res = runCatching { http.request("POST", endpoint("/v1/jobs"), token, payload, 10_000) }.getOrNull()
        if (res == null || res.code !in 200..299) {
            val fb = fallbackCall()
            return fb.copy(notice = FAILOVER)
        }
        return ExecResult(id, true, res.body.ifBlank { kind }, elapsedMs = h.elapsedMs)
    }

    private fun endpoint(path: String) = baseUrl.trimEnd('/') + path

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        const val FAILOVER = "Sıcak sandbox erişilemedi; GitHub Actions kullanılıyor."
    }
}

/** App transport. Redirects stay off; the health call uses the 2 second budget. */
fun defaultSandboxHttp(): SandboxHttp = SandboxHttp { method, url, token, body, timeoutMs ->
    val client = Http.client.newBuilder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    val b = Request.Builder().url(url).header("Authorization", "Bearer $token")
    val req = if (method.equals("POST", true)) b.post((body ?: "{}").toRequestBody(Http.JSON)).build() else b.get().build()
    val started = System.nanoTime()
    val r = Http.execute(req, client)
    SandboxResponse(r.code, r.body, (System.nanoTime() - started) / 1_000_000)
}
