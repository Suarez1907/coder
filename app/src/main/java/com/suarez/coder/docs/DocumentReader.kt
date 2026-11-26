package com.suarez.coder.docs

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Turns a document attached to the chat into text the model can read. Plain text and Office/OpenDocument files are
 * read on the phone; anything else (PDF, old .doc/.xls, epub, ...) returns null and goes to the markitdown cloud tool.
 */
object DocumentReader {
    const val MAX_CHARS = 60_000
    const val MAX_BYTES = 15L * 1024 * 1024
    const val MAX_DOCS = 3

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "log", "html", "htm", "svg",
        "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "gradle", "properties", "sh", "c", "h", "cpp", "rs",
        "go", "swift", "dart", "gd", "cs", "sql", "css", "scss", "rb", "php", "lua",
    )

    /** Formats markitdown handles better than a text guess (a PDF can look like ASCII). */
    private val CLOUD_EXT = setOf("pdf", "doc", "xls", "ppt", "rtf", "epub", "msg", "zip", "jpg", "jpeg", "png", "mp3", "wav")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    /** Text of the document, or null when it needs the cloud converter. */
    fun extract(name: String, bytes: ByteArray): String? = when (ext(name)) {
        in TEXT_EXT -> String(bytes, Charsets.UTF_8)
        "docx" -> zipEntries(bytes) { it == "word/document.xml" }.values.firstOrNull()?.let { docx(it) }
        "pptx" -> zipEntries(bytes) { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }.let { pptx(it) }
        "xlsx" -> zipEntries(bytes) { it == "xl/sharedStrings.xml" || Regex("xl/worksheets/sheet\\d+\\.xml").matches(it) }.let { xlsx(it) }
        "odt", "odp", "ods" -> zipEntries(bytes) { it == "content.xml" }.values.firstOrNull()?.let { odf(it) }
        in CLOUD_EXT -> null
        else -> if (looksLikeText(bytes)) String(bytes, Charsets.UTF_8) else null
    }?.trim()?.takeIf { it.isNotEmpty() }

    /** The block added to the user's message; the chat bubble shows only its header. */
    fun block(name: String, text: String): String {
        val cut = text.length > MAX_CHARS
        val body = if (cut) text.take(MAX_CHARS) + "\n[... belgenin kalanı kesildi: toplam ${text.length} karakter]" else text
        return "<belge ad=\"${name.replace("\"", "'")}\">\n$body\n</belge>"
    }

    private val BLOCK = Regex("<belge ad=\"([^\"]*)\">\\n([\\s\\S]*?)\\n</belge>")

    /** For display: document blocks collapse to a one-line label. */
    fun collapse(text: String): String = BLOCK.replace(text) { m -> "[Belge: ${m.groupValues[1]}, ${m.groupValues[2].length} karakter]" }

    fun looksLikeText(bytes: ByteArray): Boolean {
        val head = bytes.take(4096)
        if (head.isEmpty()) return false
        if (head.any { it == 0.toByte() }) return false
        return runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, 0, minOf(bytes.size, 4096))) }.isSuccess
    }

    private fun zipEntries(bytes: ByteArray, want: (String) -> Boolean): Map<String, String> {
        val out = sortedMapOf<String, String>(compareBy({ it.length }, { it }))
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !want(e.name)) continue
                val data = z.readBytes()
                total += data.size
                if (total > 80L * 1024 * 1024) break // zip bomb guard
                out[e.name] = String(data, Charsets.UTF_8)
            }
        }
        return out
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Paragraphs (<w:p>) of text runs (<w:t>), with tabs and line breaks. */
    fun docx(xml: String): String = Regex("<w:p[ >][\\s\\S]*?</w:p>|<w:p/>").findAll(xml).joinToString("\n") { p ->
        Regex("<w:t(?: [^>]*)?>([^<]*)</w:t>|<w:tab/>|<w:br/>").findAll(p.value).joinToString("") { m ->
            when { m.value == "<w:tab/>" -> "\t"; m.value == "<w:br/>" -> "\n"; else -> unescape(m.groupValues[1]) }
        }
    }.replace(Regex("\n{3,}"), "\n\n")

    fun pptx(slides: Map<String, String>): String = slides.entries.sortedBy { Regex("\\d+").find(it.key.substringAfterLast('/'))?.value?.toInt() ?: 0 }
        .mapIndexed { i, (_, xml) ->
            val paras = Regex("<a:p>[\\s\\S]*?</a:p>").findAll(xml).map { p ->
                Regex("<a:t>([^<]*)</a:t>").findAll(p.value).joinToString("") { unescape(it.groupValues[1]) }
            }.filter { it.isNotBlank() }
            "## Slayt ${i + 1}\n" + paras.joinToString("\n")
        }.joinToString("\n\n")

    /** Sheets as tab-separated rows; shared strings resolved. */
    fun xlsx(parts: Map<String, String>): String {
        val shared = parts["xl/sharedStrings.xml"]?.let { x ->
            Regex("<si>([\\s\\S]*?)</si>").findAll(x).map { si -> Regex("<t(?: [^>]*)?>([^<]*)</t>").findAll(si.value).joinToString("") { unescape(it.groupValues[1]) } }.toList()
        }.orEmpty()
        return parts.filterKeys { it.startsWith("xl/worksheets/") }.entries.sortedBy { Regex("\\d+").find(it.key.substringAfterLast('/'))?.value?.toInt() ?: 0 }
            .mapIndexed { i, (_, xml) ->
                val rows = Regex("<row[^>]*>([\\s\\S]*?)</row>").findAll(xml).map { r ->
                    Regex("<c ([^>]*?)(?:/>|>([\\s\\S]*?)</c>)").findAll(r.groupValues[1]).joinToString("\t") { c ->
                        val attrs = c.groupValues[1]; val inner = c.groupValues[2]
                        val v = Regex("<v>([^<]*)</v>").find(inner)?.groupValues?.get(1)
                        when {
                            attrs.contains("t=\"s\"") -> v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            attrs.contains("t=\"inlineStr\"") -> Regex("<t(?: [^>]*)?>([^<]*)</t>").findAll(inner).joinToString("") { unescape(it.groupValues[1]) }
                            else -> v?.let { unescape(it) } ?: ""
                        }
                    }.trimEnd('\t')
                }.filter { it.isNotBlank() }
                "## Sayfa ${i + 1}\n" + rows.joinToString("\n")
            }.joinToString("\n\n")
    }

    fun odf(xml: String): String = Regex("<text:(?:p|h)[^>]*?(?:/>|>([\\s\\S]*?)</text:(?:p|h)>)").findAll(xml).joinToString("\n") { m ->
        unescape(m.groupValues[1].replace(Regex("<text:tab/>"), "\t").replace(Regex("<[^>]+>"), ""))
    }
}
