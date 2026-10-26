package com.fx.recorder.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.fx.recorder.recorder.RecordingEngine
import com.fx.recorder.settings.AppSettings
import com.fx.recorder.store.AppDatabase
import com.fx.recorder.store.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 常驻录音前台服务（foregroundServiceType=microphone）。
 * 生命周期约定：
 * - ACTION_START：启动录音 + 心跳；settings.recordingActive=true（心跳据此判断应恢复）
 * - ACTION_STOP（用户手动停止）：停止录音 + 取消心跳；recordingActive=false，此后不再自动恢复
 * - 系统杀进程：心跳仍在，≤5 分钟内拉起（KeepAlive）
 */
class RecordingService : Service(), RecordingEngine.Callback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: AppSettings
    private lateinit var repo: SessionRepository
    private var engine: RecordingEngine? = null
    private var transferServer: com.fx.recorder.transfer.HttpTransferServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var userStopRequested = false

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        repo = SessionRepository(this, AppDatabase.get(this), settings)
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                userStopRequested = true
                settings.recordingActive = false
                settings.lastUserAction = "stopped"
                KeepAlive.cancel(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MANUAL_SPLIT -> {
                engine?.manualSplit()
                return START_STICKY
            }
            ACTION_MEETING_START -> {
                engine?.meetingStart()
                return START_STICKY
            }
            ACTION_MEETING_END -> {
                engine?.meetingEnd()
                return START_STICKY
            }
            else -> {
                userStopRequested = false
                settings.recordingActive = true
                settings.lastUserAction = "none"
                startRecording()
                return START_STICKY
            }
        }
    }

    private fun startRecording() {
        // 先上前台通知，再检查权限（无权限时录音引擎无法启动，通知会提示状态）
        Notifications.startForegroundTyped(this,
            Notifications.buildStatus(this, engine?.runtime?.value ?: com.fx.recorder.recorder.EngineRuntime(), settings.windowMs))
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO not granted, stop")
            Notifications.alert(this, "缺少麦克风权限，无法录音，请打开应用授权")
            stopSelf()
            return
        }
        if (engine?.runtime?.value?.running == true) {
            KeepAlive.schedule(this)
            return
        }
        acquireWakeLock()
        // 进程死亡（被杀/崩溃）遗留的 recording 窗口与 open 话题补记收尾
        runBlocking { repo.recoverOrphans() }
        val e = RecordingEngine(this, settings, repo).also { it.callback = this }
        if (!e.start()) {
            Log.e(TAG, "engine start failed")
            Notifications.alert(this, "录音引擎启动失败（麦克风被占用？），将在心跳中重试")
            KeepAlive.schedule(this)
            return
        }
        engine = e
        isRunning = true
        KeepAlive.schedule(this)
        startTransferServer()
        observeRuntime()
    }

    private fun observeRuntime() {
        val e = engine ?: return
        scope.launch {
            while (isActive && e === engine) {
                val rt = e.runtime.value
                val n = Notifications.buildStatus(this@RecordingService, rt, settings.windowMs)
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm?.notify(Notifications.ID_STATUS, n)
                delay(2_000)
            }
            Log.d(TAG, "runtime observer exit")
        }
    }

    private fun startTransferServer() {
        if (transferServer != null) return
        val server = com.fx.recorder.transfer.HttpTransferServer(
            repo, AppDatabase.get(this), settings)
        if (server.actualPort != 0 && server.startServer()) {
            transferServer = server
            com.fx.recorder.recorder.RecorderBus.publishTransfer(
                com.fx.recorder.recorder.TransferInfo(server.actualPort, settings.pairCode))
        }
    }

    override fun onDestroy() {
        engine?.callback = null
        engine?.stop()   // 同步收尾：finalize 当前窗口并落库
        engine = null
        runCatching { transferServer?.stop() }
        transferServer = null
        com.fx.recorder.recorder.RecorderBus.publishTransfer(null)
        releaseWakeLock()
        scope.cancel()
        if (!userStopRequested && settings.recordingActive) {
            // 异常死亡路径：靠心跳拉起，这里补排一次心跳缩短空窗
            KeepAlive.schedule(this)
        }
        Log.i(TAG, "service destroyed, userStop=$userStopRequested")
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------- engine callback

    override fun onMicRecovered(reason: String) {
        Notifications.alert(this, getString(com.fx.recorder.R.string.mic_occupied_alert))
    }

    override fun onEngineError(t: Throwable) {
        Notifications.alert(this, "录音引擎异常：${t.message}，将在心跳中恢复")
    }

    override fun onMeetingMarked(kind: String, ok: Boolean, detail: String) {
        if (!ok) {
            vibrate(2)
            return
        }
        vibrate(1)
        // 补齐提醒与超时提醒带信息量，发通知；普通成功仅振动
        if (kind == "timeout" || detail.contains("自动结束")) {
            Notifications.alert(this, detail)
        }
    }

    private fun vibrate(times: Int) {
        try {
            val vibrator: android.os.Vibrator? = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (getSystemService(android.os.VibratorManager::class.java))?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.os.Vibrator::class.java)
            }
            val effect = if (times <= 1) {
                android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            } else {
                android.os.VibrationEffect.createWaveform(longArrayOf(0, 60, 120, 60), -1)
            }
            vibrator?.vibrate(effect)
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ misc

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fx-recorder:record").apply {
            setReferenceCounted(false)
            // 常驻录音可能跨多天，无限期持有；onDestroy 必然 release
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        const val TAG = "FxService"
        const val ACTION_START = "com.fx.recorder.START"
        const val ACTION_STOP = "com.fx.recorder.STOP"
        const val ACTION_MANUAL_SPLIT = "com.fx.recorder.MANUAL_SPLIT"
        const val ACTION_MEETING_START = "com.fx.recorder.MEETING_START"
        const val ACTION_MEETING_END = "com.fx.recorder.MEETING_END"

        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: android.content.Context) {
            context.startForegroundService(
                Intent(context, RecordingService::class.java).setAction(ACTION_START))
        }

        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }

        fun manualSplit(context: android.content.Context) {
            runCatching {
                context.startService(
                    Intent(context, RecordingService::class.java).setAction(ACTION_MANUAL_SPLIT))
            }
        }

        fun meetingStart(context: android.content.Context) {
            runCatching {
                context.startService(
                    Intent(context, RecordingService::class.java).setAction(ACTION_MEETING_START))
            }
        }

        fun meetingEnd(context: android.content.Context) {
            runCatching {
                context.startService(
                    Intent(context, RecordingService::class.java).setAction(ACTION_MEETING_END))
            }
        }
    }
}
