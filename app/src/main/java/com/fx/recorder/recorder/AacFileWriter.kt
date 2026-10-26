package com.fx.recorder.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/**
 * 单文件 AAC-LC 编码器（MediaCodec）+ M4A 封装（MediaMuxer）。
 * 生命周期：start() → writePcm()*n → finish()。start 后所有方法须在同一线程序列调用。
 */
class AacFileWriter(
    private val file: File,
    private val sampleRate: Int = 16_000,
    private val bitrate: Int = 48_000,
) : AudioFileWriter {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var totalSamples = 0L
    private var finished = false

    fun start() {
        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    override fun writePcm(frame: ShortArray, samples: Int) {
        val c = codec ?: return
        var fed = 0
        while (fed < samples) {
            val idx = c.dequeueInputBuffer(FEED_TIMEOUT_US)
            if (idx < 0) {
                drainOutputs()
                continue
            }
            val buf = c.getInputBuffer(idx) ?: continue
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val capacitySamples = buf.capacity() / 2
            val chunk = minOf(capacitySamples, samples - fed)
            buf.clear()
            buf.asShortBuffer().put(frame, fed, chunk)
            c.queueInputBuffer(idx, 0, chunk * 2, ptsUs(), 0)
            totalSamples += chunk
            fed += chunk
            drainOutputs()
        }
    }

    override fun finish() {
        if (finished) return
        finished = true
        val c = codec
        try {
            if (c != null) {
                val idx = c.dequeueInputBuffer(50_000)
                if (idx >= 0) c.queueInputBuffer(idx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                val deadline = System.currentTimeMillis() + 2_000
                while (System.currentTimeMillis() < deadline) {
                    if (drainOutputs()) break
                    Thread.sleep(5)
                }
            }
        } finally {
            runCatching { muxer?.let { if (muxerStarted) it.stop() } }
            runCatching { muxer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            muxer = null
        }
    }

    override fun close() {
        if (!finished) runCatching { finish() }
    }

    /** drain 编码输出写入 muxer；返回是否收到 EOS。 */
    private fun drainOutputs(): Boolean {
        val c = codec ?: return true
        val m = muxer ?: return true
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val idx = c.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                else -> if (idx >= 0) {
                    val out = c.getOutputBuffer(idx)
                    if (out != null && info.size > 0 && muxerStarted && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        m.writeSampleData(trackIndex, out, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    c.releaseOutputBuffer(idx, false)
                    if (eos) return true
                }
            }
        }
    }

    private fun ptsUs(): Long = totalSamples * 1_000_000L / sampleRate

    companion object {
        private const val FEED_TIMEOUT_US = 10_000L
    }
}
