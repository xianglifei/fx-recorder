package com.fx.recorder.recorder

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** 单文件写入器：采集线程逐块喂 PCM，结束时 finish()。实现保证线程内串行调用。 */
interface AudioFileWriter : Closeable {
    /** 写入一段 PCM（16bit little-endian 语义的 short 数组）。 */
    fun writePcm(frame: ShortArray, samples: Int)

    /** 正常收尾：冲刷编码器并关闭文件。失败时抛异常。 */
    fun finish()
}

/** AAC 不可用时的降级写入器：标准 44 字节头 WAV（PCM 16k/16bit/mono）。 */
class WavFileWriter(
    private val file: File,
    private val sampleRate: Int = 16_000,
    private val channels: Int = 1,
) : AudioFileWriter {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var dataBytes = 0L
    private var headerPatched = false

    init {
        out.write(ByteArray(HEADER_BYTES))   // 先占位，finish 时回写
    }

    override fun writePcm(frame: ShortArray, samples: Int) {
        val buf = ByteArray(samples * 2)
        var i = 0
        for (s in 0 until samples) {
            val v = frame[s].toInt()
            buf[i++] = (v and 0xFF).toByte()
            buf[i++] = ((v shr 8) and 0xFF).toByte()
        }
        out.write(buf)
        dataBytes += buf.size
    }

    override fun finish() {
        if (headerPatched) return
        headerPatched = true
        out.seek(0)
        val byteRate = sampleRate * channels * 2
        out.writeLE("RIFF", u32(36L + dataBytes), int8("WAVE"))
        out.writeLE("fmt ", u32(16L), u16(1), u16(channels),
            u32(sampleRate.toLong()), u32(byteRate.toLong()), u16(channels * 2), u16(16))
        out.writeLE("data", u32(dataBytes))
        out.close()
    }

    override fun close() {
        if (!headerPatched) runCatching { finish() }
    }

    // --- 小工具：按 little-endian 写头字段 ---
    private fun RandomAccessFile.writeLE(vararg parts: Any) {
        for (p in parts) when (p) {
            is String -> write(p.toByteArray(Charsets.US_ASCII))
            is ByteArray -> write(p)
        }
    }
    private fun int8(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun u32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(),
        (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte())
    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())

    companion object {
        const val TAG = "WavFileWriter"
        const val HEADER_BYTES = 44
    }
}
