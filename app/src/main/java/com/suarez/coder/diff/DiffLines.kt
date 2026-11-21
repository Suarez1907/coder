package com.suarez.coder.diff

/** One line of a rendered diff: kind ' ' (unchanged), '-' (removed) or '+' (added). */
data class DiffLine(val kind: Char, val oldNo: Int?, val newNo: Int?, val text: String)

/** A file section of a diff view. */
data class DiffFile(val path: String, val blocks: List<List<DiffLine>>)

object DiffLines {
    /** Above this many cell comparisons the LCS is skipped (all removed, then all added). */
    private const val MAX_CELLS = 400_000

    /** Line diff of one SEARCH/REPLACE block (LCS based), with block-relative line numbers. */
    fun block(search: String, replace: String): List<DiffLine> {
        val a = if (search.isEmpty()) emptyList() else search.split("\n")
        val b = if (replace.isEmpty()) emptyList() else replace.split("\n")
        if (a.size.toLong() * b.size > MAX_CELLS) {
            return a.mapIndexed { i, t -> DiffLine('-', i + 1, null, t) } + b.mapIndexed { i, t -> DiffLine('+', null, i + 1, t) }
        }
        // lcs[i][j] = length of the LCS of a[i..] and b[j..]
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<DiffLine>()
        var i = 0; var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { out += DiffLine(' ', i + 1, j + 1, a[i]); i++; j++ }
                // On ties show the removal first, as unified diffs do (−old before +new).
                i < a.size && (j >= b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> { out += DiffLine('-', i + 1, null, a[i]); i++ }
                else -> { out += DiffLine('+', null, j + 1, b[j]); j++ }
            }
        }
        return out
    }

    /** Groups the SEARCH/REPLACE blocks of a diff text by file. */
    fun fromSearchReplace(diff: String): List<DiffFile> =
        SearchReplace.parse(diff).blocks.groupBy { it.path }.map { (path, blocks) -> DiffFile(path, blocks.map { block(it.search, it.replace) }) }

    /** New/rewritten files (github_write_files) shown as all-added. */
    fun fromWrittenFiles(files: List<Pair<String, String?>>): List<DiffFile> =
        files.map { (path, content) ->
            DiffFile(path, listOf(if (content == null) listOf(DiffLine('-', null, null, "(dosya silinir)")) else block("", content)))
        }

    /** Plain unified-style text for copying. */
    fun toText(files: List<DiffFile>): String = buildString {
        files.forEach { f ->
            append("--- ").append(f.path).append('\n')
            f.blocks.forEachIndexed { k, b ->
                append("@@ blok ").append(k + 1).append(" @@\n")
                b.forEach { append(it.kind).append(it.text).append('\n') }
            }
        }
    }
}
