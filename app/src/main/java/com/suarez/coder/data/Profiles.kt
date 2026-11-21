package com.suarez.coder.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A project the user works on: repo plus the settings that belong to it. */
data class ProjectProfile(
    val id: String = UUID.randomUUID().toString().take(8),
    val repo: String = "",
    val branch: String = "",
    val instructions: String = "",
    /** "provider/model" or blank = keep the global choice. */
    val architectModel: String = "",
    val editorModel: String = "",
    val contextRef: String = "",
    val contextFiles: List<String> = emptyList(),
) {
    val name: String get() = repo.substringAfterLast('/').ifBlank { "Profil ${id.take(4)}" }
}

object ProfileJson {
    fun toJson(list: List<ProjectProfile>): String = JSONArray().also { arr ->
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("repo", p.repo).put("branch", p.branch).put("instr", p.instructions)
                .put("arch", p.architectModel).put("edit", p.editorModel).put("cref", p.contextRef).put("cfiles", JSONArray(p.contextFiles)))
        }
    }.toString()

    fun fromJson(s: String): List<ProjectProfile> = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ProjectProfile(
                id = o.getString("id"), repo = o.optString("repo"), branch = o.optString("branch"),
                instructions = o.optString("instr"), architectModel = o.optString("arch"), editorModel = o.optString("edit"),
                contextRef = o.optString("cref"),
                contextFiles = o.optJSONArray("cfiles")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            )
        }
    }.getOrDefault(emptyList())
}
