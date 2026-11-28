package com.suarez.coder.prefetch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Faz 8B — Tahminsel Sıfır-Gecikme Ön-Isıtma (`SpeculativePrefetcher`).
 *
 * Kullanıcı metin yazarken veya sesli dikte ederken:
 * - `500 ms` debounce ve `>= 18` karakter eşiği ile yalnızca `$0` yerel ön-hazırlık yapar (asla paralı bulut LLM çağırmaz).
 * - Kullanıcı yazmaya devam ederse eski `PrefetchJob` iptal edilir (`cancel()`).
 * - Hazırlanan sonuçlar `90 sn` TTL'li [PrefetchBuffer]'da tutulur ve "Gönder" anında milisaniyede devredilir.
 * - Pil tasarrufu (`isPowerSaveMode`) veya mobil veri (`isMetered`) modunda uzak `POST /v1/warm` isteği otomatik kesilir.
 */
class SpeculativePrefetcher(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val minChars: Int = DEFAULT_MIN_CHARS,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val warmCooldownMs: Long = DEFAULT_WARM_COOLDOWN_MS,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DEFAULT_DEBOUNCE_MS = 500L
        const val DEFAULT_MIN_CHARS = 18
        const val DEFAULT_TTL_MS = 90_000L
        const val DEFAULT_WARM_COOLDOWN_MS = 5 * 60_000L
    }

    data class EnvironmentState(
        val enabled: Boolean = true,
        val isMetered: Boolean = false,
        val isPowerSaveMode: Boolean = false,
    )

    data class PrefetchSnapshot(
        val queryFingerprint: String,
        val candidatePaths: List<String>,
        val warmedSandbox: Boolean,
        val createdAtMs: Long,
        val cloudLlmCalled: Boolean = false,
    ) {
        fun statusLine(): String = "Ön-ısıtma hazır · 0 ms bağlam (${candidatePaths.size} dosya)"
    }

    @Volatile private var activeJob: Job? = null
    @Volatile private var buffer: PrefetchSnapshot? = null
    @Volatile private var lastWarmAtMs: Long = 0L

    /**
     * Triggered on draft text change; debounces [debounceMs] and cancels any prior in-flight prefetch.
     */
    fun onDraftChanged(
        scope: CoroutineScope,
        draftText: String,
        env: EnvironmentState,
        resolveLocalCandidates: suspend (String) -> List<String>,
        remoteWarm: (suspend () -> Boolean)? = null,
    ) {
        activeJob?.cancel()
        if (!env.enabled || draftText.trim().length < minChars) return
        activeJob = scope.launch {
            delay(debounceMs)
            val candidates = runCatching { resolveLocalCandidates(draftText) }.getOrDefault(emptyList()).take(5)
            val now = nowProvider()
            val allowRemoteWarm = remoteWarm != null &&
                !env.isMetered &&
                !env.isPowerSaveMode &&
                (now - lastWarmAtMs >= warmCooldownMs)
            val warmed = if (allowRemoteWarm) {
                runCatching { remoteWarm!!.invoke() }.getOrDefault(false).also { if (it) lastWarmAtMs = now }
            } else {
                false
            }
            buffer = PrefetchSnapshot(
                queryFingerprint = draftText.trim().lowercase().take(80),
                candidatePaths = candidates,
                warmedSandbox = warmed,
                createdAtMs = now,
                cloudLlmCalled = false,
            )
        }
    }

    /** Returns the warm buffer if still within [ttlMs], otherwise clears expired data and returns null. */
    fun consumeIfFresh(): PrefetchSnapshot? {
        val snap = buffer ?: return null
        val now = nowProvider()
        if (now - snap.createdAtMs > ttlMs) {
            buffer = null
            return null
        }
        return snap
    }

    fun clear() {
        activeJob?.cancel()
        buffer = null
    }
}
