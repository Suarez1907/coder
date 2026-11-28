package com.suarez.coder.context

import com.suarez.coder.cost.CacheTtlTracker
import java.security.MessageDigest

/**
 * Faz 9 — Oturumlar-Arası Paylaşımlı Önbellek Öneki (`CrossSessionPrefixManager`).
 *
 * Her repo için byte-byte deterministik statik önek ([RepoStaticPrefix]) üretir:
 * `[Sistem Talimatı] + [Alfabetik Sıralı Araç Şemaları] + [Stabil Repo Mimari Özeti (treeSha sabit kaldıkça)]`.
 * Dinamik veriler (tarih/saat, görev metni, Turn-0 dilimleri, ScopedRules) kesinlikle önekin SONRASINA yerleştirilir.
 * Böylece yeni sohbet oturumları aynı `prefixHash` ve `reuseKey` ile sıcak KV/explicit önbelleği paylaşır.
 */
object CrossSessionPrefixManager {

    data class RepoStaticPrefix(
        val repo: String,
        val model: String,
        val treeSha: String,
        val text: String,
        val prefixHash: String,
    ) {
        /** Cache reuse key shared across sessions for the same repo + model + static prefix. */
        val reuseKey: String get() = "${repo.lowercase()}|${model.lowercase()}|$prefixHash"
    }

    /**
     * Builds a byte-for-byte deterministic static prefix.
     * Tool schemas are sorted alphabetically so ordering differences across sessions never break KV cache.
     */
    fun buildStaticPrefix(
        repo: String,
        model: String,
        treeSha: String,
        systemPrompt: String,
        toolSchemas: Map<String, String>,
        stableRepoSummary: String,
    ): RepoStaticPrefix {
        val sortedTools = toolSchemas.entries.sortedBy { it.key }.joinToString("\n") { (name, schema) ->
            "TOOL:$name=$schema"
        }
        val text = buildString {
            append(systemPrompt.trim()).append("\n\n")
            if (sortedTools.isNotEmpty()) {
                append("# TOOLS\n").append(sortedTools).append("\n\n")
            }
            if (stableRepoSummary.isNotBlank()) {
                append("# REPO_SUMMARY@").append(treeSha).append('\n')
                append(stableRepoSummary.trim())
            }
        }
        val hash = sha256(text).take(20)
        return RepoStaticPrefix(repo, model, treeSha, text, hash)
    }

    /** Appends dynamic per-session context strictly AFTER the immutable static prefix. */
    fun composeWithDynamicTail(prefix: RepoStaticPrefix, dynamicTail: String): String =
        if (dynamicTail.isBlank()) prefix.text else prefix.text + "\n\n# DYNAMIC_TASK\n" + dynamicTail.trim()

    /**
     * Cache-friendly tombstoning: only collapses stale read outputs when the cache is COLD
     * or the context threshold is exceeded, never breaking a WARM cache prefix.
     */
    fun shouldTombstoneStaleReads(
        tracker: CacheTtlTracker?,
        provider: String,
        currentChars: Int,
        thresholdChars: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (currentChars >= thresholdChars) return true
        val view = tracker?.view(provider, nowMs) ?: return false
        return view.state == CacheTtlTracker.State.COLD
    }

    fun tombstone(path: String, sha: String): String = "[Önceki okuma sıkıştırıldı: $path @ ${sha.take(7)}]"

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
