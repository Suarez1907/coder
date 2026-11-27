package com.suarez.coder.mcp

import org.json.JSONObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One configured MCP server. [readOnly] servers run immediately; write tools on the others ask first. */
data class McpServerConfig(
    val name: String,
    val endpointUrl: String,
    val authHeader: String? = null,
    val enabled: Boolean = true,
    val readOnly: Boolean = true,
)

data class McpToolInfo(
    val server: String,
    val name: String,
    val description: String = "",
    val write: Boolean = false,
)

data class McpHttpResult(val code: Int, val body: String, val contentType: String? = null, val location: String? = null)

fun interface McpTransport {
    suspend fun exchange(url: String, body: String, headers: Map<String, String>, accept: String): McpHttpResult
}

/** JSON-RPC 2.0 bodies for Streamable HTTP and SSE. Parsing does not touch the network. */
object McpJson {
    fun request(method: String, params: JSONObject?, id: Int): String {
        val o = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        if (params != null) o.put("params", params)
        return o.toString()
    }

    fun parseToolsList(body: String): List<McpToolInfo> {
        val payload = unwrap(body) ?: return emptyList()
        val result = payload.optJSONObject("result") ?: return emptyList()
        val arr = result.optJSONArray("tools") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ann = o.optJSONObject("annotations")
            val hint = if (ann != null && ann.has("readOnlyHint")) ann.optBoolean("readOnlyHint") else null
            McpToolInfo("", name, o.optString("description"), write = isWrite(name, o.optString("description"), hint))
        }
    }

    fun parseToolResult(body: String): String {
        val payload = unwrap(body) ?: return body.take(4_000)
        payload.optJSONObject("error")?.let { return "MCP hata: " + it.optString("message").ifBlank { it.toString() } }
        val result = payload.opt("result") ?: return payload.toString().take(4_000)
        if (result is JSONObject) {
            val content = result.optJSONArray("content")
            if (content != null) {
                return (0 until content.length()).joinToString("\n") { i ->
                    val c = content.optJSONObject(i)
                    c?.optString("text").orEmpty().ifBlank { c?.toString().orEmpty() }
                }.ifBlank { result.toString() }.take(8_000)
            }
        }
        return result.toString().take(8_000)
    }

    fun unwrap(body: String): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return runCatching { JSONObject(trimmed) }.getOrNull()
        val data = trimmed.lineSequence().map { it.trim() }.filter { it.startsWith("data:") }.map { it.substringAfter("data:").trim() }
            .lastOrNull { it.startsWith("{") } ?: return null
        return runCatching { JSONObject(data) }.getOrNull()
    }

    fun isWrite(name: String, description: String, readOnlyHint: Boolean?): Boolean {
        if (readOnlyHint == true) return false
        if (readOnlyHint == false) return true
        val blob = "$name $description".lowercase()
        val verbs = listOf("write", "create", "update", "delete", "remove", "set_", "send", "post", "put", "exec", "insert", "drop")
        return verbs.any { it in blob }
    }
}

/**
 * Native client. Redirects are followed by the caller with [McpTransport] so tests stay offline.
 * [follow] drops Authorization when the host changes after a 302.
 */
class McpClient(private val transport: McpTransport) {
    suspend fun initialize(server: McpServerConfig): String {
        val params = JSONObject().put("protocolVersion", "2024-11-05").put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "coder").put("version", "4"))
        return rpc(server, "initialize", params, 1)
    }

    suspend fun listTools(server: McpServerConfig): List<McpToolInfo> {
        val raw = rpc(server, "tools/list", JSONObject(), 2)
        return McpJson.parseToolsList(raw).map { it.copy(server = server.name) }
    }

    suspend fun callTool(server: McpServerConfig, tool: String, arguments: JSONObject): String {
        val params = JSONObject().put("name", tool).put("arguments", arguments)
        return McpJson.parseToolResult(rpc(server, "tools/call", params, 3))
    }

    private suspend fun rpc(server: McpServerConfig, method: String, params: JSONObject, id: Int): String {
        val body = McpJson.request(method, params, id)
        val accept = "application/json, text/event-stream"
        var url = server.endpointUrl
        var headers = headers(server, keepAuth = true)
        var hops = 0
        while (true) {
            val res = transport.exchange(url, body, headers, accept)
            val next = res.location?.takeIf { res.code in 300..399 } ?: return res.body
            if (++hops > 5) return res.body
            val resolved = java.net.URI(url).resolve(next).toString()
            val sameHost = runCatching { java.net.URI(resolved).host == java.net.URI(url).host }.getOrDefault(false)
            url = resolved
            headers = headers(server, keepAuth = sameHost)
        }
    }

    private fun headers(server: McpServerConfig, keepAuth: Boolean): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = "application/json, text/event-stream"
        if (keepAuth) server.authHeader?.takeIf { it.isNotBlank() }?.let { h["Authorization"] = it }
        return h
    }
}

fun defaultMcpTransport(): McpTransport = McpTransport { url, body, headers, accept ->
    val client = com.suarez.coder.llm.Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    val b = Request.Builder().url(url).post(body.toRequestBody(com.suarez.coder.llm.Http.JSON))
    headers.forEach { (k, v) -> b.header(k, v) }
    b.header("Accept", accept)
    val r = com.suarez.coder.llm.Http.execute(b.build(), client)
    McpHttpResult(r.code, r.body, r.contentType, r.location)
}
