package com.suarez.coder.context

/**
 * Entry point named by the phase-3 spec. Indexing stays in [RepoDependencyGraph]: only a changed SHA is reparsed.
 */
class SymbolIndexer(val graph: RepoDependencyGraph = RepoDependencyGraph()) {
    fun update(path: String, sha: String, text: String): Boolean = graph.update(path, sha, text)

    fun symbols(path: String): List<RepoDependencyGraph.Symbol> = graph.symbolsOf(path)

    /** Compact `kind name:line` list. No model call. */
    fun format(path: String): String {
        val rows = symbols(path)
        if (rows.isEmpty()) return "Sembol yok: $path"
        return rows.joinToString("\n", prefix = "$path\n") { s ->
            val params = if (s.requiredParams >= 0) " zorunlu=${s.requiredParams}" else ""
            "${s.kind} ${s.name}:${s.line}$params"
        }
    }
}
