package com.suarez.coder.diff

/**
 * Parses and applies aider-style SEARCH/REPLACE blocks:
 *
 *     path/to/file.kt
 *     <<<<<<< SEARCH
 *     old lines
 *     =======
 *     new lines
 *     >>>>>>> REPLACE
 *
 * An empty SEARCH section creates a new file (or fills an empty one).
 */
object SearchReplace {
    data class Block(val path: String, val search: String, val replace: String, val index: Int)

    data class ParseResult(val blocks: List<Block>, val errors: List<String>)

    data class Failure(val block: Block, val reason: String)

    data class FileResult(val content: String, val applied: List<Block>, val failures: List<Failure>)

    private val searchRe = Regex("^\\s*<{5,9}\\s*SEARCH\\s*$")
    private val dividerRe = Regex("^\\s*={5,9}\\s*$")
    private val replaceRe = Regex("^\\s*>{5,9}\\s*REPLACE\\s*$")
    private val fenceRe = Regex("^\\s*```.*$")

    fun parse(text: String): ParseResult {
        val lines = text.replace("\r\n", "\n").split("\n")
        val blocks = ArrayList<Block>()
        val errors = ArrayList<String>()
        var candidate: String? = null
        var lastPath: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (searchRe.matches(line)) {
                val path = candidate ?: lastPath
                val startLine = i + 1
                val search = ArrayList<String>()
                i++
                while (i < lines.size && !dividerRe.matches(lines[i])) {
                    if (searchRe.matches(lines[i]) || replaceRe.matches(lines[i])) break
                    search += lines[i]; i++
                }
                if (i >= lines.size || !dividerRe.matches(lines[i])) {
                    errors += "Blok (satır $startLine): '=======' ayırıcısı bulunamadı."
                    candidate = null
                    continue
                }
                i++
                val replace = ArrayList<String>()
                while (i < lines.size && !replaceRe.matches(lines[i])) {
                    if (searchRe.matches(lines[i]) || dividerRe.matches(lines[i])) break
                    replace += lines[i]; i++
                }
                if (i >= lines.size || !replaceRe.matches(lines[i])) {
                    errors += "Blok (satır $startLine): '>>>>>>> REPLACE' bitişi bulunamadı."
                    candidate = null
                    continue
                }
                if (path == null) {
                    errors += "Blok (satır $startLine): dosya yolu yok. Yolu SEARCH satırının hemen üstüne yazın."
                } else {
                    blocks += Block(path, search.joinToString("\n"), replace.joinToString("\n"), blocks.size + 1)
                    lastPath = path
                }
                candidate = null
                i++
                continue
            }
            val t = line.trim()
            if (t.isNotEmpty() && !fenceRe.matches(line)) candidate = cleanPath(t)
            i++
        }
        return ParseResult(blocks, errors)
    }

    private fun cleanPath(raw: String): String? {
        var s = raw.trim().trim('`', '*', '"', '\'').trim()
        s = s.removePrefix("File:").removePrefix("Dosya:").trim()
        // Remove all leading # characters and any whitespace after them (markdown headings)
        s = s.replace(Regex("^#+\\s*"), "").trim()
        s = s.trim('`', '*').trim()
        s = s.removePrefix("./")
        if (s.isBlank() || s.length > 300) return null
        return s
    }

    /** Applies blocks for a single file in order. Failed blocks are reported, successful ones kept. */
    fun applyToFile(original: String?, blocks: List<Block>): FileResult {
        var content = original?.replace("\r\n", "\n")
        val applied = ArrayList<Block>()
        val failures = ArrayList<Failure>()
        for (b in blocks) {
            if (b.search.isBlank()) {
                if (content == null || content.isBlank()) {
                    content = b.replace + if (b.replace.endsWith("\n")) "" else "\n"
                    applied += b
                } else {
                    failures += Failure(b, "SEARCH bölümü boş ama dosya zaten var. Değiştirilecek mevcut satırları SEARCH'e yazın.")
                }
                continue
            }
            if (content == null) {
                failures += Failure(b, "Dosya mevcut değil. Yeni dosya için SEARCH bölümünü boş bırakın.")
                continue
            }
            when (val r = replaceOnce(content, b.search, b.replace)) {
                is Match.Ok -> { content = r.text; applied += b }
                is Match.Err -> failures += Failure(b, r.reason)
            }
        }
        return FileResult(content ?: "", applied, failures)
    }

    sealed class Match {
        data class Ok(val text: String) : Match()
        data class Err(val reason: String) : Match()
    }

    /** A SEARCH line that abbreviates the middle of a long region. */
    private fun isEllipsis(line: String) = line.trim() == "..." || line.trim() == "…"

    fun replaceOnce(content: String, search: String, replace: String): Match {
        val sLines = search.split("\n")
        if (sLines.count { isEllipsis(it) } == 1 && !content.contains(search)) return replaceAnchored(content, sLines, replace)
        // 1) Exact match.
        val exact = countOccurrences(content, search)
        if (exact == 1) return Match.Ok(content.replaceFirst(search, replace))
        if (exact > 1) return Match.Err("SEARCH metni dosyada $exact kez geçiyor; benzersiz olması için daha fazla satır ekleyin.")

        // 2) Line based: ignore trailing whitespace, then ignore indentation.
        val fileLines = content.split("\n")
        val searchLines = search.split("\n").let { trimBlankEdges(it) }
        if (searchLines.isEmpty()) return Match.Err("SEARCH bölümü yalnızca boş satırlardan oluşuyor.")
        val replaceLines = replace.split("\n")

        for (mode in 0..1) {
            val norm: (String) -> String = if (mode == 0) { s -> s.trimEnd() } else { s -> s.trim() }
            val target = searchLines.map(norm)
            val hits = ArrayList<Int>()
            for (start in 0..fileLines.size - target.size) {
                var ok = true
                for (k in target.indices) if (norm(fileLines[start + k]) != target[k]) { ok = false; break }
                if (ok) hits += start
            }
            if (hits.size > 1) return Match.Err("SEARCH metni dosyada ${hits.size} yerde eşleşiyor; daha fazla bağlam satırı ekleyin.")
            if (hits.size == 1) {
                val start = hits[0]
                val newLines = if (mode == 1) reindent(searchLines, fileLines.subList(start, start + target.size), replaceLines) else replaceLines
                val out = ArrayList<String>(fileLines.size)
                out.addAll(fileLines.subList(0, start))
                if (!(replace.isEmpty())) out.addAll(newLines)
                out.addAll(fileLines.subList(start + target.size, fileLines.size))
                return Match.Ok(out.joinToString("\n"))
            }
        }
        return Match.Err("SEARCH metni dosyada bulunamadı" + hint(fileLines, searchLines) + ". Dosyayı tekrar okuyup satırları birebir kopyalayın.")
    }

    /**
     * SEARCH of the form `head lines / ... / tail lines`: the region runs from the unique head match to the first
     * tail match after it. Saves output tokens when a long block is replaced.
     */
    private fun replaceAnchored(content: String, sLines: List<String>, replace: String): Match {
        val cut = sLines.indexOfFirst { isEllipsis(it) }
        val head = trimBlankEdges(sLines.subList(0, cut))
        val tail = trimBlankEdges(sLines.subList(cut + 1, sLines.size))
        if (head.isEmpty() || tail.isEmpty()) return Match.Err("'...' kısaltmasının önünde ve arkasında en az birer satır olmalı.")
        val fileLines = content.split("\n")
        for (mode in 0..1) {
            val norm: (String) -> String = if (mode == 0) { x -> x.trimEnd() } else { x -> x.trim() }
            fun matchesAt(start: Int, pat: List<String>) =
                start + pat.size <= fileLines.size && pat.indices.all { norm(fileLines[start + it]) == norm(pat[it]) }
            val heads = (0..fileLines.size - head.size).filter { matchesAt(it, head) }
            if (heads.size > 1) return Match.Err("'...' öncesindeki satırlar dosyada ${heads.size} yerde geçiyor; daha fazla satır ekleyin.")
            if (heads.size == 1) {
                val h = heads[0]
                val t = (h + head.size..fileLines.size - tail.size).firstOrNull { matchesAt(it, tail) }
                    ?: return Match.Err("'...' sonrasındaki satırlar, öncesindeki satırlardan sonra bulunamadı.")
                val end = t + tail.size
                val region = fileLines.subList(h, end)
                val newLines = if (mode == 1) reindent(head + tail, region.take(head.size) + region.takeLast(tail.size), replace.split("\n")) else replace.split("\n")
                val out = ArrayList<String>(fileLines.size)
                out.addAll(fileLines.subList(0, h))
                if (replace.isNotEmpty()) out.addAll(newLines)
                out.addAll(fileLines.subList(end, fileLines.size))
                return Match.Ok(out.joinToString("\n"))
            }
        }
        return Match.Err("'...' öncesindeki satırlar dosyada bulunamadı. Dosyayı tekrar okuyup satırları birebir kopyalayın.")
    }

    private fun trimBlankEdges(lines: List<String>): List<String> {
        var a = 0; var b = lines.size
        while (a < b && lines[a].isBlank()) a++
        while (b > a && lines[b - 1].isBlank()) b--
        return lines.subList(a, b)
    }

    private fun indentOf(s: String) = s.takeWhile { it == ' ' || it == '\t' }

    /** Shifts the replacement's indentation by the difference between the search and the actual text. */
    private fun reindent(search: List<String>, actual: List<String>, replace: List<String>): List<String> {
        val si = search.indexOfFirst { it.isNotBlank() }
        if (si < 0) return replace
        val sInd = indentOf(search[si]); val aInd = indentOf(actual[si])
        return when {
            aInd.length > sInd.length && aInd.startsWith(sInd) -> {
                val extra = aInd.substring(sInd.length)
                replace.map { if (it.isBlank()) it else extra + it }
            }
            sInd.length > aInd.length && sInd.startsWith(aInd) -> {
                val cut = sInd.length - aInd.length
                replace.map { l -> if (indentOf(l).length >= cut) l.substring(cut) else l.trimStart() }
            }
            else -> replace
        }
    }

    private fun hint(fileLines: List<String>, search: List<String>): String {
        val first = search.firstOrNull { it.isNotBlank() }?.trim() ?: return ""
        val idx = fileLines.indexOfFirst { it.trim() == first }
        return if (idx >= 0) " (ilk satır ${idx + 1}. satırda var ama devamı farklı)" else ""
    }

    fun countOccurrences(text: String, sub: String): Int {
        if (sub.isEmpty()) return 0
        var count = 0
        var i = text.indexOf(sub)
        while (i >= 0) { count++; i = text.indexOf(sub, i + 1) }
        return count
    }

    fun describeFailures(failures: List<Failure>): String = failures.joinToString("\n\n") { f ->
        val head = f.block.search.lines().take(3).joinToString("\n")
        "- ${f.block.path} (blok #${f.block.index}): ${f.reason}\n  SEARCH başı:\n$head"
    }

    /** Line counts for a short "+x −y" summary. */
    fun lineDelta(before: String?, after: String?): Pair<Int, Int> {
        val a = before?.split("\n") ?: emptyList()
        val b = after?.split("\n") ?: emptyList()
        val counts = HashMap<String, Int>()
        a.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        var added = 0
        b.forEach { l -> val c = counts[l] ?: 0; if (c > 0) counts[l] = c - 1 else added++ }
        val removed = counts.values.sum()
        return added to removed
    }
}
