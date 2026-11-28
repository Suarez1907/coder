package com.suarez.coder.cache

import com.suarez.coder.harness.PreFlightValidator
import com.suarez.coder.proof.InvariantMiner
import com.suarez.coder.proof.ProofCarryingPatchGate
import java.security.MessageDigest

/**
 * Faz 8B — Merkle-AST Semantik Akıl Yürütme Önbelleği (`MerkleAstCache`).
 *
 * - `AlphaNormalizer`: Kotlin/Python/GDScript bloklarındaki yerel tanımlayıcıları ve string sabitlerini
 *   deterministik sırayla (`$V0`, `$V1`, `$STR0`) kanonikleştirip alfa-değişmez **Merkle-AST Hash** üretir.
 *   Hiçbir gizli veri veya özel iş mantığı sızmaz.
 * - `SemanticPatchAdapter`: `verifiedCount >= 2` olan kesin yapısal eşleşmede ters alfa-yeniden-adlandırma
 *   ile `$0.0000` maliyetli yama üretir; `PreFlightValidator` + `ProofCarryingPatchGate` kapılarından
 *   geçemezse sessizce iptal edip normal LLM akışına devreder.
 */
class MerkleAstCache(private val maxEntries: Int = MAX_ENTRIES) {

    companion object {
        const val MAX_ENTRIES = 250
        const val MIN_VERIFIED_COUNT = 2

        private val KEYWORDS = setOf(
            "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else",
            "when", "for", "while", "do", "return", "try", "catch", "finally", "throw", "true", "false",
            "null", "is", "in", "as", "by", "private", "public", "internal", "protected", "override",
            "suspend", "data", "sealed", "enum", "companion", "const", "this", "super", "def", "func",
            "and", "or", "not", "None", "True", "False", "Int", "Long", "Double", "Float", "Boolean",
            "String", "List", "Map", "Set", "Unit", "Any",
        )
        private val TOKEN_RE = Regex("""("(?:\\.|[^"\\])*")|(\b[A-Za-z_][A-Za-z0-9_]*\b)""")
    }

    data class NormalizedAst(
        val canonicalText: String,
        val astHash: String,
        /** Forward map: real identifier -> canonical placeholder ($V0, $STR0). */
        val forwardMap: Map<String, String>,
        /** Reverse map: canonical placeholder -> real identifier. */
        val reverseMap: Map<String, String>,
    )

    data class CacheEntry(
        val beforeAstHash: String,
        val afterCanonicalPatch: String,
        val verifiedCount: Int,
        val lastUsedMs: Long,
    )

    data class AdaptResult(
        val hit: Boolean,
        val applied: Boolean,
        val adaptedCode: String?,
        val elapsedMs: Long,
        val costUsd: Double = 0.0,
        val reason: String,
    ) {
        fun statusLine(): String = "Merkle-AST isabeti · $elapsedMs ms · \$0.0000"
    }

    private val store = LinkedHashMap<String, CacheEntry>()

    /** Alpha-invariant canonicalizer producing a privacy-safe Merkle-AST hash. */
    fun normalize(code: String): NormalizedAst {
        val fwd = LinkedHashMap<String, String>()
        val rev = LinkedHashMap<String, String>()
        var varIdx = 0
        var strIdx = 0

        val canonical = TOKEN_RE.replace(code.trim()) { m ->
            val strLit = m.groupValues[1]
            val ident = m.groupValues[2]
            when {
                strLit.isNotEmpty() -> {
                    val key = fwd.getOrPut(strLit) { "\$STR${strIdx++}" }
                    rev[key] = strLit
                    key
                }
                ident.isNotEmpty() && ident !in KEYWORDS -> {
                    val key = fwd.getOrPut(ident) { "\$V${varIdx++}" }
                    rev[key] = ident
                    key
                }
                else -> m.value
            }
        }
        val compact = canonical.replace(Regex("""[ \t]+"""), " ")
        val hash = sha256(compact).take(16)
        return NormalizedAst(compact, hash, fwd, rev)
    }

    /** Records a CI-green, ProofGate-verified transformation pair into the LRU cache (max 250). */
    @Synchronized
    fun recordVerified(beforeCode: String, afterCode: String, nowMs: Long = System.currentTimeMillis()): CacheEntry {
        val normBefore = normalize(beforeCode)
        // Canonicalize `afterCode` strictly with `before`'s symbol table: only identifiers already present in
        // `normBefore.forwardMap` get a placeholder. Anything new in `afterCode` (e.g. newly added stdlib calls
        // such as `coerceAtLeast`) stays literal so reverse alpha-renaming cannot mangle it.
        val fwd = normBefore.forwardMap
        val canonicalAfter = TOKEN_RE.replace(afterCode.trim()) { m ->
            val strLit = m.groupValues[1]
            val ident = m.groupValues[2]
            when {
                strLit.isNotEmpty() -> fwd[strLit] ?: m.value
                ident.isNotEmpty() && ident !in KEYWORDS -> fwd[ident] ?: m.value
                else -> m.value
            }
        }
        val existing = store[normBefore.astHash]
        val entry = if (existing != null && existing.afterCanonicalPatch == canonicalAfter) {
            existing.copy(verifiedCount = existing.verifiedCount + 1, lastUsedMs = nowMs)
        } else {
            CacheEntry(normBefore.astHash, canonicalAfter, verifiedCount = 1, lastUsedMs = nowMs)
        }
        store[normBefore.astHash] = entry
        evictIfNeeded()
        return entry
    }

    /**
     * Attempts `$0` isomorphic structural adaptation via reverse alpha-renaming.
     * Validates candidate through [PreFlightValidator] and [ProofCarryingPatchGate];
     * on any warning/failure returns `applied = false` for silent fallback to normal LLM.
     */
    @Synchronized
    fun tryAdapt(
        path: String,
        targetBeforeCode: String,
        invariants: List<InvariantMiner.Invariant> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): AdaptResult {
        val t0 = System.nanoTime()
        val norm = normalize(targetBeforeCode)
        val entry = store[norm.astHash]
        if (entry == null || entry.verifiedCount < MIN_VERIFIED_COUNT) {
            val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
            return AdaptResult(false, false, null, ms, 0.0, "Önbellek eşleşmesi yok veya doğrulanma < $MIN_VERIFIED_COUNT.")
        }

        // Reverse alpha-renaming using target's real symbols
        val adapted = Regex("""\$(?:V|STR)\d+""").replace(entry.afterCanonicalPatch) { m ->
            norm.reverseMap[m.value] ?: m.value
        }

        // Reject if any unmapped canonical placeholder leaked into output
        if (Regex("""\$(?:V|STR)\d+""").containsMatchIn(adapted)) {
            val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            return AdaptResult(true, false, null, ms, 0.0, "Eşlenmemiş kanonik yer tutucu tespit edildi; LLM'e devredildi.")
        }

        val preflight = PreFlightValidator.check(path, targetBeforeCode, adapted)
        if (preflight.isNotEmpty()) {
            val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            return AdaptResult(true, false, null, ms, 0.0, "PreFlightValidator reddi: ${preflight.first().detail}")
        }
        if (invariants.isNotEmpty()) {
            val proof = ProofCarryingPatchGate.verifyChanges(listOf(Triple(path, targetBeforeCode, adapted)), invariants)
            if (proof.blocked) {
                val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                return AdaptResult(true, false, null, ms, 0.0, proof.blockMessage())
            }
        }

        store[norm.astHash] = entry.copy(lastUsedMs = nowMs)
        val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        return AdaptResult(true, true, adapted, ms, 0.0, "Başarılı")
    }

    @Synchronized fun size(): Int = store.size
    @Synchronized fun clear() = store.clear()

    private fun evictIfNeeded() {
        while (store.size > maxEntries) {
            val victim = store.entries.minWithOrNull(
                compareBy<Map.Entry<String, CacheEntry>> { it.value.verifiedCount }.thenBy { it.value.lastUsedMs }
            )?.key ?: break
            store.remove(victim)
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
