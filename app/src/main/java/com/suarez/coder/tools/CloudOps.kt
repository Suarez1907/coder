package com.suarez.coder.tools

import com.suarez.coder.agent.CodeIndex
import org.json.JSONObject

/** Cloud-backed code tools the registry calls (implemented by the agent; null without app storage). */
interface CloudOps {
    /** The repo's code index (may be stale; a missing or old one is rebuilt in the background). */
    suspend fun codeIndex(repo: String, refresh: Boolean): CodeIndex?
    suspend fun codemod(repo: String, branch: String, base: String?, args: JSONObject): String
    suspend fun mutation(repo: String, branch: String, path: String, from: Int?, to: Int?, tests: List<String>?): String
}
