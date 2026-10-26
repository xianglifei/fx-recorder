package com.fx.recorder.store

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一个 raw 底片文件对应一条记录（P1-2 起 raw 层无条件保留）。
 * status: recording → sealed → cleaned（被存储循环删除，记录保留）
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,        // epoch ms，文件起始时间
    val endedAt: Long,
    val durationMs: Long,       // 窗口内实际音频时长
    val fileName: String,       // recordings/ 下文件名，不含扩展名
    val sizeBytes: Long,
    val voiceRatio: Double,     // 人声帧占比 0..1
    val avgDbfs: Double,        // 平均 RMS 电平 dBFS
    val status: String,
    val closeReason: String,    // window_end / stop / error
    val ackedAt: Long? = null,  // 电脑端确认接收时刻（P1-3 HTTP ack 写入；存储循环联动）
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val kind: String,           // crash / recover / mic_occupied / disk_low / boot_start / encode_fallback / audit_gap ...
    val detail: String,
)

/**
 * 手动会议标记（时间线与自动话题完全独立，都以绝对时间为锚）。
 * endMs=null 表示进行中；忘点结束时由下一次「会议开始」自动补齐
 * （endSource=auto_closed，补齐点=新开始点前最后一次人声时刻）。
 */
@Entity(tableName = "meetings")
data class MeetingEntity(
    @PrimaryKey val id: String,         // mtg_yyyyMMdd_HHmmss_xxx
    val startMs: Long,
    val endMs: Long?,                   // null = 进行中
    val endSource: String,              // manual / auto_closed / stop
    val voiceRatio: Double = 0.0,       // 会议区间人声占比（引擎顺带统计）
    val status: String,                 // open / closed / no_voice
    val note: String = "",              // 预留：Mac 端 LLM 写标题
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 话题索引（自动时间线，与手动会议线相互独立）：不产音频实体，
 * 只记录每场讨论的起止时刻，电脑端按索引从 raw 文件无重编码抽取实体文件。
 */
@Entity(tableName = "topics")
data class TopicEntity(
    @PrimaryKey val id: String,         // topic_yyyyMMdd_HHmmss_xxx
    val startMs: Long,                  // 含 30s 预录补偿（不早于上一话题结束）
    val endMs: Long,                    // 最后一次人声时刻（gap 关闭）或强制切点
    val durationMs: Long,
    val voiceRatio: Double,
    val speechMs: Long = 0,             // 累计人声时长（burst 统计）
    val burstCount: Int = 0,            // 发声片段数（听写=短而多、会议=长而少，供电脑端分类）
    val closeReason: String,            // gap / forced / manual / stop
    val status: String,                 // open / closed / low_value
    val createdAt: Long = System.currentTimeMillis(),
)
