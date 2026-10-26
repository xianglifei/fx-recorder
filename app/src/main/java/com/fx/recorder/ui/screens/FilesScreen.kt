package com.fx.recorder.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fx.recorder.store.SessionEntity
import com.fx.recorder.store.SessionRepository
import com.fx.recorder.store.TopicEntity
import com.fx.recorder.ui.Fmt
import com.fx.recorder.ui.MainViewModel

@Composable
fun FilesScreen(vm: MainViewModel) {
    val sessions by vm.recentSessions.collectAsState()
    val topics by vm.recentTopics.collectAsState()
    val meetings by vm.recentMeetings.collectAsState()
    val context = LocalContext.current
    val adbCmd = "adb pull /sdcard/Android/data/com.fx.recorder/files/recordings/ ."

    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("双层结构说明", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "raw_* 文件是全量底片（默认保留 72 小时）；话题只是切分索引，" +
                        "电脑端按索引从底片切出每场会议的实体文件。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(adbCmd, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("adb", adbCmd))
                }) { Text("复制导出命令") }
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (meetings.isNotEmpty()) {
                item {
                    Text("  手动会议（独立时间线）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
                items(meetings.take(50), key = { "m" + it.id }) { m -> MeetingRow(m) }
            }
            if (topics.isNotEmpty()) {
                item {
                    Text("  自动话题索引（最近 ${topics.size.coerceAtMost(50)} 条）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
                items(topics.take(50), key = { "t" + it.id }) { t -> TopicRow(t) }
                item {
                    Text("  raw 底片文件", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
            items(sessions, key = { it.id }) { s -> SessionRow(s) }
        }
    }
}

@Composable
private fun MeetingRow(m: com.fx.recorder.store.MeetingEntity) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                val dur = (m.endMs ?: m.startMs) - m.startMs
                Text("${Fmt.time(m.startMs)} → ${Fmt.time(m.endMs ?: 0)} · ${Fmt.duration(dur)}",
                    style = MaterialTheme.typography.bodyLarge)
                Text(
                    "人声 ${Fmt.pct(m.voiceRatio)} · ${meetingEndLabel(m.endSource)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(onClick = {}, label = { Text(meetingStatusLabel(m.status)) })
        }
    }
}

private fun meetingEndLabel(s: String): String = when (s) {
    "manual" -> "手动结束"
    "auto_closed" -> "自动补齐结束"
    "stop" -> "停止录音时补齐"
    else -> s
}

private fun meetingStatusLabel(s: String): String = when (s) {
    "open" -> "进行中"
    "closed" -> "已结束"
    "no_voice" -> "⚠无人声"
    else -> s
}

@Composable
private fun TopicRow(t: TopicEntity) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("${Fmt.time(t.startMs)} → ${Fmt.time(t.endMs)} · ${Fmt.duration(t.durationMs)}",
                    style = MaterialTheme.typography.bodyLarge)
                Text(
                    "人声 ${Fmt.pct(t.voiceRatio)} · ${closeReasonLabel(t.closeReason)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(onClick = {}, label = { Text(topicStatusLabel(t.status)) })
        }
    }
}

@Composable
private fun SessionRow(s: SessionEntity) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("${Fmt.time(s.startedAt)} · ${Fmt.duration(s.durationMs)}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${Fmt.size(s.sizeBytes)} · 人声 ${Fmt.pct(s.voiceRatio)} · ${s.closeReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(
                onClick = {},
                label = { Text(statusLabel(s.status)) },
            )
        }
    }
}

private fun closeReasonLabel(r: String): String = when (r) {
    "gap" -> "静默结束"
    "forced" -> "45min 强切"
    "manual" -> "手动切分"
    "stop" -> "停止"
    else -> r
}

private fun topicStatusLabel(s: String): String = when (s) {
    "open" -> "进行中"
    "closed" -> "已关闭"
    "low_value" -> "低价值"
    else -> s
}

private fun statusLabel(status: String): String = when (status) {
    SessionRepository.STATUS_SEALED -> "底片"
    SessionRepository.STATUS_DISCARDED -> "异常丢弃"
    SessionRepository.STATUS_CLEANED -> "已清理"
    SessionRepository.STATUS_RECORDING -> "写入中"
    else -> status
}
