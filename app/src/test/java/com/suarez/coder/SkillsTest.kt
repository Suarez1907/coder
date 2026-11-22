package com.suarez.coder

import com.suarez.coder.cloudtools.CloudToolRunner
import com.suarez.coder.cloudtools.CloudWorkflowTemplates
import com.suarez.coder.cloudtools.CloudWorkflowTemplates.InstallState
import com.suarez.coder.skills.BuiltinSkills
import com.suarez.coder.skills.Skill
import com.suarez.coder.skills.SkillParser
import com.suarez.coder.skills.SkillRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillParserTest {
    @Test fun frontMatterAndBody() {
        val s = SkillParser.parse("""
            ---
            name: Godot Scene
            description: "Godot 4 sahnesi kurar"
            triggers:
              - godot
              - 'tscn'
            workflow: coder-toolbox.yml
            unknown: x
            ---

            # Talimat
            1. Sahneyi aç
        """.trimIndent())!!
        assertEquals("godot-scene", s.name)
        assertEquals("Godot 4 sahnesi kurar", s.description)
        assertEquals(listOf("godot", "tscn"), s.triggers)
        assertEquals("toolbox", s.workflow)
        assertTrue(s.body.startsWith("# Talimat"))
        assertFalse(s.body.contains("description:"))
    }

    @Test fun inlineListsCrlfAndRoundTrip() {
        val s = SkillParser.parse("---\r\nname: a-b\r\ndescription: d\r\ntriggers: [x, \"y\"]\r\n---\r\nGövde\r\n")!!
        assertEquals(listOf("x", "y"), s.triggers)
        assertEquals("Gövde", s.body)
        assertEquals(s, SkillParser.parse(s.toMarkdown()))
    }

    @Test fun brokenOrMissingYamlIsTolerated() {
        // No front matter: name from the folder, description from the first line.
        val a = SkillParser.parse("# Blender ipuçları\nDetay", fallbackName = "Blender Tips")!!
        assertEquals("blender-tips", a.name)
        assertEquals("Blender ipuçları", a.description)
        // Unterminated front matter: the whole text is the body.
        val b = SkillParser.parse("---\nname: x\nGövde", fallbackName = "fallback")!!
        assertEquals("fallback", b.name)
        // Garbage lines inside the front matter are skipped.
        val c = SkillParser.parse("---\n: :\nname: ok-name\n  - stray\ndescription: d\n---\nB")!!
        assertEquals("ok-name", c.name)
        // No usable name at all.
        assertNull(SkillParser.parse("---\nname: ###\n---\nB"))
        assertNull(SkillParser.parse("", fallbackName = ""))
    }

    @Test fun builtinBlenderSkillParsesWithTheRequiredRules() {
        val s = SkillParser.parse(BuiltinSkills.BLENDER_3D, source = Skill.Source.BUILTIN)!!
        assertEquals("blender-3d", s.name)
        assertEquals("blender", s.workflow)
        for (rule in listOf("read_factory_settings(use_empty=True)", "--background", "view3d", "preview.png", "800x800",
            "export_scene.gltf", "save_as_mainfile", "origin_set", "METRIC")) assertTrue(rule, s.body.contains(rule))
    }
}

class SkillCatalogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(paths: List<String> = emptyList(), files: Map<String, String> = emptyMap()) =
        SkillRepository(tmp.newFolder(), { paths }, { _, p -> files[p] })

    @Test fun catalogHasOnlyOneLinePerSkillAndTheBodyComesFromLoad() {
        val r = repo()
        val catalog = r.catalog("me/app")!!
        assertTrue(catalog.contains("- blender-3d: Headless Blender"))
        assertFalse(catalog.contains("read_factory_settings")) // body never in the prompt
        assertEquals(2, catalog.lines().size) // header + one skill
        val loaded = r.load("blender-3d", "me/app")
        assertTrue(loaded.contains("read_factory_settings(use_empty=True)"))
        assertTrue(loaded.contains("run_cloud_tool(tool=\"blender\""))
        assertTrue(r.load("/blender-3d", "me/app").contains("SKILL blender-3d"))
        assertTrue(r.load("yok", "me/app").startsWith("Yetenek bulunamadı"))
    }

    @Test fun repoSkillsOverrideGlobalsAndDisabledOnesLeaveTheCatalog() = runBlocking {
        val r = repo(
            paths = listOf("README.md", ".coder/skills/blender-3d/SKILL.md", ".coder/skills/Godot/SKILL.md", ".coder/skills/x/notes.md"),
            files = mapOf(
                ".coder/skills/blender-3d/SKILL.md" to "---\nname: blender-3d\ndescription: Bu repoya özel Blender kuralları\n---\nÖZEL GÖVDE",
                ".coder/skills/Godot/SKILL.md" to "# Godot sahneleri\nKurallar",
            ),
        )
        val found = r.refreshRepo("me/app")
        assertEquals(setOf("blender-3d", "godot"), found.map { it.name }.toSet())
        val all = r.all("me/app")
        assertEquals(Skill.Source.REPO, all.first { it.name == "blender-3d" }.source)
        assertTrue(r.load("blender-3d", "me/app").contains("ÖZEL GÖVDE"))
        assertTrue(r.catalog("me/app")!!.contains("Bu repoya özel"))
        // Another repo still sees the global one.
        assertTrue(r.load("blender-3d", "me/other").contains("read_factory_settings"))

        r.setEnabled("godot", false)
        assertFalse(r.catalog("me/app")!!.contains("godot"))
        assertTrue(r.load("godot", "me/app").startsWith("Yetenek kapalı"))
        r.setEnabled("godot", true)
        assertTrue(r.catalog("me/app")!!.contains("- godot: Godot sahneleri"))
    }

    @Test fun globalsPersistAndBuiltinsAreSeededOnce() {
        val dir = tmp.newFolder()
        val r1 = SkillRepository(dir, { null }, { _, _ -> null })
        r1.deleteGlobal("blender-3d")
        r1.saveGlobal(SkillParser.parse("---\nname: ffmpeg-gif\ndescription: Videodan GIF\n---\nffmpeg -i ...")!!)
        val r2 = SkillRepository(dir, { null }, { _, _ -> null })
        assertEquals(listOf("ffmpeg-gif"), r2.globals().map { it.name }) // deleted builtin is not re-seeded
        assertNull(SkillRepository.catalogText(emptyList()))
    }
}

class CloudWorkflowTemplatesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Suppress("UNCHECKED_CAST")
    private fun parse(t: CloudWorkflowTemplates.CloudTool) = Yaml().load<Map<Any, Any>>(t.yaml)

    @Test fun templatesAreValidYamlWithDispatchInputsAndArtifacts() {
        for (t in CloudWorkflowTemplates.ALL) {
            val doc = parse(t)
            // YAML 1.1 reads the bare key `on` as boolean true.
            @Suppress("UNCHECKED_CAST")
            val on = (doc["on"] ?: doc[true]) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val inputs = (on["workflow_dispatch"] as Map<String, Any>)["inputs"] as Map<String, Any>
            assertTrue(t.id, "script_path" in inputs && "output_dir" in inputs)
            assertTrue(t.yaml.contains("name: ${t.artifact}"))
            assertTrue(t.yaml.contains("actions/upload-artifact@v4"))
            assertFalse("no tabs", t.yaml.contains('\t'))
            assertFalse("no Kotlin placeholder left", t.yaml.contains('§'))
            // Inputs reach shell commands only through env variables (no injection via script_path).
            val runLines = t.yaml.lines().dropWhile { !it.contains("steps:") }.filter { !it.trimStart().startsWith("key:") }
            assertTrue(t.id, runLines.none { it.contains("\${{ inputs.") })
        }
        val blender = CloudWorkflowTemplates.BLENDER.yaml
        assertTrue(blender.contains("default: \"4.2\""))
        assertTrue(blender.contains("actions/cache@v4"))
        assertTrue(blender.contains("--background"))
        assertTrue(blender.contains("--python-exit-code 1"))
        assertTrue(blender.contains("Traceback (most recent call last)"))
        assertTrue(blender.contains("coder-blender-output"))
        assertTrue(blender.contains("*.glb") && blender.contains("*.blend") && blender.contains("preview.png"))
    }

    @Test fun toolArgumentMapping() {
        assertEquals(CloudWorkflowTemplates.BLENDER, CloudWorkflowTemplates.forTool("blender"))
        assertEquals(CloudWorkflowTemplates.BLENDER, CloudWorkflowTemplates.forTool(" Coder-Blender.yml"))
        assertEquals(CloudWorkflowTemplates.TOOLBOX, CloudWorkflowTemplates.forTool("toolbox"))
        assertEquals(CloudWorkflowTemplates.TOOLBOX, CloudWorkflowTemplates.forTool("ffmpeg"))
        assertNull(CloudWorkflowTemplates.forTool("maya"))
        assertEquals(".github/workflows/coder-blender.yml", CloudWorkflowTemplates.BLENDER.path)
    }

    @Test fun installState() {
        val t = CloudWorkflowTemplates.BLENDER
        assertEquals(InstallState.MISSING, CloudWorkflowTemplates.state(t, null))
        assertEquals(InstallState.CURRENT, CloudWorkflowTemplates.state(t, t.yaml))
        assertEquals(InstallState.OUTDATED, CloudWorkflowTemplates.state(t, "# coder-cloud-tool: blender v0\nname: eski"))
        assertEquals(InstallState.CUSTOM, CloudWorkflowTemplates.state(t, "name: elle yazılmış"))
    }

    @Test fun scriptPathsStayInsideTheRepo() {
        assertEquals("tools/model.py", CloudToolRunner.cleanPath("./tools/model.py"))
        assertNull(CloudToolRunner.cleanPath("../secret.py"))
        assertNull(CloudToolRunner.cleanPath("a/../../b.py"))
        assertNull(CloudToolRunner.cleanPath("~/x.py"))
        assertNull(CloudToolRunner.cleanPath(" "))
    }

    @Test fun artifactUnzipBlocksZipSlipAndTheMarkerFindsOutputs() {
        val zip = File(tmp.root, "a.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for ((name, data) in listOf("preview.png" to "png", "model.glb" to "glb", "../evil.txt" to "x", "sub/model.blend" to "b")) {
                z.putNextEntry(ZipEntry(name)); z.write(data.toByteArray()); z.closeEntry()
            }
        }
        val dir = File(tmp.root, "out")
        val files = CloudToolRunner.unzip(zip, dir)
        assertEquals(setOf("preview.png", "model.glb", "model.blend"), files.map { it.name }.toSet())
        assertFalse(File(tmp.root, "evil.txt").exists())

        val text = "Çıktılar indirildi.\n" + CloudToolRunner.marker("blender", 42, dir)
        val out = CloudToolRunner.outputs(text).single()
        assertEquals(42L, out.runId)
        assertEquals("preview.png", out.preview!!.name)
        assertEquals(setOf("model.glb", "model.blend"), out.assets.map { it.name }.toSet())
        assertNotNull(CloudToolRunner.outputs("yok").isEmpty())
    }
}
