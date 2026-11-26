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
    enum class MatchTier { EXACT, WHITESPACE_NORMALIZED, LINE_ANCHOR }

    data class Block(
        val path: String,
        val search: String,
        val replace: String,
        val index: Int,
        val startLine: Int? = null
    )

    data class ParseResult(val blocks: List<Block>, val errors: List<String>)

    data class Failure(val block: Block, val reason: String)

    data class FileResult(
        val content: String,
        val applied: List<Block>,
        val failures: List<Failure>,
        val tiersUsed: Map<Int, MatchTier> = emptyMap()
    )

    private val searchRe = Regex("^\\s*<{5,9}\\s*SEARCH(?:\\s*(?:@L?|:)(\\d+))?\\s*$", RegexOption.IGNORE_CASE)
    private val dividerRe = Regex("^\\s*={5,9}\\s*$")
    private val replaceRe = Regex("^\\s*>{5,9}\\s*REPLACE\\s*$")
    private val fenceRe = Regex("^\\s*```.*$")
    private val lineHintRe = Regex("(?:@L?|:)(\\d+)\\s*$")

    fun parse(text: String, defaultStartLine: Int? = null): ParseResult {
        val lines = text.replace("\r\n", "\n").split("\n")
        val blocks = ArrayList<Block>()
        val errors = ArrayList<String>()
        var candidate: String? = null
        var candidateLineHint: Int? = null
        var lastPath: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val searchMatch = searchRe.find(line)
            if (searchMatch != null) {
                val path = candidate ?: lastPath
                val blockStartLine = i + 1
                val inlineHint = searchMatch.groupValues.getOrNull(1)?.toIntOrNull()
                val parsedStartLine = inlineHint ?: candidateLineHint ?: defaultStartLine
                val search = ArrayList<String>()
                i++
                while (i < lines.size && !dividerRe.matches(lines[i])) {
                    if (searchRe.matches(lines[i]) || replaceRe.matches(lines[i])) break
                    search += lines[i]; i++
                }
                if (i >= lines.size || !dividerRe.matches(lines[i])) {
                    errors += "Blok (satır $blockStartLine): '=======' ayırıcısı bulunamadı."
                    candidate = null
                    candidateLineHint = null
                    continue
                }
                i++
                val replace = ArrayList<String>()
                while (i < lines.size && !replaceRe.matches(lines[i])) {
                    if (searchRe.matches(lines[i]) || dividerRe.matches(lines[i])) break
                    replace += lines[i]; i++
                }
                if (i >= lines.size || !replaceRe.matches(lines[i])) {
                    errors += "Blok (satır $blockStartLine): '>>>>>>> REPLACE' bitişi bulunamadı."
                    candidate = null
                    candidateLineHint = null
                    continue
                }
                if (path == null) {
                    errors += "Blok (satır $blockStartLine): dosya yolu yok. Yolu SEARCH satırının hemen üstüne yazın."
                } else {
                    blocks += Block(
                        path = path,
                        search = search.joinToString("\n"),
                        replace = replace.joinToString("\n"),
                        index = blocks.size + 1,
                        startLine = parsedStartLine
                    )
                    lastPath = path
                }
                candidate = null
                candidateLineHint = null
                i++
                continue
            }
            val t = line.trim()
            if (t.isNotEmpty() && !fenceRe.matches(line)) {
                val cleaned = cleanPathWithLineHint(t)
                candidate = cleaned.first
                candidateLineHint = cleaned.second
            }
            i++
        }
        return ParseResult(blocks, errors)
    }

    private fun cleanPathWithLineHint(raw: String): Pair<String?, Int?> {
        var s = raw.trim().trim('`', '*', '"', '\'').trim()
        s = s.removePrefix("File:").removePrefix("Dosya:").trim()
        s = s.replace(Regex("^#+\\s*"), "").trim()
        s = s.trim('`', '*').trim()
        s = s.removePrefix("./")
        if (s.isBlank() || s.length > 300) return null to null
        val hintMatch = lineHintRe.find(s)
        val hint = hintMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (hintMatch != null) {
            s = s.substring(0, hintMatch.range.first).trim()
        }
        if (s.isBlank() || s.length > 300) return null to null
        return s to hint
    }

    /** Applies blocks for a single file in order. Failed blocks are reported, successful ones kept. */
    fun applyToFile(original: String?, blocks: List<Block>): FileResult {
        var content = original?.replace("\r\n", "\n")
        val applied = ArrayList<Block>()
        val failures = ArrayList<Failure>()
        val tiers = mutableMapOf<Int, MatchTier>()
        for (b in blocks) {
            if (b.search.isBlank()) {
                if (content == null || content.isBlank()) {
                    content = b.replace + if (b.replace.endsWith("\n")) "" else "\n"
                    applied += b
                    tiers[b.index] = MatchTier.EXACT
                } else {
                    failures += Failure(b, "SEARCH bölümü boş ama dosya zaten var. Değiştirilecek mevcut satırları SEARCH'e yazın.")
                }
                continue
            }
            if (content == null) {
                failures += Failure(b, "Dosya mevcut değil. Yeni dosya için SEARCH bölümünü boş bırakın.")
                continue
            }
            when (val r = replaceOnce(content, b.search, b.replace, b.startLine)) {
                is Match.Ok -> {
                    content = r.text
                    applied += b
                    tiers[b.index] = r.tierUsed
                }
                is Match.Err -> failures += Failure(b, r.reason)
            }
        }
        return FileResult(content ?: "", applied, failures, tiers)
    }

    sealed class Match {
        data class Ok(val text: String, val tierUsed: MatchTier = MatchTier.EXACT) : Match()
        data class Err(val reason: String) : Match()
    }

    /** A SEARCH line that abbreviates the middle of a long region. */
    private fun isEllipsis(line: String) = line.trim() == "..." || line.trim() == "…"

    fun replaceOnce(content: String, search: String, replace: String, startLine: Int? = null): Match {
        val sLines = search.split("\n")
        if (sLines.count { isEllipsis(it) } == 1 && !content.contains(search)) {
            return replaceAnchored(content, sLines, replace)
        }

        // Tier 1: EXACT match on CRLF normalized content
        val normContent = content.replace("\r\n", "\n")
        val normSearch = search.replace("\r\n", "\n")
        val normReplace = replace.replace("\r\n", "\n")

        val exactOccurrences = findExactLineIndices(normContent, normSearch)
        if (exactOccurrences.size == 1) {
            return Match.Ok(normContent.replaceFirst(normSearch, normReplace), MatchTier.EXACT)
        }
        if (exactOccurrences.size > 1) {
            val linesStr = exactOccurrences.joinToString(", ")
            return Match.Err("SEARCH metni dosyada ${exactOccurrences.size} kez geçiyor (satırlar: $linesStr); benzersiz olması için daha fazla satır ekleyin.")
        }

        val fileLines = normContent.split("\n")
        val searchLines = normSearch.split("\n").let { trimBlankEdges(it) }
        if (searchLines.isEmpty()) return Match.Err("SEARCH bölümü yalnızca boş satırlardan oluşuyor.")
        val replaceLines = normReplace.split("\n")

        // Tier 2: WHITESPACE_NORMALIZED (trimEnd then trim)
        for (mode in 0..1) {
            val norm: (String) -> String = if (mode == 0) { s -> s.trimEnd() } else { s -> s.trim() }
            val target = searchLines.map(norm)
            val hits = ArrayList<Int>()
            for (start in 0..fileLines.size - target.size) {
                var ok = true
                for (k in target.indices) {
                    if (norm(fileLines[start + k]) != target[k]) { ok = false; break }
                }
                if (ok) hits += start
            }
            if (hits.size > 1) {
                val lineNums = hits.map { it + 1 }
                return Match.Err("SEARCH metni dosyada ${hits.size} yerde eşleşiyor (satırlar: ${lineNums.joinToString(", ")}); daha fazla bağlam satırı ekleyin.")
            }
            if (hits.size == 1) {
                val start = hits[0]
                val newLines = reindent(searchLines, fileLines.subList(start, start + target.size), replaceLines)
                val out = ArrayList<String>(fileLines.size)
                out.addAll(fileLines.subList(0, start))
                if (normReplace.isNotEmpty()) out.addAll(newLines)
                out.addAll(fileLines.subList(start + target.size, fileLines.size))
                return Match.Ok(out.joinToString("\n"), MatchTier.WHITESPACE_NORMALIZED)
            }
        }

        // Tier 3: LINE_ANCHOR (startLine +/- 25 window, first 2 & last 2 anchor lines)
        if (startLine != null && startLine > 0) {
            val anchorMatch = matchLineAnchor(fileLines, searchLines, replaceLines, startLine)
            if (anchorMatch is Match.Ok) return anchorMatch
        }

        // All 3 tiers failed: generate intelligent feedback with closest window
        val windowHint = closestWindow(fileLines, searchLines, startLine, maxLines = 15)
        return Match.Err("SEARCH metni dosyada bulunamadı. Dosyayı tekrar okuyup satırları birebir kopyalayın.\n$windowHint")
    }

    private fun findExactLineIndices(content: String, search: String): List<Int> {
        val result = ArrayList<Int>()
        var idx = content.indexOf(search)
        while (idx >= 0) {
            val lineNum = content.substring(0, idx).count { it == '\n' } + 1
            result.add(lineNum)
            idx = content.indexOf(search, idx + 1)
        }
        return result
    }

    private fun matchLineAnchor(
        fileLines: List<String>,
        searchLines: List<String>,
        replaceLines: List<String>,
        startLine: Int
    ): Match? {
        val nonBlankSearch = searchLines.filter { it.isNotBlank() }
        if (nonBlankSearch.isEmpty()) return null

        val headAnchors = nonBlankSearch.take(2).map { it.trim() }
        val tailAnchors = nonBlankSearch.takeLast(2).map { it.trim() }

        val center = startLine - 1
        val winStart = (center - 25).coerceAtLeast(0)
        val winEnd = (center + 25 + searchLines.size).coerceAtMost(fileLines.size)
        if (winStart >= winEnd) return null

        fun matchesPatternAt(idx: Int, pat: List<String>): Boolean {
            if (idx + pat.size > winEnd) return false
            return pat.indices.all { fileLines[idx + it].trim() == pat[it] }
        }

        val headMatches = (winStart until winEnd).filter { matchesPatternAt(it, headAnchors) }
        if (headMatches.size != 1) return null
        val hIdx = headMatches[0]

        val tMatches = (hIdx + headAnchors.size until winEnd).filter { matchesPatternAt(it, tailAnchors) }
        if (tMatches.size != 1) return null
        val tIdx = tMatches[0] + tailAnchors.size

        val regionSize = tIdx - hIdx
        if (regionSize > searchLines.size + 10 || regionSize <= 0) return null

        val region = fileLines.subList(hIdx, tIdx)
        val newLines = reindent(searchLines, region, replaceLines)
        val out = ArrayList<String>(fileLines.size)
        out.addAll(fileLines.subList(0, hIdx))
        if (replaceLines.isNotEmpty() && (replaceLines.size > 1 || replaceLines[0].isNotEmpty())) {
            out.addAll(newLines)
        }
        out.addAll(fileLines.subList(tIdx, fileLines.size))
        return Match.Ok(out.joinToString("\n"), MatchTier.LINE_ANCHOR)
    }

    private fun closestWindow(
        fileLines: List<String>,
        searchLines: List<String>,
        startLine: Int?,
        maxLines: Int = 15
    ): String {
        if (fileLines.isEmpty()) return "En yakın pencere: (dosya boş)"
        val firstSearch = searchLines.firstOrNull { it.isNotBlank() }?.trim() ?: ""

        val centerLineIdx = if (firstSearch.isNotEmpty()) {
            var bestIdx = -1
            var bestScore = -1
            for (i in fileLines.indices) {
                val f = fileLines[i].trim()
                if (f.isEmpty()) continue
                val score = commonTokenScore(f, firstSearch)
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = i
                }
            }
            if (bestScore > 0) bestIdx else ((startLine?.let { it - 1 }) ?: 0).coerceIn(0, fileLines.size - 1)
        } else {
            ((startLine?.let { it - 1 }) ?: 0).coerceIn(0, fileLines.size - 1)
        }

        val half = maxLines / 2
        val start = (centerLineIdx - half).coerceAtLeast(0)
        val end = (start + maxLines).coerceAtMost(fileLines.size)
        val actualStart = (end - maxLines).coerceAtLeast(0)

        val snippet = (actualStart until end).joinToString("\n") { idx ->
            "${idx + 1}: ${fileLines[idx]}"
        }
        return "En yakın dosya penceresi (satırlar ${actualStart + 1}..$end):\n$snippet"
    }

    private fun commonTokenScore(a: String, b: String): Int {
        if (a == b) return 1000
        val tokensA = a.split(Regex("[^a-zA-Z0-9_]+")).filter { it.isNotBlank() }.toSet()
        val tokensB = b.split(Regex("[^a-zA-Z0-9_]+")).filter { it.isNotBlank() }.toSet()
        val common = tokensA.intersect(tokensB).size
        return common * 10 - kotlin.math.abs(a.length - b.length)
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
