package com.suarez.coder.reflex

import com.suarez.coder.evolution.SessionTrace

/**
 * Faz 7A — İz Madenciliği (`TracePatternMiner`).
 *
 * Başarılı (CI yeşil) [SessionTrace] kayıtlarını tarayıp aynı niyet/araç/dosya kalıbına sahip
 * ve en az [MIN_GREEN_OCCURRENCES] (3) kez tekrarlanmış örüntüleri tespit eder.
 */
object TracePatternMiner {

    const val MIN_GREEN_OCCURRENCES = 3

    data class MinedPattern(
        val patternKey: String,
        val intentSignature: String,
        val toolSequence: List<String>,
        val occurrences: Int,
        val sampleRequests: List<String>,
        val avgCostUsd: Double,
    )

    /** Mines recurring green-CI session trace patterns eligible for $0 reflex compilation. */
    fun mine(traces: List<SessionTrace>, minOccurrences: Int = MIN_GREEN_OCCURRENCES): List<MinedPattern> {
        val green = traces.filter { !it.stopped && it.ciFailureCount == 0 && it.diffErrorCount == 0 && it.request.isNotBlank() }
        if (green.isEmpty()) return emptyList()

        val groups = LinkedHashMap<String, MutableList<SessionTrace>>()
        for (t in green) {
            val intentSig = normalizeIntent(t.request)
            val toolSeq = t.steps.map { it.substringBefore(':').trim() }.filter { it.isNotBlank() }.take(8)
            val key = "$intentSig|${toolSeq.joinToString(">")}"
            groups.getOrPut(key) { ArrayList() }.add(t)
        }

        val out = ArrayList<MinedPattern>()
        for ((key, list) in groups) {
            if (list.size < minOccurrences) continue
            val intentSig = key.substringBefore('|')
            val toolSeq = key.substringAfter('|').split('>').filter { it.isNotBlank() }
            val avgCost = list.map { it.totalCostUsd }.average()
            out += MinedPattern(
                patternKey = key,
                intentSignature = intentSig,
                toolSequence = toolSeq,
                occurrences = list.size,
                sampleRequests = list.map { it.request.take(160) }.take(5),
                avgCostUsd = avgCost,
            )
        }
        return out.sortedByDescending { it.occurrences * (it.avgCostUsd + 0.001) }
    }

    internal fun normalizeIntent(request: String): String {
        val tokens = request.lowercase()
            .replace(Regex("""['"`][\w./-]+['"`]"""), "<arg>")
            .replace(Regex("""\b\d+(?:\.\d+)*\b"""), "<num>")
            .replace(Regex("""[^a-z0-9ğüşıöç<> ]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length >= 3 }
            .take(6)
        return tokens.joinToString("_").ifBlank { "generic" }
    }
}
