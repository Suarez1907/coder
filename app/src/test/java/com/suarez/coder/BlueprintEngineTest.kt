package com.suarez.coder

import com.suarez.coder.harness.StagedTransaction
import com.suarez.coder.skills.Blueprint
import com.suarez.coder.skills.BlueprintEngine
import com.suarez.coder.skills.BlueprintFileTemplate
import com.suarez.coder.skills.BlueprintSlot
import com.suarez.coder.skills.BlueprintTemplateAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueprintEngineTest {
    @Test
    fun `apply fills slots deterministically for CREATE and PATCH_ANCHOR`() {
        val bp = Blueprint(
            name = "feature-module",
            description = "Yeni özellik modülü ekler",
            techTag = "kotlin",
            slots = listOf(
                BlueprintSlot("Name", "string", "Profile", required = true),
                BlueprintSlot("pkg", "string", "com.suarez.coder", required = true),
            ),
            fileTemplates = listOf(
                BlueprintFileTemplate(
                    pathTemplate = "src/{{Name}}.kt",
                    action = BlueprintTemplateAction.CREATE,
                    contentTemplate = "package {{pkg}}\nclass {{Name}} {\n    fun id(): String = \"{{Name}}\"\n}\n",
                ),
                BlueprintFileTemplate(
                    pathTemplate = "src/Registry.kt",
                    action = BlueprintTemplateAction.PATCH_ANCHOR,
                    contentTemplate = "    register(\"{{Name}}\")",
                    anchor = "// ANCHOR:REGISTER",
                ),
            ),
        )

        val tx = StagedTransaction()
        val currentFiles = mapOf(
            "src/Registry.kt" to "object Registry {\n    // ANCHOR:REGISTER\n}\n",
        )
        val res = BlueprintEngine.apply(bp, mapOf("Name" to "Profile", "pkg" to "com.suarez.coder"), currentFiles, tx)

        assertTrue(res.ok)
        assertEquals(1, res.blueprint.usageCount)
        assertEquals(listOf("src/Profile.kt", "src/Registry.kt"), res.stagedPaths)

        val staged = tx.staged().associate { it.first to it.third }
        assertTrue(staged["src/Profile.kt"]!!.contains("class Profile"))
        assertTrue(staged["src/Registry.kt"]!!.contains("// ANCHOR:REGISTER\n    register(\"Profile\")"))
    }

    @Test
    fun `apply fails on missing required slot without polluting StagedTransaction`() {
        val bp = Blueprint(
            name = "missing-slot-bp",
            description = "Eksik slot testi",
            slots = listOf(BlueprintSlot("Name", required = true)),
            fileTemplates = listOf(
                BlueprintFileTemplate("src/{{Name}}.kt", BlueprintTemplateAction.CREATE, "class {{Name}}"),
            ),
        )

        val tx = StagedTransaction()
        val res = BlueprintEngine.apply(bp, emptyMap(), emptyMap(), tx)

        assertFalse(res.ok)
        assertNotNull(res.error)
        assertTrue(tx.staged().isEmpty())
    }

    @Test
    fun `apply rejects atomically when PreFlightValidator detects unbalanced braces`() {
        val bp = Blueprint(
            name = "broken-code-bp",
            description = "Bozuk kod testi",
            slots = listOf(BlueprintSlot("Name", required = true)),
            fileTemplates = listOf(
                BlueprintFileTemplate("src/Valid.kt", BlueprintTemplateAction.CREATE, "class Valid {}"),
                BlueprintFileTemplate("src/{{Name}}.kt", BlueprintTemplateAction.CREATE, "class {{Name}} {\n    fun broken() {\n"),
            ),
        )

        val tx = StagedTransaction()
        val res = BlueprintEngine.apply(bp, mapOf("Name" to "Broken"), emptyMap(), tx)

        assertFalse(res.ok)
        assertNotNull(res.error)
        assertTrue(tx.staged().isEmpty())
    }

    @Test
    fun `distillCandidate creates draft from 3+ files on firstPassGreen and roundtrips JSON`() {
        val skipped = BlueprintEngine.distillCandidate(
            repo = "suarez/coder",
            taskTitle = "Small fix",
            changedFiles = mapOf("A.kt" to "class A", "B.kt" to "class B"),
            firstPassGreen = true,
        )
        assertNull(skipped)

        val draft = BlueprintEngine.distillCandidate(
            repo = "suarez/coder",
            taskTitle = "Add Settings Screen",
            changedFiles = mapOf(
                "Settings.kt" to "class Settings",
                "SettingsViewModel.kt" to "class SettingsViewModel",
                "SettingsTest.kt" to "class SettingsTest",
            ),
            firstPassGreen = true,
        )
        assertNotNull(draft)
        assertTrue(draft!!.draft)
        assertEquals(3, draft.fileTemplates.size)

        val json = BlueprintEngine.toJson(draft)
        val parsed = BlueprintEngine.fromJson(json)
        assertEquals(draft.name, parsed.name)
        assertEquals(draft.draft, parsed.draft)
        assertEquals(3, parsed.fileTemplates.size)
    }
}
