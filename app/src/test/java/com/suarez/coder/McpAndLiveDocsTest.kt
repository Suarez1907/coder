package com.suarez.coder

import com.suarez.coder.docs.DependencyVersionIndex
import com.suarez.coder.docs.DocFetch
import com.suarez.coder.docs.DocFetcher
import com.suarez.coder.docs.LiveDocsProvider
import com.suarez.coder.mcp.McpClient
import com.suarez.coder.mcp.McpHttpResult
import com.suarez.coder.mcp.McpJson
import com.suarez.coder.mcp.McpRegistry
import com.suarez.coder.mcp.McpServerConfig
import com.suarez.coder.mcp.McpToolInfo
import com.suarez.coder.mcp.McpTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpAndLiveDocsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun parsesJsonRpcToolsAndSseResult() {
        val body = """
            {"jsonrpc":"2.0","id":2,"result":{"tools":[
              {"name":"echo","description":"Echo","annotations":{"readOnlyHint":true}},
              {"name":"write_file","description":"write a file","annotations":{"readOnlyHint":false}}
            ]}}
        """.trimIndent()
        val tools = McpJson.parseToolsList(body)
        assertEquals(listOf("echo", "write_file"), tools.map { it.name })
        assertFalse(tools[0].write)
        assertTrue(tools[1].write)
        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"pong\"}]}}\n\n"
        assertEquals("pong", McpJson.parseToolResult(sse))
        val req = McpJson.request("tools/list", JSONObject(), 1)
        assertTrue(req.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(req.contains("tools/list"))
    }

    @Test fun readOnlyServerRunsWriteToolsWithoutApproval() {
        val ro = McpRegistry(
            listOf(McpServerConfig("Docs", "https://docs.example", readOnly = true)),
            listOf(McpToolInfo("Docs", "write_file", "write", write = true), McpToolInfo("Docs", "echo", "Echo", false)),
        )
        assertEquals("mcp_docs_write_file", McpRegistry.toolName("Docs", "write_file"))
        assertFalse(ro.needsApproval("mcp_docs_write_file"))
        val rw = McpRegistry(
            listOf(McpServerConfig("Ops", "https://ops.example", readOnly = false)),
            listOf(McpToolInfo("Ops", "write_file", "write", true)),
        )
        assertTrue(rw.needsApproval("mcp_ops_write_file"))
        assertFalse(rw.needsApproval("mcp_ops_missing"))
    }

    @Test fun summaryIsAlphabeticalAndSkipsDisabledServers() {
        val reg = McpRegistry(listOf(
            McpServerConfig("zeta", "https://z.example", enabled = true),
            McpServerConfig("alpha", "https://a.example", enabled = true),
            McpServerConfig("gamma", "https://g.example", enabled = false),
        ))
        val summary = reg.compactSummary()
        assertTrue(summary.indexOf("alpha") < summary.indexOf("zeta"))
        assertFalse(summary.contains("gamma"))
        val names = reg.bound().map { it.qualifiedName }
        assertEquals(names.sorted(), names)
    }

    @Test fun redirectDropsAuthorizationOnAnotherHost() = runBlocking {
        val calls = ArrayList<Map<String, String>>()
        val transport = McpTransport { url, _, headers, _ ->
            calls += headers
            if (url.contains("mcp.example")) McpHttpResult(302, "", location = "https://cdn.example/mcp")
            else McpHttpResult(200, """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"echo","description":"e"}]}}""")
        }
        val tools = McpClient(transport).listTools(McpServerConfig("a", "https://mcp.example/rpc", authHeader = "Bearer t"))
        assertEquals("echo", tools.single().name)
        assertEquals("Bearer t", calls[0]["Authorization"])
        assertFalse(calls[1].containsKey("Authorization"))
    }

    @Test fun extractsVersionsFromBuildFiles() {
        val index = DependencyVersionIndex.extract(mapOf(
            "gradle/libs.versions.toml" to "[versions]\ncompose = \"1.7.5\"\n[libraries]\n",
            "app/build.gradle.kts" to "implementation(\"androidx.core:core-ktx:1.15.0\")\n",
            "package.json" to """{"dependencies":{"react":"^18.2.0"}}""",
            "Cargo.toml" to "[dependencies]\nserde = \"1.0.200\"\n",
            "project.godot" to "config/features=PackedStringArray(\"4.3\")\n",
        ))
        assertEquals("1.7.5", index.versionOf("compose"))
        assertEquals("1.15.0", index.versionOf("core-ktx"))
        assertEquals("18.2.0", index.versionOf("react"))
        assertEquals("1.0.200", index.versionOf("serde"))
        assertEquals("4.3", index.versionOf("godot"))
    }

    @Test fun docsCacheLastsSevenDaysAndFollowsRedirectWithoutAuth() = runBlocking {
        val dir = tmp.newFolder()
        var clock = 1_000L
        var calls = 0
        val seen = ArrayList<String?>()
        val provider = LiveDocsProvider(dir, DocFetcher { url, headers ->
            calls++
            seen += headers["Authorization"]
            if (url.contains("context7.com")) DocFetch(302, "", "https://cdn.example/doc")
            else DocFetch(200, "x".repeat(9_000), null)
        }, now = { clock })
        val first = provider.fetch("compose", "LazyColumn", "1.7.5", "secret", null)
        val second = provider.fetch("compose", "LazyColumn", "1.7.5", "secret", null)
        assertTrue(first.length <= LiveDocsProvider.MAX_CHARS)
        assertEquals(first, second)
        assertEquals(2, calls)
        assertEquals("Bearer secret", seen[0])
        assertNull(seen[1])
        clock += LiveDocsProvider.TTL_MS + 5
        provider.fetch("compose", "LazyColumn", "1.7.5", "secret", null)
        assertEquals(4, calls)
    }
}
