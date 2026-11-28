package com.suarez.coder.repair

import com.suarez.coder.harness.PreFlightValidator
import com.suarez.coder.harness.StagedTransaction
import com.suarez.coder.proof.InvariantMiner
import com.suarez.coder.proof.ProofCarryingPatchGate

/**
 * Faz 9 — `$0` Derleyici Hızlı-Onarım Motoru (`CompilerQuickFixer`).
 *
 * Mekanik derleme ve statik analiz hatalarını LLM'e tek bir token bile ödemeden `<100 ms` içinde çözer:
 * 1) `Unresolved reference` (yalnızca benzersiz paket adayı varsa alfabetik `import` ekler; belirsizlikte dokunmaz)
 * 2) Eksik `@OptIn(...)` anotasyonu (`This declaration needs opt-in`)
 * 3) `Val cannot be reassigned` -> yerel satırda `val ` -> `var `
 * 4) Çifte birebir Kotlin `import` satırları temizliği
 * 5) YAML tab karakterlerinin 2 boşluğa çevrilmesi
 *
 * Her onarım `StagedTransaction` + `PreFlightValidator` + `ProofCarryingPatchGate` üzerinden geçer;
 * aynı imza en fazla [MAX_ATTEMPTS_PER_SIGNATURE] (2) kez denenir.
 */
object CompilerQuickFixer {

    const val MAX_ATTEMPTS_PER_SIGNATURE = 2

    data class Diagnostic(
        val filePath: String,
        val line: Int,
        val column: Int,
        val code: String,
        val message: String,
        val missingSymbol: String? = null,
    )

    data class QuickFixResult(
        val fixed: Boolean,
        val rulesApplied: List<String>,
        val staged: StagedTransaction,
        val costUsd: Double = 0.0,
        val reason: String,
    )

    private val diagRe = Regex(
        """(?m)^(?:e:\s*)?(?:file://)?([^\s:]+\.(?:kt|kts|java|py|gd|ya?ml|json)):(\d+)(?::(\d+))?\s*:?\s*(.+)$"""
    )
    private val unresolvedRe = Regex("""(?:Unresolved reference(?::|\s+')|Cannot find symbol[^A-Za-z_]+)([A-Za-z_][\w]*)""")
    private val optInRe = Regex("""(?:needs opt-in|requires opt-in).*?([A-Za-z_][\w.]*(?:Experimental|OptIn|Api)[\w.]*)""", RegexOption.IGNORE_CASE)

    fun parseDiagnostics(log: String): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        for (m in diagRe.findAll(log)) {
            val rawPath = m.groupValues[1].replace('\\', '/')
            val path = rawPath.indexOf("app/src/").let { if (it >= 0) rawPath.substring(it) else rawPath.substringAfterLast('/') }
            val line = m.groupValues[2].toIntOrNull() ?: 1
            val col = m.groupValues[3].toIntOrNull() ?: 1
            val msg = m.groupValues[4].trim()
            val missing = unresolvedRe.find(msg)?.groupValues?.get(1)
            val code = when {
                missing != null -> "UNRESOLVED_REFERENCE"
                "needs opt-in" in msg.lowercase() || "requires opt-in" in msg.lowercase() -> "MISSING_OPT_IN"
                "val cannot be reassigned" in msg.lowercase() -> "VAL_REASSIGN"
                "tab" in msg.lowercase() && (path.endsWith(".yml") || path.endsWith(".yaml")) -> "YAML_TAB"
                else -> "GENERIC"
            }
            out += Diagnostic(path, line, col, code, msg, missing)
        }
        return out
    }

    /**
     * Attempts `$0` mechanical repairs using [symbolPackages] (`SimpleName -> List<fqcn>`)
     * to resolve unique missing imports without guessing when ambiguous.
     */
    fun tryFix(
        log: String,
        files: Map<String, String>,
        symbolPackages: Map<String, List<String>> = emptyMap(),
        invariants: List<InvariantMiner.Invariant> = emptyList(),
        priorAttemptsOnSignature: Int = 0,
    ): QuickFixResult {
        if (priorAttemptsOnSignature >= MAX_ATTEMPTS_PER_SIGNATURE) {
            return QuickFixResult(false, emptyList(), StagedTransaction(), 0.0, "Aynı imza için $MAX_ATTEMPTS_PER_SIGNATURE deneme sınırı doldu.")
        }
        val diagnostics = parseDiagnostics(log)
        val working = HashMap(files)
        val applied = ArrayList<String>()

        // Rule 5 (YAML tabs) & Rule 4 (duplicate Kotlin imports) proactively on matching files
        for ((path, content) in files) {
            var updated = content
            if ((path.endsWith(".yml") || path.endsWith(".yaml")) && updated.contains('\t')) {
                updated = updated.replace("\t", "  ")
                applied += "yaml_tabs_to_spaces:$path"
            }
            if (path.endsWith(".kt")) {
                val deduped = dedupeImports(updated)
                if (deduped != updated) {
                    updated = deduped
                    applied += "dedupe_imports:$path"
                }
            }
            working[path] = updated
        }

        for (d in diagnostics) {
            val targetKey = working.keys.firstOrNull { it == d.filePath || it.endsWith("/${d.filePath}") || it.endsWith(d.filePath) } ?: continue
            val current = working[targetKey] ?: continue
            when (d.code) {
                "UNRESOLVED_REFERENCE" -> {
                    val sym = d.missingSymbol ?: continue
                    val candidates = symbolPackages[sym].orEmpty().distinct()
                    // Strict rule: ONLY add import when there is a UNIQUE package candidate.
                    if (candidates.size == 1) {
                        val next = insertImportAlphabetically(current, "import ${candidates.single()}")
                        if (next != current) {
                            working[targetKey] = next
                            applied += "add_import:$sym"
                        }
                    }
                }
                "MISSING_OPT_IN" -> {
                    val ann = optInRe.find(d.message)?.groupValues?.get(1)?.substringAfterLast('.') ?: continue
                    val next = insertAnnotationAboveLine(current, d.line, "@OptIn($ann::class)")
                    if (next != current) {
                        working[targetKey] = next
                        applied += "add_opt_in:$ann"
                    }
                }
                "VAL_REASSIGN" -> {
                    val next = changeValToVarNearLine(current, d.line)
                    if (next != current) {
                        working[targetKey] = next
                        applied += "val_to_var:L${d.line}"
                    }
                }
            }
        }

        if (applied.isEmpty()) {
            return QuickFixResult(false, emptyList(), StagedTransaction(), 0.0, "Uygulanabilir $0 hızlı-onarım kuralı yok.")
        }

        val tx = StagedTransaction()
        for ((path, after) in working) {
            val before = files[path]
            if (before != after) tx.stage(path, before, after)
        }
        val preflight = tx.edits().flatMap { e -> PreFlightValidator.check(e.path, e.before, e.after) }
        if (preflight.isNotEmpty()) {
            tx.clear()
            return QuickFixResult(false, emptyList(), StagedTransaction(), 0.0, "PreFlightValidator reddi: ${preflight.first().detail}")
        }
        if (invariants.isNotEmpty()) {
            val proof = ProofCarryingPatchGate.verify(tx, invariants)
            if (proof.blocked) {
                tx.clear()
                return QuickFixResult(false, emptyList(), StagedTransaction(), 0.0, proof.blockMessage())
            }
        }
        return QuickFixResult(true, applied, tx, 0.0, "${applied.size} mekanik hata $0 maliyetle düzeltildi.")
    }

    private fun insertImportAlphabetically(source: String, importLine: String): String {
        val lines = source.lines().toMutableList()
        if (lines.any { it.trim() == importLine }) return source
        val firstImport = lines.indexOfFirst { it.trim().startsWith("import ") }
        if (firstImport >= 0) {
            var idx = firstImport
            while (idx < lines.size && lines[idx].trim().startsWith("import ") && lines[idx].trim() < importLine) {
                idx++
            }
            lines.add(idx, importLine)
        } else {
            val pkg = lines.indexOfFirst { it.trim().startsWith("package ") }
            lines.add(if (pkg >= 0) pkg + 1 else 0, importLine)
        }
        return lines.joinToString("\n")
    }

    private fun insertAnnotationAboveLine(source: String, lineNumber: Int, annotation: String): String {
        val lines = source.lines().toMutableList()
        val idx = (lineNumber - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        if (idx > 0 && lines[idx - 1].trim() == annotation) return source
        val indent = lines[idx].takeWhile { it == ' ' || it == '\t' }
        lines.add(idx, indent + annotation)
        return lines.joinToString("\n")
    }

    private fun changeValToVarNearLine(source: String, lineNumber: Int): String {
        val lines = source.lines().toMutableList()
        val target = (lineNumber - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val assignRe = Regex("""^\s*([A-Za-z_]\w*)\s*=(?!=)""")
        val varName = assignRe.find(lines[target])?.groupValues?.get(1)
        val searchRange = if (varName != null) (target downTo maxOf(0, target - 20)) else (target..target)
        val declRe = if (varName != null) Regex("""^(\s*)val(\s+$varName\b)""") else Regex("""^(\s*)val(\s+\w+)""")
        for (i in searchRange) {
            if (declRe.containsMatchIn(lines[i])) {
                lines[i] = declRe.replaceFirst(lines[i], "$1var$2")
                return lines.joinToString("\n")
            }
        }
        return source
    }

    private fun dedupeImports(source: String): String {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (line in source.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("import ")) {
                if (seen.add(trimmed)) out += line
            } else {
                out += line
            }
        }
        return out.joinToString("\n")
    }
}
