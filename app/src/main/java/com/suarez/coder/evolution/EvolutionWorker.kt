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

/**
 * Weekly self-improvement loop (crash watch, GitHub scout, exams). Runs as a foreground job because an exam waits
 * for CI and can take longer than a normal background job is allowed to.
 */
class WeeklyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { setForeground(foreground(applicationContext, "Coder kendini sınıyor")) }
        runCatching {
            WeeklyCycle(applicationContext) { s -> runCatching { setForegroundAsync(foreground(applicationContext, s)) } }.run()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "coder-weekly"
        private const val CHANNEL = "coder_self"

        fun schedule(context: Context, wifiOnly: Boolean) {
            val req = androidx.work.PeriodicWorkRequestBuilder<WeeklyWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, androidx.work.ExistingPeriodicWorkPolicy.KEEP, req) }
        }

        fun foreground(context: Context, text: String): androidx.work.ForegroundInfo {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Kendini geliştirme", android.app.NotificationManager.IMPORTANCE_LOW))
            }
            val n = androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Coder")
                .setContentText(text.take(120))
                .setOngoing(true)
                .build()
            return if (android.os.Build.VERSION.SDK_INT >= 29) androidx.work.ForegroundInfo(4207, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else androidx.work.ForegroundInfo(4207, n)
        }
    }
}
