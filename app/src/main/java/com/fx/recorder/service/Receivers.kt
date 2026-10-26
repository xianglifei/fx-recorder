package com.fx.recorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fx.recorder.settings.AppSettings
import com.fx.recorder.store.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HEARTBEAT) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = AppSettings(app)
                KeepAlive.onHeartbeat(app, settings)
                // 心跳是单次 alarm：用户未手动停止则续排下一次，形成心跳链；
                // 停止后链条自然断掉，不会残留空转闹钟。
                if (settings.recordingActive) KeepAlive.schedule(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_HEARTBEAT = "com.fx.recorder.HEARTBEAT"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val settings = AppSettings(app)
        if (!settings.autoRestartOnBoot || !settings.recordingActive) return
        try {
            context.startForegroundService(
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START))
        } catch (t: Throwable) {
            Notifications.alert(app, "开机自动恢复录音被系统拦截，请手动打开应用")
        }
        // 记录事件
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppDatabase.get(app).eventDao().insert(
                    com.fx.recorder.store.EventEntity(ts = System.currentTimeMillis(), kind = "boot_start", detail = action))
            } finally {
                pending.finish()
            }
        }
    }
}
