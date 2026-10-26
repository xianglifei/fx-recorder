package com.fx.recorder.vad

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 轻量语音活动检测（回退档）：能量滞回 + 低/高频带能量比 + 噪声底自适应 + 时长平滑。
 * P1 起 Silero VAD 为默认引擎，本类仅在 Silero 初始化失败时使用。
 * 数值路径统一用 Double，帧长 512 样本（32ms @16k）与 Silero 对齐。
 */
class BandEnergyVad(
    /** 噪声底之上的开门余量 dB；灵敏度 quiet=18.0 / normal=12.0 / noisy=8.0 */
    private val marginDb: Double,
) : VadEngine {
    override val name = "band-energy"
    override val frameSamples = FRAME_SAMPLES

    data class Frame(val speech: Boolean, val dbfs: Float)

    private var lpState = 0.0            // 一阶低通状态（截止≈1kHz）
    private var noiseDb = INITIAL_NOISE_DB
    private var inSpeech = false
    private var speechRun = 0
    private var silenceRun = 0
    private var lastDbfs = -90f

    /** 带电平输出的判定（引擎内部统计用）。 */
    fun processWithLevel(frame: ShortArray, length: Int): Frame {
        var totalEnergy = 0.0
        var lowEnergy = 0.0
        for (i in 0 until length) {
            val x = frame[i] / 32768.0
            totalEnergy += x * x
            lpState = LP_ALPHA * lpState + (1.0 - LP_ALPHA) * x
            lowEnergy += lpState * lpState
        }
        val rms = sqrt(totalEnergy / length)
        val dbfs = if (rms > 1e-9) (20.0 * log10(rms)).coerceAtLeast(FLOOR_DB) else FLOOR_DB
        lastDbfs = dbfs.toFloat()
        val lowRatio = if (totalEnergy > 1e-12) (lowEnergy / totalEnergy).coerceIn(0.0, 1.0) else 0.0

        // 噪声底跟踪：非语音期快速向下跟随，语音期缓慢衰减
        noiseDb = if (inSpeech) {
            0.999 * noiseDb + 0.001 * dbfs
        } else {
            0.95 * noiseDb + 0.05 * dbfs
        }.coerceAtLeast(FLOOR_DB)

        val openThreshold = maxOf(FIXED_OPEN_DB, noiseDb + marginDb)
        val closeThreshold = openThreshold - HYSTERESIS_DB
        val loudEnough = dbfs > (if (inSpeech) closeThreshold else openThreshold)
        val voiceLike = lowRatio >= MIN_LOW_RATIO   // 人声能量集中于低频带；键盘敲击/风噪偏宽带
        val rawVoice = loudEnough && voiceLike

        // 最短语音/静音时长平滑，过滤瞬态（一声咳嗽、一下敲击）
        if (inSpeech) {
            if (rawVoice) silenceRun = 0
            else if (++silenceRun >= MIN_SILENCE_FRAMES) { inSpeech = false; speechRun = 0 }
        } else {
            if (rawVoice) {
                if (++speechRun >= MIN_SPEECH_FRAMES) { inSpeech = true; silenceRun = 0 }
            } else speechRun = 0
        }
        return Frame(speech = inSpeech, dbfs = dbfs.toFloat())
    }

    override fun process(frame: ShortArray, length: Int): Boolean = processWithLevel(frame, length).speech
    override fun close() {}

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512               // 32ms @16k，与 Silero 帧长对齐
        const val FRAME_MS = 32L
        private const val LP_ALPHA = 0.675          // exp(-2π*1000/16000)
        private const val FIXED_OPEN_DB = -50.0
        private const val HYSTERESIS_DB = 6.0
        private const val MIN_LOW_RATIO = 0.35
        private const val MIN_SPEECH_FRAMES = 4     // ≈128ms
        private const val MIN_SILENCE_FRAMES = 7    // ≈224ms
        private const val INITIAL_NOISE_DB = -60.0
        private const val FLOOR_DB = -90.0
    }
}
