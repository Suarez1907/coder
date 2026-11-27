package com.suarez.coder.skills

import com.suarez.coder.harness.PreFlightValidator
import com.suarez.coder.harness.StagedTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class BlueprintTemplateAction {
    CREATE,
    PATCH_ANCHOR,
}

data class BlueprintSlot(
    val name: String,
    val type: String = "string",
    val example: String = "",
    val required: Boolean = true,
)

data class BlueprintFileTemplate(
    val pathTemplate: String,
    val action: BlueprintTemplateAction,
    val contentTemplate: String,
    val anchor: String? = null,
)

data class Blueprint(
    val name: String,
    val description: String,
    val techTag: String = "",
    val slots: List<BlueprintSlot> = emptyList(),
    val fileTemplates: List<BlueprintFileTemplate> = emptyList(),
    val verificationHints: List<String> = emptyList(),
    val usageCount: Int = 0,
    val draft: Boolean = false,
)

data class BlueprintApplyResult(
    val ok: Boolean,
    val blueprint: Blueprint,
    val stagedPaths: List<String> = emptyList(),
    val error: String? = null,
)

object BlueprintEngine {
    private val PLACEHOLDER = Regex("""\{\{([^{}]+)\}\}""")

    fun fromJson(json: String): Blueprint {
        val o = JSONObject(json)
        val slotsArr = o.optJSONArray("slots") ?: JSONArray()
        val slots = (0 until slotsArr.length()).map { idx ->
            val s = slotsArr.getJSONObject(idx)
            BlueprintSlot(
                name = s.getString("name"),
                type = s.optString("type", "string"),
                example = s.optString("example", ""),
                required = s.optBoolean("required", true),
            )
        }
        val tplArr = o.optJSONArray("fileTemplates") ?: JSONArray()
        val templates = (0 until tplArr.length()).map { idx ->
            val t = tplArr.getJSONObject(idx)
            val actionStr = t.optString("action", "CREATE").uppercase()
            val action = BlueprintTemplateAction.valueOf(actionStr)
            BlueprintFileTemplate(
                pathTemplate = t.getString("pathTemplate"),
                action = action,
                contentTemplate = t.getString("contentTemplate"),
                anchor = if (t.isNull("anchor")) null else t.optString("anchor").takeIf { it.isNotEmpty() },
            )
        }
        val hintsArr = o.optJSONArray("verificationHints") ?: JSONArray()
        val hints = (0 until hintsArr.length()).map { hintsArr.getString(it) }
        return Blueprint(
            name = o.getString("name"),
            description = o.optString("description", ""),
            techTag = o.optString("techTag", ""),
            slots = slots,
            fileTemplates = templates,
            verificationHints = hints,
            usageCount = o.optInt("usageCount", 0),
            draft = o.optBoolean("draft", false),
        )
    }

    fun toJson(blueprint: Blueprint): String {
        val o = JSONObject()
            .put("name", blueprint.name)
            .put("description", blueprint.description)
            .put("techTag", blueprint.techTag)
            .put("usageCount", blueprint.usageCount)
            .put("draft", blueprint.draft)
        val slotsArr = JSONArray()
        blueprint.slots.forEach { s ->
            slotsArr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("type", s.type)
                    .put("example", s.example)
                    .put("required", s.required),
            )
        }
        o.put("slots", slotsArr)
        val tplArr = JSONArray()
        blueprint.fileTemplates.forEach { t ->
            val to = JSONObject()
                .put("pathTemplate", t.pathTemplate)
                .put("action", t.action.name)
                .put("contentTemplate", t.contentTemplate)
            if (t.anchor != null) to.put("anchor", t.anchor)
            tplArr.put(to)
        }
        o.put("fileTemplates", tplArr)
        val hintsArr = JSONArray()
        blueprint.verificationHints.forEach { hintsArr.put(it) }
        o.put("verificationHints", hintsArr)
        return o.toString(2)
    }

    fun catalogSummary(repoBlueprints: List<Blueprint>, localBlueprints: List<Blueprint>): String {
        val all = (repoBlueprints.map { it to "repo" } + localBlueprints.map { it to "local" })
            .distinctBy { it.first.name }
        if (all.isEmpty()) return "Blueprint yok."
        return all.joinToString("\n") { (bp, src) ->
            val slotNames = bp.slots.joinToString(", ") { if (it.required) it.name else "${it.name}?" }
            val tag = if (bp.techTag.isNotBlank()) " [${bp.techTag}]" else ""
            val draftTag = if (bp.draft) " [taslak]" else ""
            "- ${bp.name}$tag$draftTag ($src, slots: $slotNames): ${bp.description}"
        }
    }

    fun apply(
        blueprint: Blueprint,
        params: Map<String, String>,
        currentFiles: Map<String, String?>,
        tx: StagedTransaction,
    ): BlueprintApplyResult {
        for (slot in blueprint.slots) {
            if (slot.required && params[slot.name].isNullOrBlank()) {
                return BlueprintApplyResult(
                    ok = false,
                    blueprint = blueprint,
                    error = "Eksik zorunlu slot: ${slot.name}",
                )
            }
        }

        val prepared = ArrayList<Triple<String, String?, String>>()
        for (tpl in blueprint.fileTemplates) {
            val path = substitute(tpl.pathTemplate, params)
            val content = substitute(tpl.contentTemplate, params)
            val anchor = tpl.anchor?.let { substitute(it, params) }

            val unresolved = findUnresolved(path) ?: findUnresolved(content) ?: anchor?.let { findUnresolved(it) }
            if (unresolved != null) {
                return BlueprintApplyResult(
                    ok = false,
                    blueprint = blueprint,
                    error = "Çözülmemiş slot yer tutucusu: {{$unresolved}}",
                )
            }

            val before = currentFiles[path]
            val after = when (tpl.action) {
                BlueprintTemplateAction.CREATE -> content
                BlueprintTemplateAction.PATCH_ANCHOR -> {
                    if (before == null) {
                        return BlueprintApplyResult(
                            ok = false,
                            blueprint = blueprint,
                            error = "PATCH_ANCHOR için hedef dosya bulunamadı: $path",
                        )
                    }
                    if (anchor != null) {
                        val idx = before.indexOf(anchor)
                        if (idx < 0) {
                            return BlueprintApplyResult(
                                ok = false,
                                blueprint = blueprint,
                                error = "Anchor bulunamadı ($path): $anchor",
                            )
                        }
                        val insertAt = idx + anchor.length
                        before.substring(0, insertAt) + "\n" + content + before.substring(insertAt)
                    } else {
                        if (before.endsWith("\n")) before + content else "$before\n$content"
                    }
                }
            }

            val checkError = PreFlightValidator.check(path, before, after)
            if (checkError != null) {
                return BlueprintApplyResult(
                    ok = false,
                    blueprint = blueprint,
                    error = "PreFlightValidator hatası ($path): $checkError",
                )
            }

            prepared.add(Triple(path, before, after))
        }

        prepared.forEach { (path, before, after) ->
            tx.stage(path, before, after)
        }

        return BlueprintApplyResult(
            ok = true,
            blueprint = blueprint.copy(usageCount = blueprint.usageCount + 1),
            stagedPaths = prepared.map { it.first },
        )
    }

    fun distillCandidate(
        repo: String,
        taskTitle: String,
        changedFiles: Map<String, String>,
        firstPassGreen: Boolean,
    ): Blueprint? {
        if (!firstPassGreen || changedFiles.size < 3) return null
        val slug = taskTitle.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "blueprint-draft" }
            .take(48)
        val templates = changedFiles.entries.map { (path, content) ->
            BlueprintFileTemplate(
                pathTemplate = path,
                action = BlueprintTemplateAction.CREATE,
                contentTemplate = content,
            )
        }
        return Blueprint(
            name = slug,
            description = taskTitle.trim().ifBlank { "Otomatik damıtılan taslak" },
            techTag = repo.substringAfterLast('/'),
            slots = emptyList(),
            fileTemplates = templates,
            verificationHints = listOf("İlk turda CI yeşil geçti ($repo)"),
            usageCount = 0,
            draft = true,
        )
    }

    private fun substitute(template: String, params: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m ->
            val key = m.groupValues[1].trim()
            params[key] ?: m.value
        }

    private fun findUnresolved(text: String): String? =
        PLACEHOLDER.find(text)?.groupValues?.getOrNull(1)?.trim()
}

class BlueprintStore(private val dir: File) {
    private fun fileFor(name: String): File {
        val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(dir, "$safe.json")
    }

    fun list(): List<Blueprint> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { f -> runCatching { BlueprintEngine.fromJson(f.readText()) }.getOrNull() }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun get(name: String): Blueprint? {
        val f = fileFor(name)
        if (!f.exists()) return null
        return runCatching { BlueprintEngine.fromJson(f.readText()) }.getOrNull()
    }

    fun saveUserBlueprint(blueprint: Blueprint) {
        if (!dir.exists()) dir.mkdirs()
        fileFor(blueprint.name).writeText(BlueprintEngine.toJson(blueprint))
    }

    fun approveDraft(name: String): Blueprint? {
        val existing = get(name) ?: return null
        val approved = existing.copy(draft = false)
        saveUserBlueprint(approved)
        return approved
    }

    fun delete(name: String): Boolean = fileFor(name).delete()
}
