package com.suarez.coder.tools

/** Picks the paragraphs of a long page that matter for a query, locally and for free. */
object WebFocus {
    fun select(text: String, query: String, maxChars: Int = 6_000): String {
        if (text.length <= maxChars) return text
        val terms = Regex("[\\p{L}\\p{N}_.#-]{3,}").findAll(query.lowercase()).map { it.value }.toSet()
        if (terms.isEmpty()) return text.take(maxChars)
        // Paragraphs: blank-line separated, long ones split into ~800 char chunks.
        val paras = text.split(Regex("\\n\\s*\\n")).flatMap { p -> if (p.length <= 1_200) listOf(p) else p.chunked(800) }
        val scored = paras.mapIndexed { i, p ->
            val lp = p.lowercase()
            val hits = terms.count { lp.contains(it) }
            val code = if (p.contains("(") && p.contains(")") || p.contains("{")) 1 else 0
            Triple(i, hits * 10 + code, p)
        }.filter { it.second >= 10 }
        if (scored.isEmpty()) return text.take(maxChars)
        val chosen = HashSet<Int>()
        var size = 0
        for ((i, _, p) in scored.sortedByDescending { it.second }) {
            if (size + p.length > maxChars) continue
            chosen += i; size += p.length
        }
        return paras.indices.filter { it in chosen }.joinToString("\n\n…\n\n") { paras[it].trim() }
    }
}
