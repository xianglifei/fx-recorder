package com.fx.recorder

import android.app.Application
import android.content.Context
import android.util.Log
import com.fx.recorder.store.AppDatabase
import com.fx.recorder.store.EventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class FxApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        importPendingCrashLogs()
    }

    /**
     * 崩溃时同步写文件（进程随时会死，不能用 DB/协程），下次启动导入 events 表。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val dir = File(filesDir, "crash").apply { mkdirs() }
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(
                    "thread=${t.name}\n${sw}")
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun importPendingCrashLogs() {
        val app = this
        appScope.launch {
            val dir = File(app.filesDir, "crash")
            val files = dir.listFiles().orEmpty().sortedBy { it.name }
            if (files.isEmpty()) return@launch
            val dao = AppDatabase.get(app).eventDao()
            for (f in files) {
                val detail = runCatching { f.readText().take(4000) }.getOrDefault("?")
                dao.insert(EventEntity(ts = System.currentTimeMillis(), kind = "crash", detail = detail))
                f.delete()
                Log.i("FxApp", "imported crash log ${f.name}")
            }
        }
    }
}
