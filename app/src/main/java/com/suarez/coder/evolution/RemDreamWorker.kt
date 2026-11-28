package com.suarez.coder.evolution

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.suarez.coder.cost.PriorityTier
import com.suarez.coder.data.Settings
import com.suarez.coder.security.ArchitecturalImmunity
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Faz 6B — Gece / Boşta Bakım İşçisi (`RemDreamWorker`).
 *
 * Yalnızca cihaz şarjdayken (`requiresCharging = true`) ve Wi-Fi bağlıyken (`UNMETERED`) çalışır.
 * Tamamen salt-okunur (`READ_ONLY`) analiz yapar; asla repoya kod yazmaz veya push etmez.
 * Üretilen antikor adaylarını [ArchitecturalImmunity.Store]'a, kısa dersleri [MultiRepoMesh]'e işler.
 */
class RemDreamWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = Settings(ctx)
        if (!settings.remDreamModeEnabled) return Result.success()

        runCatching {
            val store = EvolutionEngine.storeFor(ctx)
            val dayAgo = System.currentTimeMillis() - 24 * 3_600_000L
            val recentTraces = store.traces().filter { it.at >= dayAgo }
            val report = RemReplayEngine.replay(
                traces = recentTraces,
                config = RemReplayEngine.Config(
                    enabled = settings.remDreamModeEnabled,
                    dailyBudgetUsd = settings.remDailyBudgetUsd,
                    maxTracesPerNight = RemReplayEngine.DEFAULT_MAX_TRACES_PER_NIGHT,
                ),
            )
            if (report.immunityCandidates.isNotEmpty()) {
                val immunity = ArchitecturalImmunity.Store(File(ctx.filesDir, "evolution/immunity_rules.json"))
                report.immunityCandidates.forEach { immunity.upsert(it) }
            }
            if (report.lessons.isNotEmpty()) {
                val mesh = MultiRepoMesh(File(ctx.filesDir, "evolution/multi_repo_mesh.json"))
                report.lessons.forEach { lesson ->
                    mesh.record(
                        stackTag = lesson.stackTag,
                        text = lesson.text,
                        confidence = 0.78,
                        sourceRepo = lesson.repo,
                    )
                }
            }
        }
        return Result.success()
    }

    companion object {
        val priorityTier: PriorityTier = PriorityTier.BACKGROUND_FLEX
        private const val UNIQUE_NAME = "coder-rem-dream"

        /** Schedules nightly maintenance strictly when charging + unmetered Wi-Fi. */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            if (!enabled) {
                wm.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            val req = PeriodicWorkRequestBuilder<RemDreamWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            runCatching { wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req) }
        }
    }
}
