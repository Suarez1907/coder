package com.suarez.coder

import com.suarez.coder.cloudtools.CloudWorkflowTemplates
import com.suarez.coder.docs.DocsConverter
import com.suarez.coder.docs.DocumentReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Document attachments (read on the phone) and the video/docs/manim cloud tools. */
class DocsAndVideoTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { b ->
        ZipOutputStream(b).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun docx() {
        val xml = """<w:document><w:body><w:p><w:pPr/><w:r><w:t>Başlık</w:t></w:r></w:p>""" +
            """<w:p><w:r><w:t xml:space="preserve">A &amp; B </w:t></w:r><w:r><w:tab/><w:t>son</w:t></w:r></w:p></w:body></w:document>"""
        assertEquals("Başlık\nA & B \tson", DocumentReader.extract("rapor.docx", zip("word/document.xml" to xml)))
    }

    @Test fun pptxSlidesInOrder() {
        val s = { t: String -> "<p:sld><a:p><a:r><a:t>$t</a:t></a:r></a:p></p:sld>" }
        val text = DocumentReader.extract("sunum.pptx", zip("ppt/slides/slide10.xml" to s("on"), "ppt/slides/slide2.xml" to s("iki"), "ppt/slides/slide1.xml" to s("bir")))!!
        assertEquals("## Slayt 1\nbir\n\n## Slayt 2\niki\n\n## Slayt 3\non", text)
    }

    @Test fun xlsxSharedAndInlineStrings() {
        val shared = "<sst><si><t>Ad</t></si><si><t>Fiyat</t></si><si><r><t>El</t></r><r><t>ma</t></r></si></sst>"
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>""" +
            """<row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>12.5</v></c><c r="C2" s="1"/></row>""" +
            """<row r="3"><c r="A3" t="inlineStr"><is><t>Armut</t></is></c></row></sheetData></worksheet>"""
        assertEquals("## Sayfa 1\nAd\tFiyat\nElma\t12.5\nArmut",
            DocumentReader.extract("liste.xlsx", zip("xl/sharedStrings.xml" to shared, "xl/worksheets/sheet1.xml" to sheet)))
    }

    @Test fun odtAndText() {
        val xml = "<office:text><text:h>Başlık</text:h><text:p>bir<text:tab/>iki</text:p><text:p/></office:text>"
        assertEquals("Başlık\nbir\tiki", DocumentReader.extract("not.odt", zip("content.xml" to xml)))
        assertEquals("merhaba", DocumentReader.extract("a.md", "merhaba\n".toByteArray()))
        assertEquals("düz", DocumentReader.extract("README", "düz".toByteArray()))
    }

    @Test fun pdfAndBinaryGoToCloud() {
        assertNull(DocumentReader.extract("a.pdf", "%PDF-1.4\n...".toByteArray()))
        assertNull(DocumentReader.extract("x.bin", byteArrayOf(1, 0, 2, 3)))
        assertNull(DocumentReader.extract("bos.txt", ByteArray(0)))
    }

    @Test fun blockCollapsesInBubble() {
        val msg = "Özetle\n\n" + DocumentReader.block("rapor \"son\".docx", "içerik")
        assertEquals("Özetle\n\n[Belge: rapor 'son'.docx, 6 karakter]", DocumentReader.collapse(msg))
        val long = DocumentReader.block("b.txt", "x".repeat(DocumentReader.MAX_CHARS + 10))
        assertTrue(long.contains("kesildi"))
        assertEquals("../../etc/passwd".let { DocsConverter.safeName(it) }, "etc_passwd")
        assertEquals("Rapor_2024.pdf", DocsConverter.safeName("Rapor 2024.pdf"))
    }

    @Test fun videoDocsManimTools() {
        assertEquals(CloudWorkflowTemplates.VIDEO, CloudWorkflowTemplates.forTool("remotion"))
        assertEquals(CloudWorkflowTemplates.DOCS, CloudWorkflowTemplates.forTool("markitdown"))
        assertEquals(CloudWorkflowTemplates.TOOLBOX, CloudWorkflowTemplates.forTool("manim"))
        // Every extra input the runner may pass is declared by the workflow (an undeclared one fails the dispatch).
        for (t in CloudWorkflowTemplates.ALL) {
            @Suppress("UNCHECKED_CAST")
            val doc = Yaml().load<Map<Any, Any>>(t.yaml)
            @Suppress("UNCHECKED_CAST")
            val inputs = (((doc["on"] ?: doc[true]) as Map<String, Any>)["workflow_dispatch"] as Map<String, Any>)["inputs"] as Map<String, Any>
            assertTrue(t.id, inputs.keys.containsAll(CloudWorkflowTemplates.extraInputNames(t)))
        }
        val v = CloudWorkflowTemplates.VIDEO.yaml
        assertTrue(v.contains("npx remotion render") && v.contains("remotion browser ensure") && v.contains("preview.png"))
        assertTrue(CloudWorkflowTemplates.DOCS.yaml.contains("markitdown[all]"))
        assertTrue(CloudWorkflowTemplates.TOOLBOX.yaml.contains("pip install -q manim"))
    }
}
