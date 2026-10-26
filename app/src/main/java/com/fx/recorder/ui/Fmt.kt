package com.fx.recorder.ui

import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

object Fmt {
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    fun duration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun size(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun time(ts: Long): String = timeFmt.format(Date(ts))

    fun pct(ratio: Double): String = "%d%%".format((ratio * 100).toInt())
}
