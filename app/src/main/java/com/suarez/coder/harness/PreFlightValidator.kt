package com.suarez.coder.harness

import com.suarez.coder.validate.StrictJson

object PreFlightValidator {
    data class StagedFile(
        val path: String,
        val before: String?,
        val after: String?,
        val explicitDelete: Boolean = false
    )

    data class Issue(val path: String, val line: Int, val reason: String) {
        /** Kısa açıklama erişimcisi (eski adlandırma ile uyumluluk). */
        val detail: String get() = reason

        override fun toString(): String = "$path:$line: $reason"
    }

    /** Tek dosya için hızlı kontrol: sorun yoksa boş liste döner. */
    fun check(path: String, before: String?, after: String?): List<Issue> =
        validate(listOf(StagedFile(path = path, before = before, after = after, explicitDelete = after == null))).issues

    data class Result(val ok: Boolean, val issues: List<Issue>) {
        fun report(): String = issues.joinToString("\n") { it.toString() }
    }

    fun validate(files: List<StagedFile>): Result {
        val issues = ArrayList<Issue>()
        for (f in files) {
            if (f.after == null && f.explicitDelete) continue
            val after = f.after ?: ""

            // 1) Accidental wipe protection (>50 lines down to <5 lines, or >=120 lines with >=40% deleted)
            val beforeLines = f.before?.lines()?.size ?: 0
            val afterLines = if (after.isNotEmpty()) after.lines().size else 0
            if (isAccidentalDelete(beforeLines, afterLines, f.explicitDelete)) {
                issues += Issue(
                    f.path,
                    1,
                    "Güvenlik engeli: $beforeLines satırlık dosya açık silme onayı olmadan $afterLines satıra düşürüldü"
                )
            }

            // 2) Conflict markers check (<<<<<<<, =======, >>>>>>>) outside string literals
            val conflictIssue = checkConflictMarkers(f.path, after)
            if (conflictIssue != null) issues += conflictIssue

            // 3) Extension specific checks
            val lowerPath = f.path.lowercase()
            when {
                lowerPath.endsWith(".kt") -> {
                    checkDuplicateImports(f.path, after, issues)
                    checkBrackets(f.path, after, isKotlin = true, issues = issues)
                }
                lowerPath.endsWith(".java") -> {
                    checkBrackets(f.path, after, isJava = true, issues = issues)
                }
                lowerPath.endsWith(".gd") -> {
                    checkBrackets(f.path, after, isGd = true, issues = issues)
                }
                lowerPath.endsWith(".json") -> {
                    checkStrictJson(f.path, after, issues)
                }
                lowerPath.endsWith(".yml") || lowerPath.endsWith(".yaml") -> {
                    checkYaml(f.path, after, issues)
                    if (lowerPath.startsWith(".github/workflows/")) {
                        checkWorkflow(f.path, after, issues)
                    }
                }
                lowerPath.endsWith("/skill.md") || lowerPath == "skill.md" -> {
                    checkSkillFrontmatter(f.path, after, issues)
                }
                lowerPath.endsWith(".xml") -> {
                    checkXml(f.path, after, issues)
                }
            }
        }
        return Result(issues.isEmpty(), issues)
    }

    fun isAccidentalDelete(beforeLines: Int, afterLines: Int, explicitDelete: Boolean): Boolean {
        if (explicitDelete) return false
        if (beforeLines > 50 && afterLines < 5) return true
        if (beforeLines >= 120 && (beforeLines - afterLines) * 100 >= beforeLines * 40) return true
        return false
    }

    private fun checkConflictMarkers(path: String, text: String): Issue? {
        val lines = text.split("\n")
        var inRawString = false
        for ((idx, line) in lines.withIndex()) {
            val lineNo = idx + 1
            // Track raw string markers
            var rIdx = line.indexOf("\"\"\"")
            while (rIdx >= 0) {
                inRawString = !inRawString
                rIdx = line.indexOf("\"\"\"", rIdx + 3)
            }
            if (inRawString) continue

            val t = line.trimStart()
            if (t.startsWith("<<<<<<<") || t.startsWith("=======") || t.startsWith(">>>>>>>")) {
                if (!line.contains("\"") && !line.contains("'")) {
                    return Issue(path, lineNo, "Dosyada çözülmemiş git çakışma işareti bulundu: ${t.take(7)}")
                }
            }
        }
        return null
    }

    private fun checkDuplicateImports(path: String, text: String, issues: MutableList<Issue>) {
        val seen = HashSet<String>()
        val lines = text.split("\n")
        for ((idx, line) in lines.withIndex()) {
            val t = line.trim()
            if (t.startsWith("import ")) {
                if (!seen.add(t)) {
                    issues += Issue(path, idx + 1, "Çifte import tespit edildi: $t")
                }
            }
        }
    }

    private fun checkBrackets(
        path: String,
        text: String,
        isKotlin: Boolean = false,
        isJava: Boolean = false,
        isGd: Boolean = false,
        issues: MutableList<Issue>
    ) {
        val stack = ArrayDeque<Pair<Char, Int>>()
        var i = 0
        val len = text.length
        var currentLine = 1
        var blockCommentDepth = 0

        while (i < len) {
            val c = text[i]
            if (c == '\n') {
                currentLine++
                i++
                continue
            }

            // Inside block comment
            if (blockCommentDepth > 0) {
                if (isKotlin && c == '/' && i + 1 < len && text[i + 1] == '*') {
                    blockCommentDepth++
                    i += 2
                    continue
                }
                if (c == '*' && i + 1 < len && text[i + 1] == '/') {
                    blockCommentDepth--
                    i += 2
                    continue
                }
                i++
                continue
            }

            // Triple quotes """..."""
            if (c == '"' && i + 2 < len && text[i + 1] == '"' && text[i + 2] == '"') {
                i += 3
                while (i < len) {
                    if (text[i] == '\n') currentLine++
                    if (text[i] == '"' && i + 2 < len && text[i + 1] == '"' && text[i + 2] == '"') {
                        i += 3
                        break
                    }
                    i++
                }
                continue
            }

            // Line comment //
            if ((isKotlin || isJava) && c == '/' && i + 1 < len && text[i + 1] == '/') {
                i += 2
                while (i < len && text[i] != '\n') i++
                continue
            }

            // GDScript comment #
            if (isGd && c == '#') {
                i++
                while (i < len && text[i] != '\n') i++
                continue
            }

            // Block comment start /*
            if ((isKotlin || isJava) && c == '/' && i + 1 < len && text[i + 1] == '*') {
                blockCommentDepth = 1
                i += 2
                continue
            }

            // Single quote '...'
            if (c == '\'') {
                i++
                while (i < len) {
                    if (text[i] == '\\') { i += 2; continue }
                    if (text[i] == '\'') { i++; break }
                    if (text[i] == '\n') break
                    i++
                }
                continue
            }

            // Double quote "..."
            if (c == '"') {
                i++
                while (i < len) {
                    if (text[i] == '\\') { i += 2; continue }
                    if (text[i] == '"') { i++; break }
                    if (text[i] == '\n') break
                    i++
                }
                continue
            }

            // Brackets
            when (c) {
                '{', '(', '[' -> stack.addLast(c to currentLine)
                '}', ')', ']' -> {
                    val expected = when (c) {
                        '}' -> '{'
                        ')' -> '('
                        ']' -> '['
                        else -> ' '
                    }
                    if (stack.isEmpty()) {
                        issues += Issue(path, currentLine, "Eşleşmeyen kapanış parantezi: '$c'")
                        return
                    }
                    val top = stack.removeLast()
                    if (top.first != expected) {
                        issues += Issue(path, currentLine, "'${top.first}' (satır ${top.second}) ile eşleşmeyen '$c' parantezi")
                        return
                    }
                }
            }
            i++
        }

        if (stack.isNotEmpty()) {
            val unclosed = stack.last()
            issues += Issue(path, unclosed.second, "Kapatılmamış parantez: '${unclosed.first}'")
        }
    }

    private fun checkStrictJson(path: String, text: String, issues: MutableList<Issue>) {
        val err = StrictJson.error(text)
        if (err != null) {
            issues += Issue(path, 1, err)
        }
    }

    private fun checkYaml(path: String, text: String, issues: MutableList<Issue>) {
        val lines = text.split("\n")
        for ((idx, line) in lines.withIndex()) {
            val lineNo = idx + 1
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) continue
            val leading = line.takeWhile { it == ' ' || it == '\t' }
            if (leading.contains('\t')) {
                issues += Issue(path, lineNo, "YAML dosyasında girinti için tab ('\\t') kullanılamaz, boşluk kullanın")
                return
            }
        }
    }

    private fun checkWorkflow(path: String, text: String, issues: MutableList<Issue>) {
        val lines = text.split("\n")
        var hasOn = false
        var hasJobs = false
        for (l in lines) {
            val trimmed = l.trim()
            if (trimmed.startsWith("#")) continue
            if (trimmed.startsWith("on:") || trimmed.startsWith("\"on\":") || trimmed.startsWith("'on':")) {
                if (l.takeWhile { it == ' ' }.isEmpty()) hasOn = true
            }
            if (trimmed.startsWith("jobs:") || trimmed.startsWith("\"jobs\":") || trimmed.startsWith("'jobs':")) {
                if (l.takeWhile { it == ' ' }.isEmpty()) hasJobs = true
            }
        }
        if (!hasOn) issues += Issue(path, 1, "Workflow dosyasında kök 'on:' anahtarı eksik")
        if (!hasJobs) issues += Issue(path, 1, "Workflow dosyasında kök 'jobs:' anahtarı eksik")
    }

    private fun checkSkillFrontmatter(path: String, text: String, issues: MutableList<Issue>) {
        val lines = text.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") {
            issues += Issue(path, 1, "SKILL.md dosyası '---' frontmatter başlangıcıyla açılmalıdır")
            return
        }
        val endIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIdx < 0) {
            issues += Issue(path, 1, "SKILL.md frontmatter'ı '---' ile kapatılmamış")
            return
        }
        val frontmatter = lines.subList(1, endIdx + 1)
        val hasName = frontmatter.any { line ->
            val t = line.trim()
            t.startsWith("name:") && t.substring(5).trim().isNotEmpty()
        }
        if (!hasName) {
            issues += Issue(path, 1, "SKILL.md frontmatter içinde boş olmayan 'name:' alanı zorunludur")
        }
    }

    private fun checkXml(path: String, text: String, issues: MutableList<Issue>) {
        var i = 0
        val len = text.length
        var currentLine = 1
        val tagStack = ArrayDeque<Pair<String, Int>>()

        while (i < len) {
            val c = text[i]
            if (c == '\n') { currentLine++; i++; continue }
            if (c != '<') { i++; continue }

            // Skip comments <!-- ... -->
            if (text.startsWith("<!--", i)) {
                i += 4
                while (i < len && !text.startsWith("-->", i)) {
                    if (text[i] == '\n') currentLine++
                    i++
                }
                if (i < len) i += 3
                continue
            }

            // Skip CDATA <![CDATA[ ... ]]>
            if (text.startsWith("<![CDATA[", i)) {
                i += 9
                while (i < len && !text.startsWith("]]>", i)) {
                    if (text[i] == '\n') currentLine++
                    i++
                }
                if (i < len) i += 3
                continue
            }

            // Skip processing instructions <? ... ?> and doctype <!DOCTYPE ...>
            if (text.startsWith("<?", i) || text.startsWith("<!DOCTYPE", i, ignoreCase = true)) {
                while (i < len && text[i] != '>') {
                    if (text[i] == '\n') currentLine++
                    i++
                }
                if (i < len) i++
                continue
            }

            // Closing tag </tag>
            if (i + 1 < len && text[i + 1] == '/') {
                val tagLine = currentLine
                val closeEnd = text.indexOf('>', i)
                if (closeEnd < 0) {
                    issues += Issue(path, tagLine, "Kapatılmamış XML etiketi")
                    return
                }
                val tagName = text.substring(i + 2, closeEnd).trim()
                if (tagStack.isEmpty()) {
                    issues += Issue(path, tagLine, "Açılmamış kapanış etiketi: </$tagName>")
                    return
                }
                val open = tagStack.removeLast()
                if (open.first != tagName) {
                    issues += Issue(path, tagLine, "<${open.first}> (satır ${open.second}) ile eşleşmeyen kapanış etiketi: </$tagName>")
                    return
                }
                i = closeEnd + 1
                continue
            }

            // Opening or self-closing tag
            val tagLine = currentLine
            var inAttrQuote = false
            var quoteChar = ' '
            var tagClosedAt = -1
            var j = i + 1
            while (j < len) {
                val ch = text[j]
                if (ch == '\n') currentLine++
                if (!inAttrQuote && (ch == '"' || ch == '\'')) {
                    inAttrQuote = true
                    quoteChar = ch
                } else if (inAttrQuote && ch == quoteChar) {
                    inAttrQuote = false
                } else if (!inAttrQuote && ch == '>') {
                    tagClosedAt = j
                    break
                }
                j++
            }

            if (tagClosedAt < 0) {
                issues += Issue(path, tagLine, "Bitirilmemiş XML etiketi")
                return
            }

            val tagBody = text.substring(i + 1, tagClosedAt).trim()
            val isSelfClosing = tagBody.endsWith("/")
            val cleanBody = if (isSelfClosing) tagBody.dropLast(1).trim() else tagBody
            val tagName = cleanBody.split(Regex("\\s+"))[0]

            if (!isSelfClosing && tagName.isNotEmpty()) {
                tagStack.addLast(tagName to tagLine)
            }
            i = tagClosedAt + 1
        }

        if (tagStack.isNotEmpty()) {
            val unclosed = tagStack.last()
            issues += Issue(path, unclosed.second, "Kapatılmamış XML etiketi: <${unclosed.first}>")
        }
    }
}
