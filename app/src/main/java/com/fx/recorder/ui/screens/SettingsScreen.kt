package com.fx.recorder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fx.recorder.ui.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val runtime by vm.runtime.collectAsState()
    val s = vm.settings
    // 本地编辑态：提交即写入 SharedPreferences
    var windowMin by remember { mutableFloatStateOf((s.windowMs / 60_000f)) }
    var gapMin by remember { mutableFloatStateOf((s.topicGapMs / 60_000f)) }
    var minVoiceSec by remember { mutableFloatStateOf((s.topicMinVoiceMs / 1_000f)) }
    var retentionH by remember { mutableFloatStateOf(s.retentionHours.toFloat()) }
    var quotaGb by remember { mutableFloatStateOf(s.storageQuotaGb.toFloat()) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("参数自下一个切片文件起生效；VAD 灵敏度需重启录音生效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("raw 底片切片：%d 分钟".format(windowMin.toInt()), style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = windowMin,
                    onValueChange = { windowMin = (it / 5).let { w -> (w * 5).coerceAtLeast(10f) } },
                    onValueChangeFinished = { vm.setWindowMinutes(windowMin.toInt()) },
                    valueRange = 10f..60f,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("话题静默结束阈值：%d 分钟".format(gapMin.toInt()), style = MaterialTheme.typography.titleSmall)
                Text("静默超过该时长关闭当前话题（默认 5 分钟）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = gapMin,
                    onValueChange = { gapMin = it },
                    onValueChangeFinished = { vm.setTopicGapMinutes(gapMin.toInt()) },
                    valueRange = 1f..10f,
                )
                Spacer(Modifier.height(4.dp))
                Text("话题最短有效人声：%d 秒".format(minVoiceSec.toInt()), style = MaterialTheme.typography.titleSmall)
                Text("话题内人声不足该时长标记低价值，不进默认导出",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = minVoiceSec,
                    onValueChange = { minVoiceSec = it },
                    onValueChangeFinished = { vm.setTopicMinVoiceSec(minVoiceSec.toInt()) },
                    valueRange = 5f..60f,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("存储循环", style = MaterialTheme.typography.titleSmall)
                Text("已导出（ack）文件 24 小时后删除；未导出的保满保留期",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("保留期：%d 小时".format(retentionH.toInt()), style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = retentionH,
                    onValueChange = { retentionH = it },
                    onValueChangeFinished = { vm.setRetentionHours(retentionH.toInt()) },
                    valueRange = 24f..168f,
                )
                Spacer(Modifier.height(4.dp))
                Text("存储配额：%d GB".format(quotaGb.toInt()), style = MaterialTheme.typography.titleSmall)
                Text("超出后从最老的已导出文件开始清理（未导出且未过期的不删）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = quotaGb,
                    onValueChange = { quotaGb = it },
                    onValueChangeFinished = { vm.setQuotaGb(quotaGb.toInt()) },
                    valueRange = 1f..20f,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("编码码率（AAC-LC 16kHz 单声道）", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf(32, 48, 64).forEach { kbps ->
                        FilterChip(
                            selected = s.bitrateKbps == kbps,
                            onClick = { vm.setBitrate(kbps) },
                            label = { Text("$kbps kbps") },
                        )
                    }
                }
                Text("48 kbps：10 分钟 ≈ 3.6 MB，ASR 推荐",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("VAD 灵敏度（仅回退引擎生效，当前主引擎为 Silero）",
                    style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf("quiet" to "安静", "normal" to "标准", "noisy" to "嘈杂").forEach { (k, label) ->
                        FilterChip(
                            selected = s.vadSensitivity == k,
                            onClick = { vm.setVadSensitivity(k) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                SettingSwitch("开机自动恢复录音", "手机重启后自动开始录音", s.autoRestartOnBoot) { vm.setBootRestart(it) }
                SettingSwitch("被杀自动拉起", "心跳检测：进程被系统回收后 5 分钟内恢复录音", s.crashRecovery) { vm.setCrashRecovery(it) }
            }
        }

        Text(
            "录音格式：AAC-LC / 16kHz / 单声道 / M4A\n" +
                "VAD：${if (runtime.vadEngine.isEmpty()) "-" else runtime.vadEngine}\n" +
                "当前状态：${if (runtime.running) "录音中" else "已停止"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
