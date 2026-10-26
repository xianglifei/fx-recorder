package com.fx.recorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fx.recorder.recorder.RecorderBus
import com.fx.recorder.service.KeepAlive
import com.fx.recorder.service.RecordingService
import com.fx.recorder.settings.AppSettings
import com.fx.recorder.store.AppDatabase
import com.fx.recorder.store.SessionEntity
import com.fx.recorder.store.TopicEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    val settings = AppSettings(app)

    val runtime: StateFlow<com.fx.recorder.recorder.EngineRuntime> = RecorderBus.runtime

    val transfer: StateFlow<com.fx.recorder.recorder.TransferInfo?> = RecorderBus.transfer

    /** 局域网 IPv4（无 WiFi 时为 null）。 */
    fun lanIp(): String? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    fun refreshPairCode(): String {
        val code = settings.refreshPairCode()
        RecorderBus.publishTransfer(
            RecorderBus.transfer.value?.copy(pairCode = code))
        return code
    }

    val recentSessions: StateFlow<List<SessionEntity>> =
        db.sessionDao().observeRecent(200)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), emptyList())

    val recentTopics: StateFlow<List<TopicEntity>> =
        db.topicDao().observeRecent(200)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), emptyList())

    val recentMeetings: StateFlow<List<com.fx.recorder.store.MeetingEntity>> =
        db.meetingDao().observeRecent(50)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), emptyList())

    private fun todayStart(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val todaySessions: StateFlow<List<SessionEntity>> =
        db.sessionDao().observeSince(todayStart())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), emptyList())

    val recentEvents =
        db.eventDao().observeRecent(20)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), emptyList())

    val batteryOptimizationIgnored: Boolean
        get() = KeepAlive.isIgnoringBatteryOptimizations(getApplication())

    fun startRecording() {
        RecordingService.start(getApplication())
    }

    fun stopRecording() {
        RecordingService.stop(getApplication())
    }

    fun manualSplit() {
        RecordingService.manualSplit(getApplication())
    }

    fun meetingStart() {
        RecordingService.meetingStart(getApplication())
    }

    fun meetingEnd() {
        RecordingService.meetingEnd(getApplication())
    }

    // ------------------------------------------------------------- settings

    fun setWindowMinutes(min: Int) { settings.windowMs = min * 60_000L }
    fun setBitrate(kbps: Int) { settings.bitrateKbps = kbps }
    fun setVadSensitivity(v: String) { settings.vadSensitivity = v }
    fun setTopicGapMinutes(min: Int) { settings.topicGapMs = min * 60_000L }
    fun setTopicMinVoiceSec(sec: Int) { settings.topicMinVoiceMs = sec * 1_000L }
    fun setRetentionHours(h: Int) { settings.retentionHours = h }
    fun setQuotaGb(gb: Int) { settings.storageQuotaGb = gb }
    fun setBootRestart(v: Boolean) { settings.autoRestartOnBoot = v }
    fun setCrashRecovery(v: Boolean) { settings.crashRecovery = v }

    fun purgeOldEvents() {
        viewModelScope.launch {
            db.eventDao().purgeOlderThan(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
        }
    }
}
