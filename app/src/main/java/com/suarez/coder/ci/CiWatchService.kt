package com.suarez.coder.ci

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.suarez.coder.MainActivity

/**
 * Foreground service shown while ci_wait waits for GitHub Actions: keeps the app process alive with the
 * screen off and shows repo, workflow and elapsed time. The waiting itself runs in the agent coroutine.
 */
class CiWatchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = intent?.getStringExtra(EXTRA_REPO) ?: ""
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: ""
        val since = intent?.getLongExtra(EXTRA_SINCE, System.currentTimeMillis()) ?: System.currentTimeMillis()
        ensureChannels(this)
        val n = NotificationCompat.Builder(this, CH_LIVE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CI bekleniyor · $repo")
            .setContentText(label)
            .setWhen(since)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(this, null))
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID_LIVE, n, type)
        return START_NOT_STICKY
    }

    companion object {
        private const val CH_LIVE = "ci_live"
        private const val CH_RESULT = "ci_result"
        private const val ID_LIVE = 4101
        private const val ID_RESULT = 4102
        private const val EXTRA_REPO = "repo"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_SINCE = "since"
        const val EXTRA_INSTALL_URL = "install_url"

        private var since = 0L

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_LIVE, "CI izleme", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_RESULT, "CI sonuçları", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /** Starts (or updates) the live notification. Silently skipped when Android does not allow it right now. */
        fun start(ctx: Context, repo: String, label: String) {
            if (since == 0L) since = System.currentTimeMillis()
            val i = Intent(ctx, CiWatchService::class.java)
                .putExtra(EXTRA_REPO, repo).putExtra(EXTRA_LABEL, label).putExtra(EXTRA_SINCE, since)
            // Android 12+ refuses foreground services started from the background; CI waiting still works without it.
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        /** Stops the live notification and posts the result. */
        fun finish(ctx: Context, repo: String, success: Boolean, summary: String, apkUrl: String?) {
            since = 0L
            runCatching { ctx.stopService(Intent(ctx, CiWatchService::class.java)) }
            if (!canNotify(ctx)) return
            ensureChannels(ctx)
            val b = NotificationCompat.Builder(ctx, CH_RESULT)
                .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle((if (success) "✓ CI başarılı · " else "✗ CI başarısız · ") + repo)
                .setContentText(summary.lineSequence().firstOrNull()?.take(200) ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary.take(600)))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx, null))
            if (success && apkUrl != null) {
                b.addAction(android.R.drawable.stat_sys_download, "APK Kur", openApp(ctx, apkUrl))
            }
            runCatching { NotificationManagerCompat.from(ctx).notify(ID_RESULT, b.build()) }
        }

        private fun openApp(ctx: Context, installUrl: String?): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            installUrl?.let { i.putExtra(EXTRA_INSTALL_URL, it) }
            return PendingIntent.getActivity(ctx, if (installUrl == null) 0 else 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        @Suppress("unused")
        private fun unused(n: Notification) = n
    }
}
