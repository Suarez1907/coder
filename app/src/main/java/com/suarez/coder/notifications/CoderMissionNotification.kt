package com.suarez.coder.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.suarez.coder.MainActivity
import com.suarez.coder.cost.Pricing

/** What the foreground card shows. Pure data so the wording can be checked without a device. */
data class MissionSnapshot(
    val repo: String,
    val stage: String,
    val costUsd: Double,
    val phase: Phase,
) {
    enum class Phase { RUNNING, PR_READY, APK_READY }

    fun line(): String = "$stage · ${Pricing.formatUsd(costUsd)}"
}

object MissionBus {
    @Volatile var stop: (() -> Unit)? = null
    @Volatile var merge: (() -> Unit)? = null
    @Volatile var review: (() -> Unit)? = null
    @Volatile var install: (() -> Unit)? = null
}

object CoderMissionNotification {
    const val CHANNEL = "coder_mission"
    const val NOTIFICATION_ID = 4201
    const val ACTION_STOP = "com.suarez.coder.mission.STOP"
    const val ACTION_MERGE = "com.suarez.coder.mission.MERGE"
    const val ACTION_REVIEW = "com.suarez.coder.mission.REVIEW"
    const val ACTION_INSTALL = "com.suarez.coder.mission.INSTALL"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Uzun görev", NotificationManager.IMPORTANCE_LOW))
    }

    fun build(ctx: Context, snap: MissionSnapshot): Notification {
        ensureChannel(ctx)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(snap.repo.ifBlank { "Coder" })
            .setContentText(snap.line())
            .setStyle(NotificationCompat.BigTextStyle().bigText(snap.line()))
            .setOngoing(snap.phase == MissionSnapshot.Phase.RUNNING)
            .setOnlyAlertOnce(true)
            .setContentIntent(activity(ctx))
        when (snap.phase) {
            MissionSnapshot.Phase.RUNNING -> b.addAction(0, "Durdur", broadcast(ctx, ACTION_STOP))
            MissionSnapshot.Phase.PR_READY -> {
                b.addAction(0, "Merge Et", broadcast(ctx, ACTION_MERGE))
                b.addAction(0, "İncele", broadcast(ctx, ACTION_REVIEW))
            }
            MissionSnapshot.Phase.APK_READY -> b.addAction(0, "APK Kur", broadcast(ctx, ACTION_INSTALL))
        }
        return b.build()
    }

    private fun activity(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun broadcast(ctx: Context, action: String): PendingIntent {
        val i = Intent(ctx, CoderMissionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(ctx, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class CoderMissionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val snap = MissionSnapshot(
            intent?.getStringExtra("repo").orEmpty(),
            intent?.getStringExtra("stage").orEmpty(),
            intent?.getDoubleExtra("cost", 0.0) ?: 0.0,
            runCatching { MissionSnapshot.Phase.valueOf(intent?.getStringExtra("phase") ?: "RUNNING") }
                .getOrDefault(MissionSnapshot.Phase.RUNNING),
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, CoderMissionNotification.NOTIFICATION_ID, CoderMissionNotification.build(this, snap), type)
        if (snap.phase != MissionSnapshot.Phase.RUNNING) stopForeground(STOP_FOREGROUND_DETACH)
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context, snap: MissionSnapshot) {
            val i = Intent(ctx, CoderMissionService::class.java)
                .putExtra("repo", snap.repo).putExtra("stage", snap.stage)
                .putExtra("cost", snap.costUsd).putExtra("phase", snap.phase.name)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, i) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, CoderMissionService::class.java)) }
        }
    }
}

class CoderMissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CoderMissionNotification.ACTION_STOP -> MissionBus.stop?.invoke()
            CoderMissionNotification.ACTION_MERGE -> MissionBus.merge?.invoke()
            CoderMissionNotification.ACTION_REVIEW -> MissionBus.review?.invoke()
            CoderMissionNotification.ACTION_INSTALL -> MissionBus.install?.invoke()
        }
    }
}
