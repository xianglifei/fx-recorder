package com.fx.recorder.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.fx.recorder.settings.AppSettings

/**
 * 心跳自愈：服务每 HEARTBEAT_MS 排一个 exact alarm；
 * 心跳触发时若发现服务不在运行（被系统回收/崩溃）且用户未手动停止，
 * 尝试重新拉起前台服务。
 */
object KeepAlive {
    const val HEARTBEAT_MS = 5L * 60 * 1000
    private const val TAG = "FxKeepAlive"

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = heartbeatPendingIntent(context)
        val at = System.currentTimeMillis() + HEARTBEAT_MS
        try {
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "exact alarm denied, fallback inexact", se)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(heartbeatPendingIntent(context))
    }

    private fun heartbeatPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, HeartbeatReceiver::class.java).setAction(HeartbeatReceiver.ACTION_HEARTBEAT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** 电量优化白名单检查结果（MIUI 还需自启动+省电无限制，见 App 内引导）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 心跳回调里的核心逻辑：需要且可行则拉起服务。 */
    fun onHeartbeat(context: Context, settings: AppSettings) {
        val wasActive = settings.recordingActive
        val alive = RecordingService.isRunning
        Log.d(TAG, "heartbeat: serviceRunning=$alive wasActive=$wasActive")
        if (!alive && wasActive && settings.crashRecovery) {
            try {
                context.startForegroundService(
                    Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START))
                Log.i(TAG, "heartbeat relaunched service")
            } catch (t: Throwable) {
                // Android 12+ 从后台启动 FGS 可能被系统拒绝（exact alarm 豁免在部分 ROM 缺失）
                Log.w(TAG, "heartbeat relaunch denied", t)
                Notifications.alert(context, "录音已中断，点开应用恢复录音")
            }
        }
    }
}
