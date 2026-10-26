package com.fx.recorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fx.recorder.ui.screens.FilesScreen
import com.fx.recorder.ui.screens.SettingsScreen
import com.fx.recorder.ui.screens.StatusScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
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

    // 打开即自动开录（常驻录音设备语义）：权限齐备、当前未在录、用户未显式停止过
    val runtime by vm.runtime.collectAsState()
    LaunchedEffect(permOk, runtime.running) {
        if (permOk == 1 && !runtime.running && vm.settings.lastUserAction != "stopped") {
            vm.startRecording()
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            TabButton("状态", tab == 0, Modifier.weight(1f)) { tab = 0 }
            TabButton("文件", tab == 1, Modifier.weight(1f)) { tab = 1 }
            TabButton("设置", tab == 2, Modifier.weight(1f)) { tab = 2 }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                0 -> StatusScreen(vm, permOk == 1) { permLauncher.launch(PERMISSIONS) }
                1 -> FilesScreen(vm)
                2 -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.padding(2.dp),
        colors = if (selected) ButtonDefaults.buttonColors()
        else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) { Text(label) }
}
