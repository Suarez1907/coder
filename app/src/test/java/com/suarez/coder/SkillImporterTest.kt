package com.suarez.coder

import com.suarez.coder.skills.Skill
import com.suarez.coder.skills.SkillImporter
import com.suarez.coder.skills.SkillParser
import com.suarez.coder.skills.SkillRepository
import com.suarez.coder.tools.GitHub
import com.suarez.coder.tools.GitHub.TreeEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    // A repository shaped like the public Agent Skills collections: skills/<name>/SKILL.md with references and scripts.
    private val files = mapOf(
        "README.md" to "# Skills",
        "skills/pdf/SKILL.md" to "---\nname: pdf\ndescription: PDF okuma ve oluşturma\n---\n# PDF\nreferences/forms.md dosyasına bak.",
        "skills/pdf/references/forms.md" to "Form alanları ...",
        "skills/pdf/scripts/fill_form.py" to "print('fill')",
        "skills/pdf/assets/logo.png" to "binary",
        "skills/pdf/inner/SKILL.md" to "---\nname: pdf-inner\ndescription: iç içe\n---\nB",
        "skills/pdf/inner/notes.md" to "iç dosya",
        "skills/brand-guidelines/SKILL.md" to "# Marka kuralları\nRenkler...",
        "node_modules/x/SKILL.md" to "---\nname: vendored\n---\nx",
    )
    private val entries = files.map { (p, c) -> TreeEntry(p, if (p.endsWith(".png")) 5_000 else c.length.toLong()) }
    private var reads = 0
    private val importer = SkillImporter(
        tree = { _, ref -> (ref ?: "main") to entries },
        read = { _, p, _ -> reads++; files[p] },
    )

    @Test fun parsesGitHubLinks() {
        assertEquals(SkillImporter.Ref("anthropics/skills", null, null), SkillImporter.parseRef("anthropics/skills"))
        assertEquals(SkillImporter.Ref("anthropics/skills", null, null), SkillImporter.parseRef("https://github.com/anthropics/skills.git"))
        assertEquals(SkillImporter.Ref("a/b", "main", "skills/pdf"), SkillImporter.parseRef("https://github.com/a/b/tree/main/skills/pdf"))
        assertEquals(SkillImporter.Ref("a/b", "v2", "skills/pdf/SKILL.md"), SkillImporter.parseRef("github.com/a/b/blob/v2/skills/pdf/SKILL.md?plain=1"))
        assertNull(SkillImporter.parseRef("not a link"))
        assertNull(SkillImporter.parseRef("https://github.com/onlyowner"))
    }

    @Test fun findsSkillsAndTheirFilesButNotVendoredOrNestedOnes() = runBlocking {
        val found = importer.scan("a/b")
        assertEquals(listOf("brand-guidelines", "pdf", "pdf-inner"), found.map { it.name }.sorted())
        val pdf = found.first { it.name == "pdf" }
        assertEquals("PDF okuma ve oluşturma", pdf.description)
        // Text files only (no .png), and not the nested skill's files.
        assertEquals(listOf("skills/pdf/references/forms.md", "skills/pdf/scripts/fill_form.py"), pdf.files.map { it.path })
        assertTrue(pdf.hasScripts)
        val brand = found.first { it.name == "brand-guidelines" } // no front matter: name from the folder
        assertEquals("Marka kuralları", brand.description)
        assertFalse(brand.hasScripts)
        // A link to one folder lists only that skill (and skills below it).
        assertEquals(listOf("pdf", "pdf-inner"), importer.scan("a/b", "main", "skills/pdf").map { it.name })
        assertEquals(listOf("pdf"), importer.scan("a/b", "main", "skills/pdf/SKILL.md").map { it.name })
    }

    @Test fun importKeepsOriginAndFilesAndLoadSkillServesThem() = runBlocking {
        val pdf = importer.scan("a/b").first { it.name == "pdf" }
        val s = importer.import(pdf)!!
        assertEquals("a/b@main:skills/pdf/SKILL.md", s.origin)
        assertEquals(setOf("references/forms.md", "scripts/fill_form.py"), s.resources.keys)
        assertTrue(s.hasScripts)

        val repo = SkillRepository(tmp.newFolder(), { null }, { _, _ -> null })
        repo.saveGlobal(s)
        val stored = repo.global("pdf")!!
        assertEquals(s.origin, stored.origin)
        assertEquals(s.resources, stored.resources)
        val text = repo.load("pdf", "")
        assertTrue(text.contains("BUNDLED FILES"))
        assertTrue(text.contains("references/forms.md"))
        assertTrue(text.contains("run_cloud_tool(tool=\"toolbox\""))
        assertFalse(text.contains("Form alanları")) // file contents only on request
        assertTrue(repo.load("pdf", "", file = "references/forms.md").contains("Form alanları"))
        assertTrue(repo.load("pdf", "", file = "yok.md").startsWith("Dosya yok"))
        // Still one catalog line.
        assertEquals(1, repo.catalog("")!!.lines().count { it.startsWith("- pdf:") })
    }

    @Test fun updateReimportsFromTheOrigin() = runBlocking {
        val s = importer.update("a/b@old-tag:skills/brand-guidelines/SKILL.md")!!
        assertEquals("brand-guidelines", s.name)
        assertEquals("a/b@main:skills/brand-guidelines/SKILL.md", s.origin)
        assertNull(importer.update("a/b@main:skills/missing/SKILL.md"))
    }

    @Test fun fileLimitsAreEnforced() {
        val many = (1..40).map { TreeEntry("s/x/ref$it.md", 20_000) } + TreeEntry("s/x/SKILL.md", 100) + TreeEntry("s/x/huge.md", 500_000)
        val res = SkillImporter.resourcesFor("s/x/SKILL.md", many)
        assertTrue(res.size <= SkillImporter.MAX_FILES)
        assertTrue(res.sumOf { it.size } <= SkillImporter.MAX_TOTAL_BYTES)
        assertTrue(res.none { it.path.endsWith("huge.md") })
        assertTrue(SkillImporter.resourcesFor("SKILL.md", many).isEmpty()) // a root SKILL.md brings no repo files
    }

    @Test fun starredJson() {
        val list = GitHub.Starred.parse("""[{"full_name":"anthropics/skills","description":"Agent Skills","topics":["claude","skills"],"stargazers_count":5},
            {"full_name":"x/y","description":null,"stargazers_count":1}]""")
        assertEquals(listOf("anthropics/skills", "x/y"), list.map { it.fullName })
        assertEquals("", list[1].description)
        assertEquals(listOf("claude", "skills"), list[0].topics)
        assertTrue(GitHub.Starred.parse("bozuk").isEmpty())
        assertNotNull(SkillParser.parse(Skill("a", "d", body = "b").toMarkdown()))
    }
}
