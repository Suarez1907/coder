package com.suarez.coder.mcp

import org.json.JSONArray
import org.json.JSONObject

/** Active servers, alphabetical compact summary, and the mcp_<server>_<tool> names bound into the tool registry. */
class McpRegistry(servers: List<McpServerConfig> = emptyList(), tools: List<McpToolInfo> = emptyList()) {
    val servers: List<McpServerConfig> = servers.sortedBy { it.name.lowercase() }
    private val tools: List<McpToolInfo> = tools.sortedWith(compareBy({ it.server.lowercase() }, { it.name.lowercase() }))

    fun enabled(): List<McpServerConfig> = servers.filter { it.enabled && it.endpointUrl.isNotBlank() }

    fun bound(): List<BoundTool> = enabled().flatMap { server ->
        tools.filter { it.server.equals(server.name, true) }.map { tool ->
            BoundTool(toolName(server.name, tool.name), server, tool.name, tool.description, tool.write && !server.readOnly)
        }
    }.sortedBy { it.qualifiedName }

    /** Enabled servers only, alphabetical, one short line each. Task context, not the stable system prefix. */
    fun compactSummary(): String {
        val rows = enabled()
        if (rows.isEmpty()) return ""
        return rows.joinToString("\n") { s ->
            val n = bound().count { it.server.name.equals(s.name, true) }
            val mode = if (s.readOnly) "salt-okunur" else "yazma-onaylı"
            "- ${s.name} ($mode, $n araç)"
        }
    }

    fun needsApproval(qualifiedName: String): Boolean = bound().firstOrNull { it.qualifiedName == qualifiedName }?.write == true

    fun find(qualifiedName: String): BoundTool? = bound().firstOrNull { it.qualifiedName == qualifiedName }

    data class BoundTool(
        val qualifiedName: String,
        val server: McpServerConfig,
        val tool: String,
        val description: String,
        val write: Boolean,
    )

    companion object {
        fun toolName(server: String, tool: String): String = "mcp_${slug(server)}_${slug(tool)}"

        fun slug(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifBlank { "x" }

        fun serversToJson(list: List<McpServerConfig>): String = JSONArray().also { arr ->
            list.forEach { s ->
                arr.put(JSONObject().put("name", s.name).put("endpointUrl", s.endpointUrl)
                    .put("authHeader", s.authHeader ?: "").put("enabled", s.enabled).put("readOnly", s.readOnly))
            }
        }.toString()

        fun serversFromJson(json: String): List<McpServerConfig> = runCatching {
            val a = JSONArray(json.ifBlank { "[]" })
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                if (name.isBlank()) return@mapNotNull null
                McpServerConfig(name, o.optString("endpointUrl").trim(), o.optString("authHeader").trim().ifBlank { null },
                    o.optBoolean("enabled", true), o.optBoolean("readOnly", true))
            }
        }.getOrDefault(emptyList())

        fun toolsToJson(list: List<McpToolInfo>): String = JSONArray().also { arr ->
            list.forEach { t ->
                arr.put(JSONObject().put("server", t.server).put("name", t.name).put("description", t.description).put("write", t.write))
            }
        }.toString()

        fun toolsFromJson(json: String): List<McpToolInfo> = runCatching {
            val a = JSONArray(json.ifBlank { "[]" })
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                if (name.isBlank()) return@mapNotNull null
                McpToolInfo(o.optString("server"), name, o.optString("description"), o.optBoolean("write"))
            }
        }.getOrDefault(emptyList())
    }
}
