package com.fx.recorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.fx.recorder.R
import com.fx.recorder.recorder.EngineRuntime
import com.fx.recorder.ui.MainActivity

object Notifications {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_ALERTS = "alerts"
    const val ID_STATUS = 1001
    const val ID_ALERT = 1002

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.notif_channel_status),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "常驻录音状态"
                setShowBadge(false)
            })
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.notif_channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "录音异常提醒"
            })
    }

    fun buildStatus(context: Context, runtime: EngineRuntime, windowMs: Long): Notification {
        val mm = (runtime.windowDurationMs / 1000) / 60
        val ss = (runtime.windowDurationMs / 1000) % 60
        val text = buildString {
            if (runtime.running) {
                append(context.getString(R.string.notif_recording_title))
                append(" · raw %02d:%02d/%dmin".format(mm, ss, windowMs / 60_000))
                if (runtime.meetingActive) {
                    val tm = (runtime.meetingDurationMs / 1000) / 60
                    append(" · 🎙会议 %02d:%02d".format(tm, (runtime.meetingDurationMs / 1000) % 60))
                } else if (runtime.topicActive) {
                    append(" · 话题 %02d:%02d".format(
                        (runtime.topicDurationMs / 1000) / 60, (runtime.topicDurationMs / 1000) % 60))
                } else {
                    append(" · 静默中")
                }
                append(" · %s".format(runtime.vadEngine))
            } else {
                append(context.getString(R.string.notif_idle_title))
            }
        }
        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // 会议打点：动态切换 开始/结束（与自动话题完全独立的两条时间线）
        val meetingAction = if (runtime.meetingActive) {
            context.getString(R.string.notif_action_meeting_end) to RecordingService.ACTION_MEETING_END
        } else {
            context.getString(R.string.notif_action_meeting_start) to RecordingService.ACTION_MEETING_START
        }
        val meetingIntent = PendingIntent.getService(
            context, 2,
            Intent(context, RecordingService::class.java).setAction(meetingAction.second),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(
            context, 1,
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("fx-recorder")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, meetingAction.first, meetingIntent)
            .addAction(0, context.getString(R.string.notif_action_stop), stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** Android 9 及以下没有按类型的 startForeground 重载。 */
    fun startForegroundTyped(service: android.app.Service, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            service.startForeground(ID_STATUS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            service.startForeground(ID_STATUS, notification)
        }
    }

    fun alert(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("fx-recorder")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(ID_ALERT, n)
    }
}
