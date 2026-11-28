package com.suarez.coder.validate

import com.suarez.coder.repomap.RepoSkeletonExtractor

/**
 * Faz 6A — Kod İskeletleme & Diferansiyel Doğrulama (`DifferentialVerifier`).
 *
 * 1) Düzenleme öncesi/sonrası dosya çiftleri üzerinde deterministik yapısal fark denetimi:
 *    - Silinen veya private'a daraltılan `public`/`internal` imzalar
 *    - Kırılan `package` / `import` sözleşmeleri
 *    - Yeni eklenen riskli null/hata dalları (`!!`, boş `catch` blokları, suspend içinde yutulan `CancellationException`)
 *    - Yapısal kısıt ihlalleri (parantez dengesi, aşırı beklenmedik dosya silinmesi)
 * 2) Büyük düzenleme öncesi hedef dosyanın yalnızca sınıf/metod imzalarından oluşan hafif iskeletini
 *    ([skeletonize]) üretip gereksiz tam-dosya okumasını önler.
 */
object DifferentialVerifier {

    enum class Severity { BLOCKING, WARNING }

    enum class IssueKind {
        REMOVED_PUBLIC_SIGNATURE,
        CHANGED_SIGNATURE_ARITY,
        PACKAGE_MISMATCH,
        BROKEN_IMPORT_CONTRACT,
        MISSING_ERROR_OR_NULL_GUARD,
        CONSTRAINT_VIOLATION,
    }

    data class Issue(
        val path: String,
        val severity: Severity,
        val kind: IssueKind,
        val detail: String,
    )

    data class VerificationReport(
        val issues: List<Issue>,
    ) {
        val hasBlocking: Boolean get() = issues.any { it.severity == Severity.BLOCKING }
        val isClean: Boolean get() = issues.isEmpty()

        /** 0.0 (ağır ihlal) .. 1.0 (temiz) deterministik doğrulama skoru. */
        val score: Double
            get() {
                if (issues.isEmpty()) return 1.0
                val penalty = issues.sumOf { if (it.severity == Severity.BLOCKING) 0.45 else 0.12 }
                return (1.0 - penalty).coerceIn(0.0, 1.0)
            }

        fun summary(): String = if (issues.isEmpty()) "Diferansiyel doğrulama temiz."
        else issues.joinToString("\n") { "[${it.severity}] ${it.path} (${it.kind}): ${it.detail}" }
    }

    data class SkeletonView(
        val path: String,
        val skeleton: String,
        val originalChars: Int,
        val skeletonChars: Int,
    ) {
        val reductionPercent: Int
            get() = if (originalChars <= 0) 0
            else (((originalChars - skeletonChars).coerceAtLeast(0) * 100.0) / originalChars).toInt()
    }

    private val pkgRe = Regex("""(?m)^\s*package\s+([\w.]+)""")
    private val importRe = Regex("""(?m)^\s*import\s+([\w.]+)""")
    private val sigNameRe = Regex("""(?:class|interface|object|fun|val|var|typealias)\s+(?:<[^>]+>\s+)?([\w.]+)""")
    private val emptyCatchRe = Regex("""catch\s*\([^)]*\)\s*\{\s*\}""")
    private val bangBangRe = Regex("""(?<!\!)!!(?!\!)""")

    fun verify(path: String, before: String?, after: String?): VerificationReport {
        if (after == null || before == after) return VerificationReport(emptyList())
        val isKotlin = path.endsWith(".kt") || path.endsWith(".kts")
        val isJava = path.endsWith(".java")
        if (!isKotlin && !isJava) return VerificationReport(emptyList())

        val issues = ArrayList<Issue>()

        // 1) Package declaration stability
        if (before != null) {
            val oldPkg = pkgRe.find(before)?.groupValues?.get(1)
            val newPkg = pkgRe.find(after)?.groupValues?.get(1)
            if (oldPkg != null && oldPkg != newPkg) {
                issues += Issue(path, Severity.BLOCKING, IssueKind.PACKAGE_MISMATCH, "Paket bildirimi değişti: `$oldPkg` → `${newPkg ?: "(yok)"}`")
            }
        }

        // 2) Broken import contract: removed import whose simple symbol is still referenced in the new body
        if (before != null) {
            val oldImports = importRe.findAll(before).map { it.groupValues[1] }.toSet()
            val newImports = importRe.findAll(after).map { it.groupValues[1] }.toSet()
            val bodyWithoutImports = after.lines().filterNot { it.trim().startsWith("import ") || it.trim().startsWith("package ") }.joinToString("\n")
            for (removed in oldImports - newImports) {
                if (removed.endsWith(".*")) continue
                val simple = removed.substringAfterLast('.')
                if (simple.length >= 3 && Regex("""\b${Regex.escape(simple)}\b""").containsMatchIn(bodyWithoutImports)) {
                    issues += Issue(
                        path,
                        Severity.BLOCKING,
                        IssueKind.BROKEN_IMPORT_CONTRACT,
                        "Kaldırılan import `$removed` hâlâ kodda `$simple` olarak kullanılıyor.",
                    )
                }
            }
        }

        // 3) Public/internal signature removal or arity break
        if (before != null) {
            val oldSigs = extractPublicSignatures(path, before)
            val newSigs = extractPublicSignatures(path, after)
            for ((name, oldSig) in oldSigs) {
                val newSig = newSigs[name]
                if (newSig == null) {
                    issues += Issue(path, Severity.WARNING, IssueKind.REMOVED_PUBLIC_SIGNATURE, "Public/internal imza kaldırıldı: `$oldSig`")
                } else {
                    val oldArity = paramArity(oldSig)
                    val newArity = paramArity(newSig)
                    if (oldArity >= 0 && newArity >= 0 && oldArity != newArity) {
                        issues += Issue(
                            path,
                            Severity.WARNING,
                            IssueKind.CHANGED_SIGNATURE_ARITY,
                            "`$name` parametre sayısı değişti ($oldArity → $newArity).",
                        )
                    }
                }
            }
        }

        // 4) Missing null / error branches introduced by the edit
        val beforeEmptyCatches = before?.let { emptyCatchRe.findAll(it).count() } ?: 0
        val afterEmptyCatches = emptyCatchRe.findAll(after).count()
        if (afterEmptyCatches > beforeEmptyCatches) {
            issues += Issue(path, Severity.WARNING, IssueKind.MISSING_ERROR_OR_NULL_GUARD, "Yeni boş `catch {}` bloğu eklendi; hata yutulabilir.")
        }
        val beforeBangs = before?.let { bangBangRe.findAll(it).count() } ?: 0
        val afterBangs = bangBangRe.findAll(after).count()
        if (afterBangs > beforeBangs) {
            issues += Issue(path, Severity.WARNING, IssueKind.MISSING_ERROR_OR_NULL_GUARD, "Yeni `!!` (unsafe non-null assertion) eklendi (+${afterBangs - beforeBangs}).")
        }
        if (isKotlin && after.contains("suspend fun") && Regex("""catch\s*\(\s*\w+\s*:\s*Exception\s*\)""").containsMatchIn(after) && !after.contains("CancellationException")) {
            val hadBefore = before != null && before.contains("suspend fun") && Regex("""catch\s*\(\s*\w+\s*:\s*Exception\s*\)""").containsMatchIn(before) && !before.contains("CancellationException")
            if (!hadBefore) {
                issues += Issue(path, Severity.WARNING, IssueKind.MISSING_ERROR_OR_NULL_GUARD, "`suspend fun` içinde `catch (e: Exception)` bulunuyor ancak `CancellationException` yeniden fırlatılmıyor.")
            }
        }

        // 5) Structural constraints: balanced braces & accidental truncation
        if (!hasBalancedBraces(after)) {
            issues += Issue(path, Severity.BLOCKING, IssueKind.CONSTRAINT_VIOLATION, "Süslü parantez `{}` dengesi bozuk.")
        }
        if (before != null && before.length > 600 && after.length < before.length / 5) {
            issues += Issue(path, Severity.WARNING, IssueKind.CONSTRAINT_VIOLATION, "Dosya boyutu %80'den fazla küçüldü (${before.length} → ${after.length} karakter); eksik içerik riski.")
        }

        return VerificationReport(issues)
    }

    fun verifyAll(changes: List<Triple<String, String?, String?>>): VerificationReport {
        val all = changes.flatMap { (path, before, after) -> verify(path, before, after).issues }
        return VerificationReport(all)
    }

    /**
     * Produces a lightweight signature-only skeleton of [content] to prevent unnecessary full-file reads
     * before large edits. Preserves package header, imports count, and all type/method/field signatures.
     */
    fun skeletonize(path: String, content: String): SkeletonView {
        if (content.isBlank()) return SkeletonView(path, "", 0, 0)
        val outline = RepoSkeletonExtractor.extract(path, content)
        val header = buildString {
            pkgRe.find(content)?.let { append(it.value.trim()).append('\n') }
            val importCount = importRe.findAll(content).count()
            if (importCount > 0) append("// $importCount imports omitted\n")
            if (outline.isNotEmpty()) {
                append(outline.joinToString("\n"))
            } else {
                append(content.lines().take(25).joinToString("\n"))
            }
        }.trim()
        return SkeletonView(path, header, content.length, header.length)
    }

    private fun extractPublicSignatures(path: String, text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in RepoSkeletonExtractor.extract(path, text)) {
            val line = raw.trim()
            if (line.startsWith("private ") || line.startsWith("…")) continue
            val name = sigNameRe.find(line)?.groupValues?.get(1) ?: continue
            out.putIfAbsent(name, line)
        }
        return out
    }

    private fun paramArity(sig: String): Int {
        val open = sig.indexOf('(')
        val close = sig.lastIndexOf(')')
        if (open < 0 || close <= open) return -1
        val inside = sig.substring(open + 1, close).trim()
        if (inside.isEmpty()) return 0
        var depth = 0
        var count = 1
        for (c in inside) {
            when (c) {
                '(', '<', '[' -> depth++
                ')', '>', ']' -> depth = (depth - 1).coerceAtLeast(0)
                ',' -> if (depth == 0) count++
            }
        }
        return count
    }

    private fun hasBalancedBraces(src: String): Boolean {
        var depth = 0
        var inStr: Char? = null
        var inLine = false
        var inBlock = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            if (inLine) { if (c == '\n') inLine = false; i++; continue }
            if (inBlock) { if (c == '*' && next == '/') { inBlock = false; i += 2 } else i++; continue }
            if (inStr != null) {
                if (c == '\\') { i += 2; continue }
                if (c == inStr) inStr = null
                i++; continue
            }
            if (c == '/' && next == '/') { inLine = true; i += 2; continue }
            if (c == '/' && next == '*') { inBlock = true; i += 2; continue }
            if (c == '"' || c == '\'') { inStr = c; i++; continue }
            if (c == '{') depth++
            else if (c == '}') { depth--; if (depth < 0) return false }
            i++
        }
        return depth == 0
    }
}
