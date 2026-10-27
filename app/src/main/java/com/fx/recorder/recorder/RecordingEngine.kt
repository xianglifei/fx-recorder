package com.fx.recorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.fx.recorder.settings.AppSettings
import com.fx.recorder.store.SessionRepository
import com.fx.recorder.vad.BandEnergyVad
import com.fx.recorder.vad.VadEngine
import com.fx.recorder.vad.VadFactory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10

/** 引擎对外运行时状态（UI 与通知读取），由采集线程以约 320ms 周期刷新。 */
data class EngineRuntime(
    val running: Boolean = false,
    val vadEngine: String = "",
    val windowStartedAt: Long = 0L,
    val windowDurationMs: Long = 0L,      // 当前 raw 窗口已录时长
    val windowVoiceRatio: Double = 0.0,   // 当前 raw 窗口人声占比
    val currentDbfs: Float = -90f,
    val currentSpeech: Boolean = false,
    val topicActive: Boolean = false,
    val topicDurationMs: Long = 0L,       // 当前话题已持续时长
    val totalTopics: Int = 0,
    val meetingActive: Boolean = false,
    val meetingDurationMs: Long = 0L,     // 当前会议已持续时长
    val totalMeetings: Int = 0,
)

/**
 * 录音引擎（P1-2 双层架构）：
 *
 * - raw 层：采集永不断流，按 [AppSettings.windowMs]（默认 30 分钟）产出全量底片文件，无条件保留；
 * - topic 层：VAD 驱动的话题状态机只产索引（Room topics 表），不产音频实体。
 *   开门：连续人声 ≥ onset（3s）；关门：静默 ≥ gap（默认 5min）或达 45min 强切或手动切分；
 *   开门含 30s 预录补偿（索引起点提前，不早于上一话题结束）。
 *
 * 采集线程内串行完成 VAD / 状态机 / 编码喂入；DB 写为毫秒级且低频，同步执行
 * （runBlocking），不依赖外部协程作用域的生命周期。
 */
class RecordingEngine(
    private val context: Context,
    private val settings: AppSettings,
    private val repo: SessionRepository,
) {
    val runtime: StateFlow<EngineRuntime> = RecorderBus.runtime

    interface Callback {
        fun onMicRecovered(reason: String)
        fun onEngineError(t: Throwable)
        /** 会议打点结果反馈：kind=start/end/timeout；ok=false 表示无效操作（如无会议时点结束）。 */
        fun onMeetingMarked(kind: String, ok: Boolean, detail: String) {}
    }

    var callback: Callback? = null

    @Volatile private var running = false
    private var thread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var vad: VadEngine = BandEnergyVad(marginDb = settings.vadMarginDb())
    private var vadDegraded = false

    // ---- raw 窗口状态（仅采集线程访问） ----
    private var writer: AudioFileWriter? = null
    private var sessionId = ""
    private var windowStartedAt = 0L
    private var windowSamples = 0L
    private var windowFrames = 0L
    private var windowSpeechFrames = 0L

    // ---- 话题状态机（仅采集线程访问） ----
    private var topicActive = false
    private var topicId = ""
    private var topicStartMs = 0L
    private var topicLastVoiceMs = 0L
    private var topicFrames = 0L
    private var topicSpeechFrames = 0L
    private var topicBurstCount = 0            // 发声片段数（连续人声计 1 片）
    private var currentBurstFrames = 0         // 当前连续人声帧数
    private var onsetAccumMs = 0L          // SILENT 态连续人声累计
    private var silenceAccumMs = 0L        // ACTIVE 态连续静默累计
    private var prevTopicEndMs = 0L        // 0 = 录音开始以来还没有话题
    private var gapFrames = 0L             // 无话题期间帧统计（audit_gap 用）
    private var gapSpeechFrames = 0L
    private var topicsDone = 0
    private val pendingManualSplit = AtomicBoolean(false)

    // ---- 手动会议（时间线与自动话题完全独立） ----
    private var meetingOpen = false
    private var meetingId = ""
    private var meetingStartMs = 0L
    private var meetingFrames = 0L
    private var meetingSpeechFrames = 0L
    private var meetingsDone = 0
    private var meetingTimeoutAlerted = false
    private val pendingMeetingStart = AtomicBoolean(false)
    private val pendingMeetingEnd = AtomicBoolean(false)
    private val pendingExpedite = AtomicBoolean(false)
    private var globalLastVoiceMs = 0L          // 全局最后人声时刻（补齐忘点结束用）

    private val frameBuf = ShortArray(VAD_FRAME)

    @SuppressLint("MissingPermission") // RECORD_AUDIO 由服务启动前检查
    fun start(): Boolean {
        if (running) return true
        val (v, degraded) = VadFactory.create(context, settings)
        vad = v
        vadDegraded = degraded
        val ar = createAudioRecord() ?: return false
        try {
            ar.startRecording()
        } catch (t: Throwable) {
            runCatching { ar.release() }
            Log.e(TAG, "startRecording failed", t)
            return false
        }
        audioRecord = ar
        running = true
        if (degraded) runBlocking { repo.logEvent("vad_fallback", "silero init failed, using ${v.name}") }
        openWindow()
        thread = Thread({ loop() }, "fx-audio-loop").apply { start() }
        return true
    }

    /** 请求停止；当前 raw 窗口与话题在采集线程收尾后落库。 */
    fun stop() {
        running = false
        thread?.join(5_000)
        thread = null
        runCatching { vad.close() }
    }

    /** 手动切分：ACTIVE 态关闭当前话题（下一话题由 VAD 正常开门）；SILENT 态无操作。 */
    fun manualSplit() {
        pendingManualSplit.set(true)
    }

    /** 手动打点：标记会议开始（若上一场未点结束，先自动补齐）。 */
    fun meetingStart() {
        pendingMeetingStart.set(true)
    }

    /** 手动打点：标记会议结束。 */
    fun meetingEnd() {
        pendingMeetingEnd.set(true)
    }

    /**
     * 立即出稿（回溯性提交）：没打点但刚聊完的讨论想立刻拿文字稿——
     * 关闭当前话题（若开着）+ 无条件封盘当前 raw 窗口，音频即刻进入可拉取状态，
     * 配合 Mac 端 30s 探测实现 ≈30s 内开始转写。
     */
    fun expediteNow() {
        pendingManualSplit.set(true)
        pendingExpedite.set(true)
    }

    // ---------------------------------------------------------------- loop

    private fun loop() {
        val readBuf = ShortArray(READ_SAMPLES)
        var zeroRuns = 0
        try {
            while (running) {
                val ar = audioRecord ?: break
                val n = runCatching { ar.read(readBuf, 0, READ_SAMPLES) }.getOrElse { -1 }
                if (n <= 0) {
                    rebuildAudioRecord("read_fail")
                    continue
                }
                val allZero = processBlock(readBuf, n)

                // 麦克风被其他应用抢占的典型表现：持续读到数字静音
                zeroRuns = if (allZero && n == READ_SAMPLES) zeroRuns + 1 else 0
                if (zeroRuns >= SILENT_REBUILD_BLOCKS) {
                    zeroRuns = 0
                    rebuildAudioRecord("mic_silent")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "loop crashed", t)
            callback?.onEngineError(t)
        } finally {
            finalizeWindow("stop")
            closeTopic("stop")
            closeMeetingImpl(System.currentTimeMillis(), "stop")
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            audioRecord = null
            RecorderBus.publish(RecorderBus.runtime.value.copy(running = false))
        }
    }

    /** 处理一个读取块：VAD 分帧 → 话题状态机 → 编码写入 → raw 窗口翻转判定。返回是否全零。 */
    private fun processBlock(buf: ShortArray, n: Int): Boolean {
        if (pendingMeetingStart.compareAndSet(true, false)) meetingStartImpl()
        if (pendingMeetingEnd.compareAndSet(true, false)) meetingEndImpl()
        if (pendingExpedite.compareAndSet(true, false)) {
            // 立即出稿：manualSplit 已在下方 topicFrame 处理关闭话题，这里负责提前封盘
            if (running) rotateWindow("expedite")
        }

        var blockSumSq = 0.0
        var nonZero = false
        for (s in 0 until n) {
            val v = buf[s]
            if (v.toInt() != 0) nonZero = true
            blockSumSq += (v * v).toDouble()
        }
        val blockDbfs = (10 * log10((blockSumSq / n) / (32768.0 * 32768.0) + 1e-20)).toFloat()

        var speechFrames = 0
        var i = 0
        var lastSpeech = false
        val nowMs = System.currentTimeMillis()
        while (i + VAD_FRAME <= n) {
            System.arraycopy(buf, i, frameBuf, 0, VAD_FRAME)
            val speech = vad.process(frameBuf, VAD_FRAME)
            lastSpeech = speech
            if (speech) {
                speechFrames++
                globalLastVoiceMs = nowMs
            }
            if (meetingOpen) {
                meetingFrames++
                if (speech) meetingSpeechFrames++
            }
            topicFrame(speech)
            i += VAD_FRAME
        }
        windowFrames += i / VAD_FRAME
        windowSpeechFrames += speechFrames

        // 会议超时提醒（1 小时，单次）
        if (meetingOpen && !meetingTimeoutAlerted &&
            System.currentTimeMillis() - meetingStartMs >= MEETING_TIMEOUT_MS) {
            meetingTimeoutAlerted = true
            callback?.onMeetingMarked("timeout", true, "会议已超过 1 小时仍未标记结束，是不是忘了？")
        }

        windowSamples += n
        writer?.writePcm(buf, n)

        val durationMs = windowSamples * 1000L / SAMPLE_RATE
        RecorderBus.publish(EngineRuntime(
            running = true,
            vadEngine = vad.name,
            windowStartedAt = windowStartedAt,
            windowDurationMs = durationMs,
            windowVoiceRatio = if (windowFrames > 0) windowSpeechFrames.toDouble() / windowFrames else 0.0,
            currentDbfs = blockDbfs,
            currentSpeech = lastSpeech,
            topicActive = topicActive,
            topicDurationMs = if (topicActive) System.currentTimeMillis() - topicStartMs else 0L,
            totalTopics = topicsDone,
            meetingActive = meetingOpen,
            meetingDurationMs = if (meetingOpen) System.currentTimeMillis() - meetingStartMs else 0L,
            totalMeetings = meetingsDone,
        ))

        if (durationMs >= settings.windowMs) rotateWindow("window_end")
        return !nonZero
    }

    // ------------------------------------------------------------ meetings

    private fun meetingStartImpl() {
        val now = System.currentTimeMillis()
        var detail = "会议开始已标记"
        if (meetingOpen) {
            // 防呆：上一场忘点结束 → 自动补齐 = 新开始点前最后一次人声时刻
            val end = if (globalLastVoiceMs > meetingStartMs) globalLastVoiceMs else now
            closeMeetingImpl(end, "auto_closed")
            // 补齐的那场会议音频要立刻可拉取：翻转当前 raw 窗口提前封盘
            rotateWindow("meeting_end")
            detail = "上一场会议已自动结束（时长 ${(end - meetingStartMs) / 60000} 分钟），新会议开始"
        }
        meetingOpen = true
        meetingId = "mtg_" + repo.newSessionId(now)
        meetingStartMs = now
        meetingFrames = 0
        meetingSpeechFrames = 0
        meetingTimeoutAlerted = false
        val mid = meetingId
        runBlocking { repo.openMeeting(mid, now) }
        Log.i(TAG, "meeting start: $mid")
        callback?.onMeetingMarked("start", true, detail)
    }

    private fun meetingEndImpl() {
        if (!meetingOpen) {
            callback?.onMeetingMarked("end", false, "当前没有进行中的会议")
            return
        }
        val now = System.currentTimeMillis()
        closeMeetingImpl(now, "manual")
        // 低延迟出稿：会议结束即翻转 raw 窗口，音频立刻进入可拉取状态
        // （Mac 端 30s 轻探测发现新关闭的会议后即刻触发转写）
        rotateWindow("meeting_end")
        callback?.onMeetingMarked("end", true, "会议结束（时长 ${(now - meetingStartMs) / 60000} 分钟），已提交转写队列")
    }

    private fun closeMeetingImpl(endMs: Long, endSource: String) {
        if (!meetingOpen) return
        meetingOpen = false
        val speechMs = meetingSpeechFrames * BandEnergyVad.FRAME_MS
        val ratio = if (meetingFrames > 0) meetingSpeechFrames.toDouble() / meetingFrames else 0.0
        // 区间人声过少视为 no_voice（可能手机没录到 / 摆位问题），显式警示
        val status = if (speechMs < MEETING_NO_VOICE_MS) "no_voice" else "closed"
        meetingsDone++
        val mid = meetingId
        runBlocking { repo.closeMeeting(mid, endMs, endSource, ratio, status) }
        Log.i(TAG, "meeting close: $mid source=$endSource dur=${(endMs - meetingStartMs) / 1000}s voice=%.0f%%".format(Locale.US, ratio * 100))
        meetingId = ""
    }

    // -------------------------------------------------------------- topics

    /** 每帧（32ms）驱动话题状态机。 */
    private fun topicFrame(speech: Boolean) {
        if (pendingManualSplit.compareAndSet(true, false) && topicActive) {
            closeTopic("manual")
        }
        if (topicActive) {
            topicFrames++
            val now = System.currentTimeMillis()
            if (speech) {
                topicSpeechFrames++
                currentBurstFrames++
                topicLastVoiceMs = now
                silenceAccumMs = 0
            } else {
                silenceAccumMs += BandEnergyVad.FRAME_MS
                if (currentBurstFrames > 0) {
                    topicBurstCount++
                    currentBurstFrames = 0
                }
            }
            when {
                silenceAccumMs >= settings.topicGapMs -> closeTopic("gap")
                now - topicStartMs >= settings.topicMaxMs -> closeTopic("forced")
            }
        } else {
            gapFrames++
            if (speech) {
                gapSpeechFrames++
                onsetAccumMs += BandEnergyVad.FRAME_MS
            } else {
                onsetAccumMs = (onsetAccumMs - BandEnergyVad.FRAME_MS * 2).coerceAtLeast(0)
            }
            if (onsetAccumMs >= settings.topicOnsetMs) openTopic()
        }
    }

    private fun openTopic() {
        val now = System.currentTimeMillis()
        val floor = maxOf(prevTopicEndMs, windowStartedAt - 1)
        val start = maxOf(floor, now - PRE_ROLL_MS)

        // 遗漏审计：无话题覆盖的区间 >60s 时记录（区间内 VAD 人声占比供判断是真空隙还是疑似漏检）
        if (prevTopicEndMs > 0 && start - prevTopicEndMs > AUDIT_GAP_MIN_MS) {
            val ratio = if (gapFrames > 0) gapSpeechFrames.toDouble() / gapFrames else 0.0
            val detail = "durMs=${start - prevTopicEndMs} voiceRatio=%.3f".format(Locale.US, ratio)
            runBlocking { repo.logEvent("audit_gap", detail) }
        }

        topicActive = true
        topicId = "topic_" + repo.newSessionId(now)
        topicStartMs = start
        topicLastVoiceMs = now
        topicFrames = 0
        topicSpeechFrames = 0
        topicBurstCount = 0
        currentBurstFrames = 0
        silenceAccumMs = 0
        onsetAccumMs = 0
        gapFrames = 0
        gapSpeechFrames = 0
        val tid = topicId
        val st = start
        runBlocking { repo.openTopic(tid, st) }
        Log.i(TAG, "topic open: $tid at $start")
    }

    private fun closeTopic(reason: String) {
        if (!topicActive) return
        topicActive = false
        val end = if (reason == "gap") topicLastVoiceMs else System.currentTimeMillis()
        val duration = (end - topicStartMs).coerceAtLeast(0)
        val speechMs = topicSpeechFrames * BandEnergyVad.FRAME_MS
        val burstCount = topicBurstCount + if (currentBurstFrames > 0) 1 else 0
        val ratio = if (topicFrames > 0) topicSpeechFrames.toDouble() / topicFrames else 0.0
        val status = if (speechMs < settings.topicMinVoiceMs) "low_value" else "closed"
        prevTopicEndMs = end
        topicsDone++
        val tid = topicId
        val st = topicStartMs
        runBlocking { repo.closeTopic(tid, end, duration, ratio, reason, status, speechMs, burstCount) }
        Log.i(TAG, "topic close: $tid reason=$reason dur=${duration / 1000}s voice=%.0f%% bursts=$burstCount".format(Locale.US, ratio * 100))
        topicId = ""
        silenceAccumMs = 0
        onsetAccumMs = 0
        currentBurstFrames = 0
    }

    // ------------------------------------------------------------- windows

    private fun openWindow() {
        val now = System.currentTimeMillis()
        sessionId = "raw_" + repo.newSessionId(now)
        windowStartedAt = now
        windowSamples = 0
        windowFrames = 0
        windowSpeechFrames = 0
        val sid = sessionId
        val file = repo.audioFile(sid)
        writer = try {
            AacFileWriter(file, SAMPLE_RATE, settings.bitrateKbps * 1000).apply { start() }
        } catch (t: Throwable) {
            Log.w(TAG, "AAC unavailable, fallback to WAV", t)
            runBlocking { repo.logEvent("encode_fallback", t.message ?: "?") }
            WavFileWriter(file, SAMPLE_RATE)
        }
        val startedAt = now
        // DB 操作频率低且耗时毫秒级，采集线程内同步执行，避免依赖外部协程作用域的生命周期
        runBlocking { repo.openSession(sid, startedAt) }
    }

    private fun rotateWindow(reason: String) {
        finalizeWindow(reason)
        if (running || reason == "window_end") openWindow()
    }

    /** 关闭当前 raw 窗口：无条件 sealed（raw 是底片，永不丢弃）。 */
    private fun finalizeWindow(reason: String) {
        val w = writer ?: return
        writer = null
        val sid = sessionId
        val durationMs = windowSamples * 1000L / SAMPLE_RATE
        val ratio = if (windowFrames > 0) windowSpeechFrames.toDouble() / windowFrames else 0.0
        val avgDbfs = -1.0 // 块级电平已在 runtime 输出，sidecar 不再单独累计
        val finished = runCatching { w.finish() }.isSuccess
        runCatching { w.close() }

        if (durationMs < MIN_KEEP_MS) {
            // 过短窗口（如停止瞬间）无保留价值
            repo.audioFile(sid).delete()
            runBlocking {
                repo.finalizeSession(sid, System.currentTimeMillis(), durationMs, 0.0, avgDbfs, "${reason}_short")
            }
            return
        }
        if (!finished) runBlocking { repo.logEvent("encode_error", "finish failed for $sid") }
        runBlocking {
            repo.finalizeSession(sid, System.currentTimeMillis(), durationMs, ratio, avgDbfs, reason)
            repo.cleanupAfterFinalize()
        }
    }

    // ---------------------------------------------------------------- mic

    @SuppressLint("MissingPermission")
    private fun rebuildAudioRecord(reason: String) {
        Log.w(TAG, "rebuild AudioRecord: $reason")
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        Thread.sleep(1_000)
        val ar = createAudioRecord()
        if (ar == null) {
            runBlocking { repo.logEvent("mic_rebuild_fail", reason) }
            return
        }
        runCatching { ar.startRecording() }.onFailure {
            runCatching { ar.release() }
            runBlocking { repo.logEvent("mic_rebuild_fail", "$reason+start") }
            return
        }
        audioRecord = ar
        runBlocking { repo.logEvent("mic_rebuilt", reason) }
        callback?.onMicRecovered(reason)
    }

    private fun createAudioRecord(): AudioRecord? {
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val bufSize = maxOf(minBuf * 4, SAMPLE_RATE * 2 * 2)   // ≥2s 音频缓冲，兜住文件翻转
        return try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (t: Throwable) {
            Log.e(TAG, "createAudioRecord failed", t)
            null
        }
    }

    companion object {
        const val TAG = "FxEngine"
        const val SAMPLE_RATE = 16_000
        const val VAD_FRAME = 512                       // 32ms @16k（Silero 帧长）
        const val READ_SAMPLES = 5_120                  // 320ms = 16 帧
        const val SILENT_REBUILD_BLOCKS = 47            // 连续 ~15s 数字静音 → 重建
        const val MIN_KEEP_MS = 5_000L                  // 短于 5s 的 raw 窗口直接丢弃
        const val PRE_ROLL_MS = 30_000L                 // 话题开门预录补偿
        const val AUDIT_GAP_MIN_MS = 60_000L            // 无话题覆盖超 60s 记审计
        const val MEETING_TIMEOUT_MS = 60L * 60 * 1000  // 会议超时提醒阈值（1 小时）
        const val MEETING_NO_VOICE_MS = 10_000L         // 会议区间人声 <10s 判 no_voice
    }
}
