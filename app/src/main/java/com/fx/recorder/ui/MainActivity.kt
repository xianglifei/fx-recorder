package com.fx.recorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fx.recorder.ui.screens.FilesScreen
import com.fx.recorder.ui.screens.SettingsScreen
import com.fx.recorder.ui.screens.StatusScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(context)
            else MaterialTheme.colorScheme
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    val vm: MainViewModel = viewModel()
                    AppRoot(vm)
                }
            }
        }
    }
}

private val PERMISSIONS: Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: MainViewModel) {
    val context = LocalContext.current
    var permOk by remember {
        mutableIntStateOf(if (PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) 1 else 0)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permOk = if (result.values.all { it }) 1 else 0
    }
    LaunchedEffect(permOk) {
        if (permOk == 0) permLauncher.launch(PERMISSIONS)
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showStopDialog by remember { mutableStateOf(false) }
    val runtime by vm.runtime.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("fx-recorder", fontWeight = FontWeight.Bold)
                        Row(Modifier.padding(top = 1.dp)) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(
                                        if (runtime.running) Color(0xFF2EAF54) else Color(0xFF9AA5B1),
                                        CircleShape,
                                    )
                            )
                            Text(
                                " " + if (runtime.running)
                                    (if (runtime.meetingActive) "会议进行中" else "录音中·静默")
                                else "已停止",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    // 停止：全 App 唯一能中断 24h 运转的操作——放顶栏 + 确认弹窗双保护
                    if (runtime.running) {
                        IconButton(onClick = { showStopDialog = true }) {
                            Icon(
                                Icons.Filled.StopCircle,
                                contentDescription = "停止录音",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(if (tab == 0) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, null) },
                    label = { Text("状态") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(if (tab == 1) Icons.Filled.Folder else Icons.Outlined.Folder, null) },
                    label = { Text("文件") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(if (tab == 2) Icons.Filled.Settings else Icons.Outlined.Settings, null) },
                    label = { Text("设置") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> StatusScreen(vm, permOk == 1) { permLauncher.launch(PERMISSIONS) }
                1 -> FilesScreen(vm)
                2 -> SettingsScreen(vm)
            }
        }
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("停止录音？") },
            text = { Text("停止后将不再录制，直到你重新点「开始录音」。\n（手动打点与自动话题都会停止）") },
            confirmButton = {
                TextButton(onClick = { showStopDialog = false; vm.stopRecording() }) {
                    Text("停止", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) { Text("继续录音") }
            },
        )
    }
}
