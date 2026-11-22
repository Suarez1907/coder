package com.suarez.coder.evolution

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Background evolution run: no UI, network only (Wi-Fi when asked), exits cleanly when disabled or over budget. */
class EvolutionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { EvolutionEngine(applicationContext).run(force = false) }
        return Result.success()
    }

    companion object {
        private const val NAME = "coder-evolution"

        /** Queued after a recorded session; KEEP so a burst of sessions becomes one run. */
        fun schedule(context: Context, wifiOnly: Boolean, delayMinutes: Long = 3) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<EvolutionWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req) }
        }
    }
}
