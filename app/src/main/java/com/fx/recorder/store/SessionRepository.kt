package com.fx.recorder.store

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.fx.recorder.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 负责音频文件与元数据的落盘、话题索引、sidecar 生成、存储循环（P1-5）。
 * 目录：[外置应用私有目录]/recordings（adb pull / HTTP 均友好，无需存储权限）。
 */
class SessionRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: AppSettings,
) {
    val recordingsDir: File = File(
        (context.getExternalFilesDir(null) ?: context.filesDir), "recordings"
    ).apply { mkdirs() }

    private val sessionDao get() = db.sessionDao()
    private val topicDao get() = db.topicDao()
    private val meetingDao get() = db.meetingDao()
    private val eventDao get() = db.eventDao()

    fun newSessionId(startedAt: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
        val suffix = (0..0xFFFF).random().toString(16).uppercase(Locale.US).padStart(4, '0')
        return "${stamp}_${suffix}"
    }

    fun audioFile(id: String): File = File(recordingsDir, "$id.m4a")

    // ------------------------------------------------------------- sessions

    suspend fun openSession(id: String, startedAt: Long): SessionEntity {
        val e = SessionEntity(
            id = id,
            startedAt = startedAt,
            endedAt = startedAt,
            durationMs = 0,
            fileName = id,
            sizeBytes = 0,
            voiceRatio = 0.0,
            avgDbfs = -120.0,
            status = STATUS_RECORDING,
            closeReason = "",
        )
        sessionDao.insert(e)
        return e
    }

    /** 关闭 raw 窗口：底片无条件 sealed（P1-2 起 raw 层不做丢弃）。 */
    suspend fun finalizeSession(
        id: String,
        endedAt: Long,
        durationMs: Long,
        voiceRatio: Double,
        avgDbfs: Double,
        closeReason: String,
    ): String = withContext(Dispatchers.IO) {
        val file = audioFile(id)
        val keep = file.exists() && file.length() > 0
        val status = if (keep) STATUS_SEALED else STATUS_DISCARDED
        val cur = sessionDao.byId(id)
        sessionDao.update(
            (cur ?: return@withContext status).copy(
                endedAt = endedAt,
                durationMs = durationMs,
                sizeBytes = if (keep) file.length() else 0,
                voiceRatio = voiceRatio,
                avgDbfs = avgDbfs,
                status = status,
                closeReason = closeReason,
            )
        )
        if (keep) writeSidecar(id) else {
            file.delete()
            File(recordingsDir, "$id.json").delete()
        }
        status
    }

    private suspend fun writeSidecar(id: String) {
        val e = sessionDao.byId(id) ?: return
        val json = JSONObject().apply {
            put("id", e.id)
            put("startedAtIso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ", Locale.US).format(Date(e.startedAt)))
            put("startedAtMs", e.startedAt)
            put("endedAtMs", e.endedAt)
            put("durationMs", e.durationMs)
            put("segmentMode", "raw")
            put("closeReason", e.closeReason)
            put("voiceRatio", e.voiceRatio)
            put("avgDbfs", e.avgDbfs)
            put("audio", JSONObject().apply {
                put("file", "${e.fileName}.m4a")
                put("container", "m4a")
                put("codec", "aac-lc")
                put("sampleRate", 16000)
                put("channels", 1)
                put("bitrateKbps", settings.bitrateKbps)
            })
            put("device", JSONObject().apply {
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("androidSdk", Build.VERSION.SDK_INT)
            })
        }
        runCatching {
            File(recordingsDir, "$id.json").writeText(json.toString(2))
        }.onFailure { Log.w(TAG, "sidecar write failed for $id", it) }
    }

    // --------------------------------------------------------------- topics

    /**
     * 孤儿回收：进程被杀（force-stop/崩溃）时正在写的窗口与开着的话题来不及收尾。
     * 服务启动时调用，按"文件在即保留"原则补记 sealed/closed，让 API 与存储循环可见。
     */
    suspend fun recoverOrphans() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var recovered = 0
        for (e in sessionDao.recordingSessions()) {
            val f = audioFile(e.id)
            val has = f.exists() && f.length() > 0
            sessionDao.update(e.copy(
                status = if (has) STATUS_SEALED else STATUS_DISCARDED,
                endedAt = now,
                durationMs = (now - e.startedAt).coerceAtLeast(0),
                sizeBytes = if (has) f.length() else 0,
                closeReason = "recovered",
            ))
            if (has) writeSidecar(e.id)
            recovered++
        }
        for (t in topicDao.openTopics()) {
            topicDao.update(t.copy(
                endMs = now,
                durationMs = (now - t.startMs).coerceAtLeast(0),
                closeReason = "recovered",
                status = "closed",
            ))
            recovered++
        }
        if (recovered > 0) logEvent("orphan_recovered", "count=$recovered")
        recovered
    }

    suspend fun openTopic(id: String, startMs: Long) {
        topicDao.insert(TopicEntity(
            id = id, startMs = startMs, endMs = startMs, durationMs = 0,
            voiceRatio = 0.0, closeReason = "", status = TOPIC_OPEN,
        ))
    }

    suspend fun closeTopic(id: String, endMs: Long, durationMs: Long, voiceRatio: Double,
                           closeReason: String, status: String,
                           speechMs: Long = 0, burstCount: Int = 0) {
        val t = topicDao.byId(id) ?: return
        topicDao.update(t.copy(endMs = endMs, durationMs = durationMs,
            voiceRatio = voiceRatio, closeReason = closeReason, status = status,
            speechMs = speechMs, burstCount = burstCount))
    }

    // ------------------------------------------------------------- meetings

    suspend fun openMeeting(id: String, startMs: Long) {
        meetingDao.insert(MeetingEntity(
            id = id, startMs = startMs, endMs = null,
            endSource = "", status = MEETING_OPEN,
        ))
    }

    suspend fun closeMeeting(id: String, endMs: Long, endSource: String,
                             voiceRatio: Double, status: String) {
        val m = meetingDao.byId(id) ?: return
        meetingDao.update(m.copy(endMs = endMs, endSource = endSource,
            voiceRatio = voiceRatio, status = status))
    }

    // --------------------------------------------------------- 存储循环 P1-5

    /**
     * 每次 raw 窗口关闭后调用。规则（任何情况下不删"未 ack 且未过保留期"的文件）：
     * 1) 时间过期：sealed 且 endedAt < now - retentionHours → 删；
     * 2) ack 联动：已 ack 且 endedAt < now - ACK_RETENTION_HOURS(24h) → 删；
     * 3) 容量配额：目录总大小 > quota → 按 endedAt 升序删（跳过未 ack 且未过期的）直到达标；
     * 4) 磁盘水位（最后防线）：分区可用 < 1GB 时同上清到 2GB，无可删则记 disk_full 事件。
     * 返回本次删除的文件数。
     */
    suspend fun cleanupAfterFinalize(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val retentionMs = settings.retentionHours * HOUR_MS
        val ackCutoff = now - ACK_RETENTION_HOURS * HOUR_MS
        var removed = 0

        // 1+2) 时间过期与 ack 过期
        for (e in sessionDao.sealedOldestFirst()) {
            val expired = e.endedAt < now - retentionMs
            val ackExpired = e.ackedAt != null && e.endedAt < ackCutoff
            if (expired || ackExpired) {
                if (deleteAudio(e)) removed++
            }
        }

        // 3) 容量配额
        val quotaBytes = settings.storageQuotaGb.toLong() * 1024 * 1024 * 1024
        var total = dirSizeBytes()
        if (total > quotaBytes) {
            for (e in sessionDao.sealedOldestFirst()) {
                if (total <= quotaBytes) break
                val protected = e.ackedAt == null && e.endedAt >= now - retentionMs
                if (protected) continue
                if (deleteAudio(e)) {
                    removed++
                    total = dirSizeBytes()
                }
            }
            if (dirSizeBytes() > quotaBytes) {
                eventDao.insert(EventEntity(ts = System.currentTimeMillis(), kind = "quota_pressure",
                    detail = "size=${dirSizeBytes()} quota=$quotaBytes"))
            }
        }

        // 4) 磁盘水位（最后防线）
        val stat = StatFs(recordingsDir.path)
        if (stat.availableBytes < MIN_FREE_BYTES) {
            eventDao.insert(EventEntity(ts = System.currentTimeMillis(), kind = "disk_low",
                detail = "availableBytes=${stat.availableBytes}"))
            for (e in sessionDao.sealedOldestFirst()) {
                if (StatFs(recordingsDir.path).availableBytes >= TARGET_FREE_BYTES) break
                val protected = e.ackedAt == null && e.endedAt >= now - retentionMs
                if (protected) continue
                if (deleteAudio(e)) removed++
            }
        }
        removed
    }

    private suspend fun deleteAudio(e: SessionEntity): Boolean {
        val gone = audioFile(e.id).delete()
        File(recordingsDir, "${e.id}.json").delete()
        sessionDao.setStatus(e.id, STATUS_CLEANED)
        return gone
    }

    private fun dirSizeBytes(): Long =
        recordingsDir.listFiles()?.sumOf { it.length() } ?: 0L

    suspend fun logEvent(kind: String, detail: String) {
        eventDao.insert(EventEntity(ts = System.currentTimeMillis(), kind = kind, detail = detail))
    }

    companion object {
        const val TAG = "FxRepo"
        const val STATUS_RECORDING = "recording"
        const val STATUS_SEALED = "sealed"
        const val STATUS_DISCARDED = "discarded"   // 仅异常场景（文件写失败）
        const val STATUS_CLEANED = "cleaned"
        const val TOPIC_OPEN = "open"
        const val TOPIC_CLOSED = "closed"
        const val TOPIC_LOW_VALUE = "low_value"
        const val MEETING_OPEN = "open"
        const val MEETING_CLOSED = "closed"
        const val MEETING_NO_VOICE = "no_voice"
        const val ACK_RETENTION_HOURS = 24
        const val MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024      // 1 GB
        const val TARGET_FREE_BYTES = 2L * 1024 * 1024 * 1024    // 清理到 2 GB 为止
        const val HOUR_MS = 3_600_000L
    }
}
