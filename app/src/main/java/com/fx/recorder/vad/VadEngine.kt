package com.fx.recorder.vad

import android.content.Context
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import com.konovalov.vad.silero.VadSilero

/** VAD 引擎统一接口：逐帧（512 样本 @16k）判定是否人声。实现自带平滑，输出为最终判定。 */
interface VadEngine {
    val name: String
    val frameSamples: Int

    fun process(frame: ShortArray, length: Int): Boolean
    fun close()
}

/** Silero VAD（DNN，P1 默认引擎）：键盘/稳态噪声区分度显著优于能量法。 */
class SileroVadEngine(context: Context) : VadEngine {
    override val name = "silero"
    override val frameSamples = FrameSize.FRAME_SIZE_512.value

    private val vad = VadSilero(
        context = context,
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_512,
        mode = Mode.NORMAL,
        silenceDurationMs = 300,
        speechDurationMs = 50,
    )

    // 复用缓冲，避免每帧分配
    private val byteBuf = ByteArray(frameSamples * 2)

    override fun process(frame: ShortArray, length: Int): Boolean {
        var i = 0
        for (s in 0 until length) {
            val v = frame[s].toInt()
            byteBuf[i++] = (v and 0xFF).toByte()
            byteBuf[i++] = ((v shr 8) and 0xFF).toByte()
        }
        return vad.isSpeech(byteBuf)
    }

    override fun close() {
        vad.close()
    }
}

object VadFactory {
    /**
     * Silero 优先；初始化失败（模型加载异常等）回退 BandEnergyVad。
     * 返回 Pair<引擎, 是否降级>。
     */
    fun create(context: Context, settings: com.fx.recorder.settings.AppSettings): Pair<VadEngine, Boolean> {
        return try {
            SileroVadEngine(context) to false
        } catch (t: Throwable) {
            BandEnergyVad(marginDb = settings.vadMarginDb()) to true
        }
    }
}
