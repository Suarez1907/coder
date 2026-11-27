package com.suarez.coder.qa

import android.content.Context
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Diff-Focused Mutation Tester (Phase 5B).
 *
 * Generates at most 5 targeted micro-mutants strictly from added/changed executable
 * lines in the diff. Skips pure comments, whitespace, markdown/text files, and
 * UI color constants. Blocks merge if any mutant survives and provides exact
 * (path, line, original -> mutated) feedback for at most 1 recovery round.
 */
class DiffMutationTester(
    private val statsStore: MutationStatsStore? = null,
) {
    data class DiffLineChange(
        val path: String,
        val lineNumber: Int,
        val content: String,
    )

    data class DiffMutant(
        val id: Int,
        val path: String,
        val lineNumber: Int,
        val originalLine: String,
        val mutatedLine: String,
        val operator: String,
    )

    data class MutationEvaluation(
        val skipped: Boolean,
        val totalMutants: Int,
        val killedMutants: Int,
        val survivingMutants: List<DiffMutant>,
        val allowMerge: Boolean,
        val canRetry: Boolean,
        val recoveryPrompt: String,
        val summaryLine: String,
    )

    private val colorRegex = Regex("""Color\s*\(\s*0x[0-9A-Fa-f]{6,8}\s*\)""")
    private val ifCondRegex = Regex("""\bif\s*\(([^()]+)\)""")

    /**
     * Returns false if the diff only touches comments, whitespace, markdown/text files,
     * or UI Color(0x...) constants.
     */
    fun isLogicChange(changes: List<DiffLineChange>): Boolean {
        return changes.any { isExecutableLogicLine(it.path, it.content) }
    }

    private fun isExecutableLogicLine(path: String, rawLine: String): Boolean {
        val lowerPath = path.lowercase()
        if (lowerPath.endsWith(".md") || lowerPath.endsWith(".txt")) return false

        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return false
        if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) return false

        // Skip lines that only define or update a UI Color(0x...) constant.
        if (colorRegex.containsMatchIn(trimmed)) {
            val withoutColor = colorRegex.replace(trimmed, "").trim()
            val hasLogicOp = listOf("if", "when", "==", "!=", "&&", "||", ">=", "<=", ">", "<")
                .any { withoutColor.contains(it) }
            if (!hasLogicOp) return false
        }

        return true
    }

    /**
     * Generates at most [maxMutants] (default 5) micro-mutants from changed/added
     * executable lines only.
     */
    fun generateDiffMutants(
        changes: List<DiffLineChange>,
        maxMutants: Int = 5,
    ): List<DiffMutant> {
        val cap = maxMutants.coerceIn(1, 5)
        val mutants = mutableListOf<DiffMutant>()
        var nextId = 1

        for (change in changes) {
            if (mutants.size >= cap) break
            if (!isExecutableLogicLine(change.path, change.content)) continue

            val mutated = mutateLine(change.content) ?: continue
            mutants.add(
                DiffMutant(
                    id = nextId++,
                    path = change.path,
                    lineNumber = change.lineNumber,
                    originalLine = change.content,
                    mutatedLine = mutated.first,
                    operator = mutated.second,
                ),
            )
        }
        return mutants
    }

    private fun mutateLine(line: String): Pair<String, String>? {
        // 1. Relational operators: > <-> >=, < <-> <=
        if (line.contains(">=")) return line.replaceFirst(">=", ">") to ">= -> >"
        if (line.contains("<=")) return line.replaceFirst("<=", "<") to "<= -> <"
        if (Regex("""(?<!=)>(?!=)""").containsMatchIn(line) && !line.contains("->")) {
            return Regex("""(?<!=)>(?!=)""").replaceFirst(line, ">=") to "> -> >="
        }
        if (Regex("""(?<!=)<(?!=)""").containsMatchIn(line)) {
            return Regex("""(?<!=)<(?!=)""").replaceFirst(line, "<=") to "< -> <="
        }

        // 2. Equality operators: == <-> !=
        if (line.contains("==")) return line.replaceFirst("==", "!=") to "== -> !="
        if (line.contains("!=")) return line.replaceFirst("!=", "==") to "!= -> =="

        // 3. Logical operators: && <-> ||
        if (line.contains("&&")) return line.replaceFirst("&&", "||") to "&& -> ||"
        if (line.contains("||")) return line.replaceFirst("||", "&&") to "|| -> &&"

        // 4. Branch condition: if (cond) -> if (false)
        if (ifCondRegex.containsMatchIn(line)) {
            val replaced = ifCondRegex.replaceFirst(line, "if (false)")
            if (replaced != line) return replaced to "if(cond) -> if(false)"
        }

        // 5. Boolean / zero-one literals: true <-> false, 0 <-> 1
        val trueRegex = Regex("""\btrue\b""")
        if (trueRegex.containsMatchIn(line)) return trueRegex.replaceFirst(line, "false") to "true -> false"
        val falseRegex = Regex("""\bfalse\b""")
        if (falseRegex.containsMatchIn(line)) return falseRegex.replaceFirst(line, "true") to "false -> true"
        val zeroRegex = Regex("""\b0\b""")
        if (zeroRegex.containsMatchIn(line)) return zeroRegex.replaceFirst(line, "1") to "0 -> 1"
        val oneRegex = Regex("""\b1\b""")
        if (oneRegex.containsMatchIn(line)) return oneRegex.replaceFirst(line, "0") to "1 -> 0"

        return null
    }

    /**
     * Evaluates each generated mutant against [runner] within [timeoutMs] (default 60s).
     * [runner] returns true if the test suite KILLED the mutant (tests failed),
     * or false if the mutant SURVIVED (tests still passed).
     */
    suspend fun evaluate(
        changes: List<DiffLineChange>,
        timeoutMs: Long = 60_000L,
        recoveryAttempt: Int = 0,
        runner: suspend (DiffMutant) -> Boolean,
    ): MutationEvaluation {
        if (!isLogicChange(changes)) {
            return MutationEvaluation(
                skipped = true,
                totalMutants = 0,
                killedMutants = 0,
                survivingMutants = emptyList(),
                allowMerge = true,
                canRetry = false,
                recoveryPrompt = "",
                summaryLine = "Mutasyon Testi: atlandı (mantık değişikliği yok)",
            )
        }

        val mutants = generateDiffMutants(changes, maxMutants = 5)
        if (mutants.isEmpty()) {
            return MutationEvaluation(
                skipped = true,
                totalMutants = 0,
                killedMutants = 0,
                survivingMutants = emptyList(),
                allowMerge = true,
                canRetry = false,
                recoveryPrompt = "",
                summaryLine = "Mutasyon Testi: atlandı (uygun mutant yok)",
            )
        }

        val surviving = mutableListOf<DiffMutant>()
        var killed = 0

        withTimeoutOrNull(timeoutMs.coerceIn(1_000L, 60_000L)) {
            for (mutant in mutants) {
                val isKilled = runCatching { runner(mutant) }.getOrDefault(false)
                if (isKilled) {
                    killed++
                } else {
                    surviving.add(mutant)
                }
            }
        }

        val allowMerge = surviving.isEmpty()
        val canRetry = !allowMerge && recoveryAttempt < 1
        val recoveryPrompt = if (allowMerge) {
            ""
        } else {
            buildString {
                appendLine("Mutasyon Testi başarısız: ${surviving.size}/${mutants.size} mutant hayatta kaldı.")
                appendLine("Aşağıdaki satırlar için test kapsamını güçlendir:")
                surviving.forEach { m ->
                    appendLine("- ${m.path}:${m.lineNumber} | ${m.originalLine.trim()} -> ${m.mutatedLine.trim()}")
                }
            }.trim()
        }

        statsStore?.record(total = mutants.size, killed = killed)

        return MutationEvaluation(
            skipped = false,
            totalMutants = mutants.size,
            killedMutants = killed,
            survivingMutants = surviving,
            allowMerge = allowMerge,
            canRetry = canRetry,
            recoveryPrompt = recoveryPrompt,
            summaryLine = "Mutasyon Testi: $killed/${mutants.size} mutant öldürüldü",
        )
    }
}

/** Persistent store for Diff Mutation Testing metrics. */
class MutationStatsStore(context: Context) {
    private val prefs = context.getSharedPreferences("mutation_stats", Context.MODE_PRIVATE)

    val totalMutants: Int get() = prefs.getInt("total_mutants", 0)
    val totalKilled: Int get() = prefs.getInt("total_killed", 0)

    fun record(total: Int, killed: Int) {
        prefs.edit()
            .putInt("total_mutants", totalMutants + total)
            .putInt("total_killed", totalKilled + killed)
            .apply()
    }
}
