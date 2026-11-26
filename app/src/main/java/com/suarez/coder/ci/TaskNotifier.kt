package com.suarez.coder.ci

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.suarez.coder.MainActivity

/** "Background task finished" notifications (a chat that kept running after the user switched to another one). */
object TaskNotifier {
    private const val CHANNEL = "coder_tasks"

    fun done(ctx: Context, title: String, request: String, answer: String) {
        if (!CiWatchService.canNotify(ctx)) return
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Arka plan görevleri", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("$title: $request")
            .setContentText(answer.lineSequence().firstOrNull()?.take(160) ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(answer))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100_000).toInt() + 1000, n) }
    }
}
