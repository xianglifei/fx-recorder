package com.fx.recorder.settings

import android.content.Context
import android.content.SharedPreferences

/** 全部可调参数集中在此，默认值即 P1 需求文档推荐值。 */
class AppSettings(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("fx_recorder_settings", Context.MODE_PRIVATE)

    /** raw 底片切片时长，毫秒（10–60 分钟）。raw 永续全量保留，不做丢弃。 */
    var windowMs: Long
        get() = sp.getLong(KEY_WINDOW_MS, 1_800_000L)
        set(v) = sp.edit().putLong(KEY_WINDOW_MS, v.coerceIn(600_000L, 3_600_000L)).apply()

    /** AAC 码率 kbps，可选 32 / 48 / 64 */
    var bitrateKbps: Int
        get() = sp.getInt(KEY_BITRATE, 48)
        set(v) = sp.edit().putInt(KEY_BITRATE, if (v in intArrayOf(32, 48, 64)) v else 48).apply()

    /** 话题结束判定：连续静默超过该时长则关闭当前话题（60–600 秒）。 */
    var topicGapMs: Long
        get() = sp.getLong(KEY_TOPIC_GAP_MS, 300_000L)
        set(v) = sp.edit().putLong(KEY_TOPIC_GAP_MS, v.coerceIn(60_000L, 600_000L)).apply()

    /** 话题内累计人声低于该时长标记 low_value，不进默认导出（5–60 秒）。 */
    var topicMinVoiceMs: Long
        get() = sp.getLong(KEY_TOPIC_MIN_VOICE_MS, 20_000L)
        set(v) = sp.edit().putLong(KEY_TOPIC_MIN_VOICE_MS, v.coerceIn(5_000L, 60_000L)).apply()

    /** 话题开门门槛：连续人声达到该时长才开新话题（毫秒，固定 3s，预留可调） */
    var topicOnsetMs: Long
        get() = sp.getLong(KEY_TOPIC_ONSET_MS, 3_000L)
        set(v) = sp.edit().putLong(KEY_TOPIC_ONSET_MS, v.coerceIn(1_000L, 10_000L)).apply()

    /** 话题强切上限（毫秒，由 Whisper 25MB 反推 45min，预留可调） */
    var topicMaxMs: Long
        get() = sp.getLong(KEY_TOPIC_MAX_MS, 2_700_000L)
        set(v) = sp.edit().putLong(KEY_TOPIC_MAX_MS, v.coerceIn(1_800_000L, 3_600_000L)).apply()

    /** raw 文件保留时长（小时，24–168），到期删除音频、保留 DB 记录。 */
    var retentionHours: Int
        get() = sp.getInt(KEY_RETENTION_HOURS, 72)
        set(v) = sp.edit().putInt(KEY_RETENTION_HOURS, v.coerceIn(24, 168)).apply()

    /** App 目录存储配额（GB，1–20），超限从最老已 ack 文件开始删。 */
    var storageQuotaGb: Int
        get() = sp.getInt(KEY_QUOTA_GB, 5)
        set(v) = sp.edit().putInt(KEY_QUOTA_GB, v.coerceIn(1, 20)).apply()

    /** VAD 灵敏度：quiet=更灵敏 / normal / noisy=更迟钝（仅回退引擎 BandEnergyVad 使用） */
    var vadSensitivity: String
        get() = sp.getString(KEY_VAD_SENS, "normal") ?: "normal"
        set(v) = sp.edit().putString(KEY_VAD_SENS, v).apply()

    /** 手机重启后自动恢复录音 */
    var autoRestartOnBoot: Boolean
        get() = sp.getBoolean(KEY_BOOT_RESTART, false)
        set(v) = sp.edit().putBoolean(KEY_BOOT_RESTART, v).apply()

    /** 心跳自愈：进程被杀后尝试自动拉起 */
    var crashRecovery: Boolean
        get() = sp.getBoolean(KEY_CRASH_RECOVERY, true)
        set(v) = sp.edit().putBoolean(KEY_CRASH_RECOVERY, v).apply()

    /** 服务运行标志：由 RecordingService 维护；心跳/开机据此判断"应恢复"而非"从未开始"。 */
    var recordingActive: Boolean
        get() = sp.getBoolean(KEY_RECORDING_ACTIVE, false)
        set(v) = sp.edit().putBoolean(KEY_RECORDING_ACTIVE, v).apply()

    /**
     * 用户最近一次显式操作："stopped" 表示用户手动停止过录音，App 冷启动时不自动恢复；
     * 重新点「开始录音」后清除。全新安装为 "none"（首次打开即自动开录）。
     */
    var lastUserAction: String
        get() = sp.getString(KEY_LAST_USER_ACTION, "none") ?: "none"
        set(v) = sp.edit().putString(KEY_LAST_USER_ACTION, v).apply()

    fun vadMarginDb(): Double = when (vadSensitivity) {
        "quiet" -> 18.0
        "noisy" -> 8.0
        else -> 12.0
    }

    /** 局域网传输配对码（6 位数字）：getter 保证存在（无则生成）。 */
    fun ensurePairCode(): String {
        val cur = sp.getString(KEY_PAIR_CODE, null)
        if (cur != null && cur.length == 6) return cur
        val code = (100000..999999).random().toString()
        sp.edit().putString(KEY_PAIR_CODE, code).apply()
        return code
    }

    fun refreshPairCode(): String {
        val code = (100000..999999).random().toString()
        sp.edit().putString(KEY_PAIR_CODE, code).apply()
        return code
    }

    val pairCode: String get() = ensurePairCode()

    private companion object {
        const val KEY_WINDOW_MS = "window_ms"
        const val KEY_BITRATE = "bitrate_kbps"
        const val KEY_TOPIC_GAP_MS = "topic_gap_ms"
        const val KEY_TOPIC_MIN_VOICE_MS = "topic_min_voice_ms"
        const val KEY_TOPIC_ONSET_MS = "topic_onset_ms"
        const val KEY_TOPIC_MAX_MS = "topic_max_ms"
        const val KEY_RETENTION_HOURS = "retention_hours"
        const val KEY_QUOTA_GB = "storage_quota_gb"
        const val KEY_VAD_SENS = "vad_sensitivity"
        const val KEY_BOOT_RESTART = "auto_restart_on_boot"
        const val KEY_CRASH_RECOVERY = "crash_recovery"
        const val KEY_RECORDING_ACTIVE = "recording_active"
        const val KEY_LAST_USER_ACTION = "last_user_action"
        const val KEY_PAIR_CODE = "pair_code"
    }
}
