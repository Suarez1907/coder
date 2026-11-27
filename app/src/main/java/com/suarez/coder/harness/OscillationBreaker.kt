package com.suarez.coder.harness

import java.security.MessageDigest

/**
 * Detects an edit that returns to an earlier body of the same file (A -> B -> A).
 * After [threshold] such loops the caller must escalate instead of accepting another cheap retry.
 * The count lives here; traces copy [escalations] rather than parsing a notice.
 */
class OscillationBreaker(private val threshold: Int = DEFAULT_THRESHOLD) {
    data class Decision(val oscillated: Boolean, val escalate: Boolean, val escalations: Int)

    private val hashes = HashMap<String, ArrayDeque<String>>()
    var escalations: Int = 0
        private set

    fun observe(path: String, content: String): Decision {
        val key = path.trim().removePrefix("./").ifBlank { return Decision(false, escalations >= threshold, escalations) }
        val hash = fingerprint(content)
        val q = hashes.getOrPut(key) { ArrayDeque() }
        if (q.lastOrNull() == hash) return Decision(false, escalations >= threshold, escalations)
        q.addLast(hash)
        while (q.size > 8) q.removeFirst()
        val oscillated = q.size >= 3 &&
            q.elementAt(q.size - 1) == q.elementAt(q.size - 3) &&
            q.elementAt(q.size - 1) != q.elementAt(q.size - 2)
        if (oscillated) escalations++
        return Decision(oscillated, escalations >= threshold, escalations)
    }

    fun reset() {
        hashes.clear()
        escalations = 0
    }

    companion object {
        const val DEFAULT_THRESHOLD = 1

        /** Stable within a run: trimmed lines, so a pure re-indent is not a new region. */
        fun fingerprint(content: String): String {
            val norm = content.replace("\r\n", "\n").lineSequence().map { it.trimEnd() }.joinToString("\n")
            val dig = MessageDigest.getInstance("SHA-256").digest(norm.toByteArray())
            return dig.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
