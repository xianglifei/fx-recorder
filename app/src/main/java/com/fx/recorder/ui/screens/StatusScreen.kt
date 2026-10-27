package com.fx.recorder.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.collectAsState
import com.fx.recorder.ui.Fmt
import com.fx.recorder.ui.MainViewModel
import com.fx.recorder.store.SessionRepository

@Composable
fun StatusScreen(vm: MainViewModel, permOk: Boolean, requestPerms: () -> Unit) {
    val context = LocalContext.current
    val runtime by vm.runtime.collectAsState()
    val today by vm.todaySessions.collectAsState()
    val topics by vm.recentTopics.collectAsState()
    val meetings by vm.recentMeetings.collectAsState()
    val events by vm.recentEvents.collectAsState()

    // 从系统设置页返回时刷新电池白名单状态
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshKey by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refreshKey++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val batteryOk = remember(refreshKey) { vm.batteryOptimizationIgnored }

    val sealed = today.filter { it.status == SessionRepository.STATUS_SEALED }
    val totalDur = sealed.sumOf { it.durationMs }
    val totalSize = sealed.sumOf { it.sizeBytes }
    val todayStart = remember { java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis }
    val topicsToday = topics.filter { it.startMs >= todayStart && it.status != "open" }
    val meetingsToday = meetings.filter { it.startMs >= todayStart && it.status != "open" }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 运行状态 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (runtime.running) "● 录音中" else "○ 已停止",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (runtime.running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("raw 底片：${Fmt.duration(runtime.windowDurationMs)} / ${vm.settings.windowMs / 60000} 分钟")
                val statusLine = when {
                    runtime.meetingActive -> "🎙 会议进行中：${Fmt.duration(runtime.meetingDurationMs)}"
                    runtime.topicActive -> "话题进行中：${Fmt.duration(runtime.topicDurationMs)}"
                    else -> "静默中（等待下一个话题）"
                }
                Text(
                    statusLine,
                    color = if (runtime.meetingActive || runtime.topicActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("VAD 引擎：${if (runtime.vadEngine.isEmpty()) "-" else runtime.vadEngine}",
                    style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { (runtime.windowDurationMs.toFloat() / vm.settings.windowMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (runtime.running) {
                        Button(
                            onClick = { if (runtime.meetingActive) vm.meetingEnd() else vm.meetingStart() },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (runtime.meetingActive) "会议结束" else "🎙 会议开始") }
                        OutlinedButton(onClick = { vm.expediteNow() }, modifier = Modifier.weight(1f)) {
                            Text("⚡ 立即出稿")
                        }
                    } else {
                        Button(
                            onClick = { if (permOk) vm.startRecording() else requestPerms() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("开始录音") }
                    }
                }
                Text(
                    "「立即出稿」：没打点但想立刻拿刚才这段的文字稿时点它\n「停止录音」已移至页面顶部（带确认）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 今日统计 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("今日", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("raw 文件 ${sealed.size} 个 · 时长 ${Fmt.duration(totalDur)} · ${Fmt.size(totalSize)}")
                if (meetingsToday.isNotEmpty()) {
                    Text("今日会议 ${meetingsToday.size} 场" +
                        meetingsToday.take(5).joinToString("") {
                            val noVoice = if (it.status == "no_voice") " · ⚠无人声" else ""
                            val src = if (it.endSource == "auto_closed") " · 自动补齐结束" else ""
                            "\n  🎙 ${Fmt.time(it.startMs)} · ${Fmt.duration((it.endMs ?: it.startMs) - it.startMs)}$noVoice$src"
                        })
                }
                Text("话题 ${topicsToday.size} 个" +
                    topicsToday.take(5).joinToString("") {
                        "\n  ${Fmt.time(it.startMs)} · ${Fmt.duration(it.durationMs)} · ${Fmt.pct(it.voiceRatio)}${if (it.status == "low_value") " · 低价值" else ""}"
                    })
            }
        }

        // ---- 局域网传输 ----
        val transfer by vm.transfer.collectAsState()
        val lanIp = remember(refreshKey) { vm.lanIp() }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("局域网传输", style = MaterialTheme.typography.titleMedium)
                if (transfer == null) {
                    Text("未运行（随录音服务启动）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (lanIp == null) {
                    Text("手机未连接局域网（检查 WiFi）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        "电脑浏览器/脚本访问：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "http://$lanIp:${transfer!!.port}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("配对码", style = MaterialTheme.typography.bodySmall)
                            Text(
                                transfer!!.pairCode,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                        OutlinedButton(onClick = { vm.refreshPairCode() }) { Text("刷新") }
                    }
                }
            }
        }

        // ---- 保活检查 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("保活检查", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        if (batteryOk) "✓ 电池优化白名单" else "✗ 未加入电池优化白名单",
                        modifier = Modifier.weight(1f),
                    )
                    if (!batteryOk) {
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")))
                            }
                        }) { Text("去设置") }
                    }
                }
                Text(
                    "MIUI 额外设置（手动一次即可）：\n" +
                        "1. 设置→应用管理→fx-recorder→自启动：开\n" +
                        "2. 设置→省电与电池→应用省电策略→fx-recorder→无限制\n" +
                        "3. 最近任务卡片下拉锁定 fx-recorder",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 最近事件（含 audit_gap 遗漏审计） ----
        if (events.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("最近事件", style = MaterialTheme.typography.titleMedium)
                    events.take(10).forEach {
                        Text(
                            "${Fmt.time(it.ts)}  ${it.kind}  ${it.detail.take(70)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
