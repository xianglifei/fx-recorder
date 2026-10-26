package com.fx.recorder.transfer

import android.util.Log
import com.fx.recorder.settings.AppSettings
import com.fx.recorder.store.AppDatabase
import com.fx.recorder.store.SessionRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 内嵌 HTTP 传输服务（P1-3）：
 * - JSON API：/api/files、/api/topics、/files/{id}、/api/ack（供 Mac 管线脚本）
 * - 网页：/ 与 /pair（供浏览器直接访问下载）
 * - 安全：6 位配对码换取会话 cookie；仅授权会话可访问数据
 *
 * ack 语义：Mac 端确认收到后文件标记 ackedAt，存储循环按"已 ack 24h / 未 ack 72h"清理。
 */
class HttpTransferServer(
    private val repo: SessionRepository,
    private val db: AppDatabase,
    private val settings: AppSettings,
    port: Int = pickFreePort(),
) : NanoHTTPD(port) {

    private val sessions = ConcurrentHashMap.newKeySet<String>()
    val actualPort: Int = port

    // 配对失败限速：连续错 5 次锁 10 分钟（防 6 位码暴力枚举；正常使用零感知）
    private var pairFails = 0
    private var pairLockedUntil = 0L
    private val pairLock = Any()

    companion object {
        private const val TAG = "FxHttp"

        /** 从 8080 起依次尝试 10 个端口，找到未被占用的。 */
        fun pickFreePort(): Int {
            for (p in 8080..8089) {
                try {
                    ServerSocket(p).use { return p }
                } catch (_: Exception) { /* occupied, try next */ }
            }
            return 0
        }
    }

    fun startServer(): Boolean = try {
        start(SOCKET_READ_TIMEOUT, false)
        Log.i(TAG, "transfer server on :$actualPort")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "transfer server start failed", t)
        false
    }

    // ------------------------------------------------------------- routing

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.trimEnd('/')
        return try {
            when {
                path == "" || path == "/" -> pageIndex(session)
                path == "/pair" && session.method == Method.POST -> pagePair(session)
                path == "/api/status" -> json_simple() // 健康检查（无敏感信息，不鉴权）
                !authorized(session) -> unauthorized()
                path == "/api/files" -> apiFiles()
                path == "/api/topics" -> apiTopics()
                path == "/api/meetings" -> apiMeetings()
                path.startsWith("/files/") -> apiDownload(path.removePrefix("/files/"))
                path == "/api/ack" && session.method == Method.POST -> apiAck(session)
                else -> notFound()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "serve error for $path", t)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error: ${t.message}")
        }
    }

    // -------------------------------------------------------------- auth

    private fun authorized(session: IHTTPSession): Boolean {
        val cookie = session.headers["cookie"] ?: return false
        val token = cookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("fxauth=") }?.removePrefix("fxauth=")
        return token != null && token in sessions
    }

    private fun grantSession(): String {
        val token = UUID.randomUUID().toString()
        sessions.add(token)
        // 会话数限制，防粗暴枚举
        if (sessions.size > 32) sessions.clear().also { sessions.add(token) }
        return token
    }

    private fun unauthorized(): Response =
        newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"error\":\"unauthorized\"}")

    private fun json_simple(): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json",
            "{\"app\":\"fx-recorder\",\"version\":\"${com.fx.recorder.BuildConfig.VERSION_NAME}\"}")

    // ---------------------------------------------------------------- api

    private fun apiFiles(): Response {
        val arr = JSONArray()
        runBlocking {
            // sealed + cleaned(acked) 都返回，脚本按 ackedAt 增量拉取
            for (e in db.sessionDao().sealedOldestFirst().sortedByDescending { it.startedAt }) {
                arr.put(JSONObject().apply {
                    put("id", e.id)
                    put("startedAtMs", e.startedAt)
                    put("endedAtMs", e.endedAt)
                    put("durationMs", e.durationMs)
                    put("sizeBytes", e.sizeBytes)
                    put("voiceRatio", e.voiceRatio)
                    put("status", e.status)
                    put("closeReason", e.closeReason)
                    put("ackedAtMs", e.ackedAt ?: JSONObject.NULL)
                    put("audio", "/files/${e.id}.m4a")
                    put("sidecar", "/files/${e.id}.json")
                })
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
    }

    private fun apiTopics(): Response {
        val arr = JSONArray()
        runBlocking {
            for (t in db.topicDao().closedOldestFirst().sortedByDescending { it.startMs }) {
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("startMs", t.startMs)
                    put("endMs", t.endMs)
                    put("durationMs", t.durationMs)
                    put("voiceRatio", t.voiceRatio)
                    put("speechMs", t.speechMs)
                    put("burstCount", t.burstCount)
                    put("avgBurstMs", if (t.burstCount > 0) t.speechMs / t.burstCount else 0)
                    put("closeReason", t.closeReason)
                    put("status", t.status)
                })
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
    }

    private fun apiMeetings(): Response {
        val arr = JSONArray()
        runBlocking {
            for (m in db.meetingDao().closedOldestFirst().sortedByDescending { it.startMs }) {
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("startMs", m.startMs)
                    put("endMs", m.endMs ?: JSONObject.NULL)
                    put("durationMs", (m.endMs ?: m.startMs) - m.startMs)
                    put("voiceRatio", m.voiceRatio)
                    put("endSource", m.endSource)      // manual / auto_closed / stop
                    put("status", m.status)            // closed / no_voice
                    put("note", m.note)
                })
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
    }

    private fun apiDownload(name: String): Response {
        if (!name.matches(Regex("[A-Za-z0-9_.-]+"))) return notFound()
        val f = File(repo.recordingsDir, name)
        if (!f.exists() || !f.isFile) return notFound()
        val mime = when {
            name.endsWith(".m4a") -> "audio/mp4"
            name.endsWith(".wav") -> "audio/wav"
            name.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
        return newChunkedResponse(Response.Status.OK, mime, FileInputStream(f)).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$name\"")
        }
    }

    private fun apiAck(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        if (body.isEmpty()) return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"body required\"}")
        val ids = JSONArray(body) // 期望: ["raw_20261026_..._XXXX", ...]
        var marked = 0
        runBlocking {
            val dao = db.sessionDao()
            for (i in 0 until ids.length()) {
                val id = ids.optString(i)
                if (id.isNotEmpty()) {
                    dao.markAcked(id, System.currentTimeMillis())
                    marked++
                }
            }
            if (marked > 0) repo.cleanupAfterFinalize()
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json",
            "{\"acked\":$marked}")
    }

    // --------------------------------------------------------------- pages

    private fun pageIndex(session: IHTTPSession): Response {
        return if (authorized(session)) renderMainPage() else renderPairPage()
    }

    private fun pagePair(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val code = session.parameters["code"]?.firstOrNull() ?: ""
        synchronized(pairLock) {
            if (System.currentTimeMillis() < pairLockedUntil) {
                val remainMin = (pairLockedUntil - System.currentTimeMillis()) / 60_000 + 1
                return renderPairPage("尝试次数过多，请 $remainMin 分钟后再试")
            }
            if (code == settings.pairCode) {
                pairFails = 0
            } else {
                pairFails++
                if (pairFails >= 5) {
                    pairLockedUntil = System.currentTimeMillis() + 10 * 60_000
                    pairFails = 0
                    Log.w(TAG, "pair locked for 10min after 5 failed attempts")
                    return renderPairPage("连续输错 5 次，已锁定 10 分钟")
                }
                return renderPairPage("配对码不正确（第 $pairFails/5 次）")
            }
        }
        val token = grantSession()
        return newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "").apply {
            addHeader("Set-Cookie", "fxauth=$token; Path=/; Max-Age=31536000; HttpOnly")
            addHeader("Location", "/")
        }
    }

    private fun renderPairPage(err: String? = null): Response {
        val html = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>fx-recorder</title><style>
        body{font-family:sans-serif;max-width:420px;margin:80px auto;padding:0 16px}
        input{font-size:24px;letter-spacing:8px;width:100%;padding:10px;text-align:center;box-sizing:border-box}
        button{margin-top:12px;width:100%;padding:12px;font-size:16px}
        .err{color:#c62828}
        </style></head><body>
        <h2>fx-recorder 配对</h2>
        <p>请输入手机 App 状态页显示的 6 位配对码：</p>
        <form method="post" action="/pair">
        <input name="code" maxlength="6" inputmode="numeric" autofocus>
        <button type="submit">配对</button>
        </form>
        ${if (err != null) "<p class='err'>$err</p>" else ""}
        </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
    }

    private fun renderMainPage(): Response {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        var meetingRows = ""
        var topicRows = ""
        var fileRows = ""
        var statLine = ""
        runBlocking {
            val meetings = db.meetingDao().closedOldestFirst().sortedByDescending { it.startMs }
            meetingRows = meetings.take(50).joinToString("") { m ->
                val dur = ((m.endMs ?: m.startMs) - m.startMs) / 1000
                """<tr><td>${fmt.format(Date(m.startMs))}</td><td>${dur}s</td>
                <td>${(m.voiceRatio * 100).toInt()}%</td><td>${m.endSource}</td>
                <td>${if (m.status == "no_voice") "<b style='color:#c62828'>无人声!</b>" else m.status}</td></tr>"""
            }
            val topics = db.topicDao().closedOldestFirst().sortedByDescending { it.startMs }
            topicRows = topics.take(100).joinToString("") { t ->
                """<tr><td>${fmt.format(Date(t.startMs))}</td><td>${t.durationMs / 1000}s</td>
                <td>${(t.voiceRatio * 100).toInt()}%</td><td>${if (t.burstCount > 0) t.speechMs / t.burstCount else 0}ms</td>
                <td>${t.closeReason}</td><td>${t.status}</td></tr>"""
            }
            val files = db.sessionDao().sealedOldestFirst().sortedByDescending { it.startedAt }
            fileRows = files.take(100).joinToString("") { e ->
                """<tr><td>${fmt.format(Date(e.startedAt))}</td><td>${e.durationMs / 60000}min</td>
                <td>${"%.1f".format(e.sizeBytes / 1048576.0)}MB</td>
                <td>${if (e.ackedAt != null) "已确认" else "-"}</td>
                <td><a href="/files/${e.id}.m4a">音频</a> <a href="/files/${e.id}.json">元数据</a></td></tr>"""
            }
            statLine = "会议 ${meetings.size} 场 · 话题 ${topics.size} 条 · 底片 ${files.size} 个"
        }
        val html = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>fx-recorder</title><style>
        body{font-family:sans-serif;max-width:900px;margin:24px auto;padding:0 16px;color:#222}
        h2{border-bottom:2px solid #0288d1;padding-bottom:6px}
        table{border-collapse:collapse;width:100%;font-size:14px}
        td,th{border-bottom:1px solid #ddd;padding:6px 8px;text-align:left}
        a{color:#0288d1}
        .meta{color:#666;font-size:13px;margin:4px 0 18px}
        </style></head><body>
        <h2>fx-recorder</h2>
        <p class="meta">$statLine · 手动会议线与自动话题线相互独立 · 平均发声片长 = 累计人声 / 发声片段数（听写短而碎、会议长而少）</p>
        <h3>手动会议</h3>
        <table><tr><th>开始</th><th>时长</th><th>人声</th><th>结束方式</th><th>状态</th></tr>$meetingRows</table>
        <h3>自动话题</h3>
        <table><tr><th>开始</th><th>时长</th><th>人声</th><th>平均片长</th><th>关闭原因</th><th>状态</th></tr>$topicRows</table>
        <h3>raw 底片文件</h3>
        <table><tr><th>开始</th><th>时长</th><th>大小</th><th>已确认</th><th>下载</th></tr>$fileRows</table>
        </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
}
