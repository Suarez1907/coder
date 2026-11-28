package com.suarez.coder.debug

/**
 * Faz 8A — Otomatik Delta Debugging (`ddmin`) Motoru (`DeltaDebugger`).
 *
 * Andreas Zeller'in `ddmin` algoritması ile `items` içindeki hatayı üreten 1-minimal alt kümeyi
 * `$0` LLM maliyetiyle izole eder. Derleme hatası veren ara alt kümeleri ([OracleResult.UNRESOLVED_COMPILE_ERROR])
 * güvenle atlar ve sert yoklama ([maxProbes] = 16) ile zaman ([maxDurationMs] = 45_000 ms) tavanına uyar.
 */
object DeltaDebugger {

    const val DEFAULT_MAX_PROBES = 16
    const val DEFAULT_MAX_DURATION_MS = 45_000L

    enum class OracleResult {
        PASS,
        FAIL_SAME_SIGNATURE,
        UNRESOLVED_COMPILE_ERROR,
    }

    data class DeltaResult<T>(
        val minimalItems: List<T>,
        val originalSize: Int,
        val probesUsed: Int,
        val cappedOut: Boolean,
    ) {
        val reductionPercent: Int
            get() = if (originalSize <= 0) 0
            else (((originalSize - minimalItems.size).coerceAtLeast(0) * 100.0) / originalSize).toInt()

        fun formatBanner(renderItem: (T) -> String): String =
            "[DDMIN KÖK NEDEN]: $originalSize değişiklik bloğundan yalnızca ${minimalItems.size} blok hatayı tetikliyor -> " +
                minimalItems.joinToString(" | ") { renderItem(it) }
    }

    suspend fun <T> minimize(
        items: List<T>,
        maxProbes: Int = DEFAULT_MAX_PROBES,
        maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
        testOracle: suspend (List<T>) -> OracleResult,
    ): DeltaResult<T> {
        if (items.size <= 1) return DeltaResult(items, items.size, 0, false)
        val started = System.currentTimeMillis()
        var probes = 0
        var capped = false

        suspend fun probe(candidate: List<T>): OracleResult {
            if (probes >= maxProbes || System.currentTimeMillis() - started >= maxDurationMs) {
                capped = true
                return OracleResult.UNRESOLVED_COMPILE_ERROR
            }
            probes++
            return testOracle(candidate)
        }

        var subset = items
        var n = 2
        while (subset.size >= 2 && !capped) {
            val parts = partition(subset, n)
            var reduced = false

            // 1) Try each chunk alone
            for (part in parts) {
                if (part.isEmpty()) continue
                val res = probe(part)
                if (capped) break
                if (res == OracleResult.FAIL_SAME_SIGNATURE) {
                    subset = part
                    n = 2
                    reduced = true
                    break
                }
            }
            if (reduced || capped) continue

            // 2) Try each complement (subset minus chunk), skipping UNRESOLVED_COMPILE_ERROR cleanly
            for (i in parts.indices) {
                val complement = parts.filterIndexed { idx, _ -> idx != i }.flatten()
                if (complement.isEmpty()) continue
                val res = probe(complement)
                if (capped) break
                if (res == OracleResult.FAIL_SAME_SIGNATURE) {
                    subset = complement
                    n = (n - 1).coerceAtLeast(2)
                    reduced = true
                    break
                }
            }
            if (!reduced && !capped) {
                if (n >= subset.size) break
                n = minOf(subset.size, n * 2)
            }
        }
        return DeltaResult(subset, items.size, probes, capped)
    }

    private fun <T> partition(items: List<T>, n: Int): List<List<T>> {
        val out = ArrayList<List<T>>(n)
        val base = items.size / n
        val rem = items.size % n
        var offset = 0
        for (i in 0 until n) {
            val sz = base + if (i < rem) 1 else 0
            out += items.subList(offset, offset + sz)
            offset += sz
        }
        return out
    }
}
