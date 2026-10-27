#!/usr/bin/env python3
# fxsync.py — fx-recorder Mac 端管线核心（M-P1）
#
# 架构：无常驻进程，launchd 定时拉起；文件系统即状态；幂等（文件存在即跳过）。
# 命令：
#   once    跑一拍全流程（同步 → 下载 → ack → 切分 → 转写）
#   probe   轻探测（~30s 节奏）：发现 2 分钟内新关闭的会议则触发 once
#   status  输出状态摘要 JSON
#   config <ip> <pair_code>  写入配置
#   install 安装 launchd（5 分钟全量拍 + 30 秒探测拍）
#   uninstall 卸载 launchd

import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import urllib.parse
import http.cookiejar
from datetime import datetime
from pathlib import Path

HOME = Path.home()
DATA_DIR = HOME / "fx-recorder-data"
ARCHIVE = DATA_DIR / "archive"
STATE = DATA_DIR / "state.json"
CONFIG = DATA_DIR / "config.json"
LOGS = DATA_DIR / "logs"

# 转写环境（可被 config 覆盖）
DEFAULT_TRANSCRIBE_PY = "/Users/xianglifei/Code/Github/fx-flow/Podcast/.venv/bin/python3"
DEFAULT_TRANSCRIBE_SCRIPT = str(HOME / ".agents/skills/mm-asr/scripts/transcribe.py")


def log(msg):
    line = f"[{datetime.now().strftime('%m-%d %H:%M:%S')}] {msg}"
    print(line, flush=True)
    LOGS.mkdir(parents=True, exist_ok=True)
    with open(LOGS / "sync.log", "a") as f:
        f.write(line + "\n")


def load_config():
    if not CONFIG.exists():
        log(f"config 不存在：{CONFIG}，先运行 fxsync.py config <ip> <pair_code>")
        sys.exit(1)
    return json.loads(CONFIG.read_text())


class Phone:
    """手机 HTTP API 客户端：每次运行重新配对（对手机端 token 生命周期完全无感）。"""

    def __init__(self, cfg):
        self.base = f"http://{cfg['phone']}:{cfg.get('port', 8080)}"
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def _req(self, method, path, data=None, timeout=8, headers=None):
        req = urllib.request.Request(self.base + path, method=method,
                                     data=data.encode() if isinstance(data, str) else data)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        with self.opener.open(req, timeout=timeout) as resp:
            return resp.read()

    def ping(self):
        return json.loads(self._req("GET", "/api/status", timeout=4))

    def pair(self, code):
        body = urllib.parse.urlencode({"code": code})
        self._req("POST", "/pair", data=body,
                  headers={"Content-Type": "application/x-www-form-urlencoded"})

    def api(self, name):
        return json.loads(self._req("GET", f"/api/{name}"))

    def download(self, path, dest: Path):
        with self.opener.open(self.base + path, timeout=120) as resp, open(dest, "wb") as f:
            while True:
                chunk = resp.read(1 << 16)
                if not chunk:
                    break
                f.write(chunk)

    def ack(self, ids):
        self._req("POST", "/api/ack", data=json.dumps(ids),
                  headers={"Content-Type": "application/json"})


class AdbSource:
    """ADB 降级数据源（FR-1.2）：USB 常插场景下 HTTP 失联时的可靠兜底。
    索引 = run-as 拉手机 SQLite（topics/meetings/sessions）；下载 = adb pull。
    限制：无法 ack（记 pending_acks，HTTP 恢复后补交，期间手机按 72h 兜底保留）。"""

    def __init__(self, cfg):
        self.adb = cfg.get("adb_path", str(HOME / "Library/Android/sdk/platform-tools/adb"))
        self.tmp = DATA_DIR / ".adbtmp"

    def _shell(self, *args, timeout=60):
        r = subprocess.run([self.adb, *args], capture_output=True, timeout=timeout)
        if r.returncode != 0:
            raise RuntimeError(f"adb {args}: {r.stderr.decode()[:120]}")
        return r.stdout

    def _pull_db(self):
        self.tmp.mkdir(parents=True, exist_ok=True)
        import sqlite3
        for suffix in ("", "-wal", "-shm"):
            out = self._shell("exec-out", "run-as", "com.fx.recorder",
                              "cat", f"databases/fx-recorder.db{suffix}")
            (self.tmp / f"fx-recorder.db{suffix}").write_bytes(out)
        conn = sqlite3.connect(str(self.tmp / "fx-recorder.db"))
        return conn

    def load_all(self):
        """返回与 HTTP API 同构的 files/topics/meetings 列表。"""
        conn = self._pull_db()
        cur = conn.cursor()
        files = [{"id": r[0], "startedAtMs": r[1], "endedAtMs": r[2], "durationMs": r[3],
                  "sizeBytes": r[4], "status": r[5],
                  "ackedAtMs": r[6], "closeReason": r[7],
                  "audio": f"/files/{r[0]}.m4a", "sidecar": f"/files/{r[0]}.json"}
                 for r in cur.execute(
                     "SELECT id,startedAt,endedAt,durationMs,sizeBytes,status,ackedAt,closeReason "
                     "FROM sessions WHERE status='sealed' ORDER BY startedAt")]
        topics = [{"id": r[0], "startMs": r[1], "endMs": r[2], "durationMs": r[3],
                   "voiceRatio": r[4], "speechMs": r[5], "burstCount": r[6],
                   "closeReason": r[7], "status": r[8]}
                  for r in cur.execute(
                      "SELECT id,startMs,endMs,durationMs,voiceRatio,speechMs,burstCount,"
                      "closeReason,status FROM topics WHERE status!='open' ORDER BY startMs")]
        meetings = [{"id": r[0], "startMs": r[1], "endMs": r[2], "durationMs": r[3],
                     "voiceRatio": r[4], "endSource": r[5], "status": r[6], "note": r[7]}
                    for r in cur.execute(
                        "SELECT id,startMs,endMs,(endMs - startMs) AS durationMs,voiceRatio,"
                        "endSource,status,note "
                        "FROM meetings WHERE status!='open' AND endMs IS NOT NULL ORDER BY startMs")]
        conn.close()
        return files, topics, meetings

    def download(self, url_path, dest: Path):
        remote = f"/sdcard/Android/data/com.fx.recorder/files/recordings/{url_path.split('/')[-1]}"
        subprocess.run([self.adb, "pull", remote, str(dest)], check=True,
                       capture_output=True, timeout=300)


def build_daily_html(cfg, st):
    """静态滚动报告：每拍生成 ~/fx-recorder-data/今日.html，展示最近 24 小时的
    会议与话题（滚动窗口，不按自然日切分）。浏览器收藏一次即可；音频用相对路径
    可直接播放；转写稿折叠内嵌。"""
    import html as html_mod
    window_ms = 24 * 3600 * 1000
    cutoff = time.time() * 1000 - window_ms

    def rel(p):
        try:
            return str(Path(p).relative_to(DATA_DIR))
        except Exception:
            return ""

    def clip_card(it, kind_label):
        dur = it.get("durationMs", 0)
        voice = round((it.get("voiceRatio", 0) or 0) * 100)
        clip = it.get("clip") or ""
        transcript = it.get("transcript") or ""
        size_mb = "-"
        if clip:
            try:
                size_mb = f"{Path(clip).stat().st_size / 1048576:.1f} MB"
            except Exception:
                pass
        hhmm = datetime.fromtimestamp(it["startMs"] / 1000).strftime("%H:%M")
        audio_html = (f'<audio controls preload="none" style="width:100%;height:32px"'
                      f' src="{html_mod.escape(rel(clip))}"></audio>') if clip else ""
        body = ""
        if transcript:
            try:
                text = Path(transcript).read_text(errors="replace")
                fname = f"{Path(clip).stem}.md"
                body = (f'<details><summary>📖 展开转写稿（{len(text)} 字）</summary>'
                        f'<div class="ops">'
                        f'<button class="export-btn" data-fname="{html_mod.escape(fname)}">⬇ 导出 Markdown</button>'
                        f'<button class="copy-btn">复制全文</button>'
                        f'<span class="ok" hidden>已复制 ✓</span>'
                        f'</div>'
                        f'<pre style="white-space:pre-wrap">{html_mod.escape(text)}</pre></details>')
            except Exception as e:
                log(f"转写稿内嵌失败 {transcript}: {e}")
        badge = ('<span class="b mtg">🎙 会议</span>' if kind_label == "meeting"
                 else '<span class="b">话题</span>')
        status = it.get("status", "")
        low = ' <span class="b low">低价值</span>' if status == "low_value" else ""
        return f'''<div class="card">
  <div class="row1">{badge}{low}<b>{hhmm}</b> · {max(1, round(dur/60000))} 分钟 · {size_mb} · 人声 {voice}%</div>
  {audio_html}
  {body}
  <div class="path">{html_mod.escape(clip)}</div>
</div>'''

    items = []
    for m in st.get("meetings", []):
        if m["startMs"] >= cutoff:
            items.append((m["startMs"], clip_card(m, "meeting")))
    for t in st.get("topics", []):
        if not t.get("included_in_meeting") and t["startMs"] >= cutoff:
            items.append((t["startMs"], clip_card(t, "topic")))
    items.sort(key=lambda x: -x[0])

    n_meet = sum(1 for m in st.get("meetings", []) if m["startMs"] >= cutoff)
    n_topic = sum(1 for t in st.get("topics", [])
                  if not t.get("included_in_meeting") and t["startMs"] >= cutoff)
    n_trans = len([1 for _, c in items if "transcript" in c])
    since = datetime.fromtimestamp(cutoff / 1000).strftime("%m-%d %H:%M")

    page = f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<title>fx-recorder · 最近 24 小时</title><style>
*{{box-sizing:border-box;margin:0}} body{{font-family:-apple-system,"PingFang SC",sans-serif;
background:#f4f6f8;color:#1c2430;padding:24px 16px;line-height:1.6}}
.wrap{{max-width:820px;margin:0 auto}} h1{{font-size:20px}}
.sub{{color:#7a8899;font-size:13px;margin:2px 0 16px}}
.stats{{display:flex;gap:10px;margin-bottom:14px;flex-wrap:wrap}}
.stat{{flex:1;min-width:110px;background:#fff;border-radius:12px;padding:10px 14px;
box-shadow:0 1px 3px rgba(0,0,0,.06)}} .stat b{{display:block;font-size:18px}}
.stat span{{color:#7a8899;font-size:12px}}
.card{{background:#fff;border-radius:12px;padding:14px 18px;margin:10px 0;
box-shadow:0 1px 3px rgba(0,0,0,.06)}}
.row1{{font-size:14px;margin-bottom:6px}} .row1 b{{font-size:16px}}
.b{{display:inline-block;font-size:11px;padding:2px 8px;border-radius:10px;
background:#eef2f5;color:#5a6b7d;margin-right:6px}}
.b.mtg{{background:#e1f2fc;color:#0277bd}} .b.low{{background:#f0f0f0;color:#888}}
pre{{background:#f8fafc;border:1px solid #e8edf2;border-radius:8px;padding:12px;
font-size:13px;margin-top:8px;max-height:420px;overflow:auto}}
details summary{{cursor:pointer;color:#0288d1;font-size:13px}}
.ops{{margin:8px 0 4px}}
.ops button{{border:0;border-radius:8px;padding:6px 14px;font-size:13px;cursor:pointer;
background:#0288d1;color:#fff;margin-right:8px}}
.ops button:last-of-type{{background:#e8edf2;color:#1c2430}}
.ops .ok{{color:#2e7d46;font-size:12px}}
.path{{color:#b0bcc9;font-size:11px;margin-top:6px;word-break:break-all}}
.empty{{padding:60px;text-align:center;color:#93a1b0}}
</style></head><body><div class="wrap">
<h1>🎙 fx-recorder · 最近 24 小时</h1>
<div class="sub">窗口：{since} 至今（滚动） · 管线每 5 分钟自动更新 · 浏览器刷新查看最新 · 更早记录见 archive/</div>
<div class="stats">
  <div class="stat"><span>会议（手动打点）</span><b>{n_meet}</b></div>
  <div class="stat"><span>话题（自动检测）</span><b>{n_topic}</b></div>
  <div class="stat"><span>已转写</span><b>{n_trans}</b></div>
</div>
{''.join(c for _, c in items) if items else '<div class="card empty">最近 24 小时内没有会议或话题</div>'}
</div>
'''
    page += '''
<script>
function exportMd(btn, fname, text) {
  const blob = new Blob(["# " + fname.replace(/\\.md$/, "") + "\\n\\n" + text],
                        {type: "text/markdown;charset=utf-8"});
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = fname;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
}
// 事件委托：按钮不内联参数（data 属性），规避引号嵌套问题
document.addEventListener("click", (e) => {
  const ex = e.target.closest(".export-btn");
  if (ex) {
    const pre = ex.closest("details").querySelector("pre");
    exportMd(ex, ex.dataset.fname, pre.textContent);
    return;
  }
  const cp = e.target.closest(".copy-btn");
  if (cp) {
    const pre = cp.closest("details").querySelector("pre");
    const ta = document.createElement("textarea");
    ta.value = pre.textContent; document.body.appendChild(ta);
    ta.select(); document.execCommand("copy"); ta.remove();
    const ok = cp.parentElement.querySelector(".ok");
    ok.hidden = false; setTimeout(() => ok.hidden = true, 1500);
  }
});
</script>
</body></html>'''
    out = DATA_DIR / "今日.html"
    out.write_text(page)
    return out


def cleanup_mac(cfg):
    """FR-5.4 Mac 端存储循环：音频按保留期自动清理（默认 7 天，用户拍板：
    手机 72h + Mac 7d 两级保留），转写稿(.md/.segments.json)永久保留——
    数据价值在文字，音频只是原料。
    磁盘水位兜底：<10GB 时提前清理最老的音频目录直到 >15GB。"""
    import shutil, re
    retention = cfg.get("mac_retention_days", 7)
    if not ARCHIVE.exists():
        return
    from datetime import timedelta
    cutoff_date = datetime.now() - timedelta(days=retention)
    freed = 0
    for d in sorted(ARCHIVE.iterdir()):
        if not d.is_dir() or not re.match(r"\d{4}-\d{2}-\d{2}", d.name):
            continue
        try:
            day = datetime.strptime(d.name, "%Y-%m-%d")
        except ValueError:
            continue
        if day >= cutoff_date:
            continue
        # 删除音频目录，保留 transcripts/
        for sub in ("raw", "meetings", "topics"):
            p = d / sub
            if p.exists():
                size = sum(f.stat().st_size for f in p.rglob("*") if f.is_file())
                shutil.rmtree(p)
                freed += size
        # transcripts 里清理对应音频引用无意义，只清空的中间文件
        for f in (d / "transcripts").glob("*.transcript.txt"):
            f.unlink()
    if freed > 0:
        gb = freed / 1e9
        log(f"Mac 清理：删除 {retention} 天前的音频 {gb:.2f} GB（转写稿已保留）")
        notify_mac("fx-recorder", f"已自动清理 {gb:.1f}GB 过期音频（转写稿保留）")
    # 磁盘水位兜底（<10GB 清到 >15GB，从最老日期开始）
    def disk_free_gb():
        r = subprocess.run(["df", "-g", "/"], capture_output=True, text=True)
        return int(r.stdout.splitlines()[1].split()[3])
    if disk_free_gb() < 10:
        for d in sorted(ARCHIVE.iterdir()):
            if disk_free_gb() >= 15:
                break
            if not d.is_dir():
                continue
            size = sum(f.stat().st_size for f in d.rglob("*.m4a") if f.is_file())
            for sub in ("raw", "meetings", "topics"):
                p = d / sub
                if p.exists():
                    shutil.rmtree(p)
            if size > 0:
                log(f"磁盘水位清理: {d.name} 释放 {size/1e9:.2f} GB（保留转写稿）")
        notify_mac("fx-recorder", "Mac 磁盘紧张，已提前清理最老的音频（转写稿保留）")


# ------------------------------------------------------------ 幻觉清理

# ASR 静默段复读的典型形态：事件标签复读（[SPK][SPK]...）、短语复读（"我查一下，"×N）、
# 单字/数字复读（"正号正号..."、"5 5 5..."）。以下规则只折叠"病态重复"（阈值保守，
# 允许真实口头重复 ≤3 次），不改写任何正常内容。
_RE_TAG_RUN = re.compile(r'(\[[A-Za-z]+\][，。\s]*){4,}')
_RE_TOKEN_RUN = re.compile(r'(.{1,24}?)\1{3,}')          # 连续复读（含标点/周期循环，中文友好）
_RE_WORD_RUN = re.compile(r'(\b\S{1,8}\b)([，,、\s]\1){3,}')  # 带分隔的词复读


def clean_transcript(text: str) -> str:
    out_lines, prev, run = [], None, 0
    for ln in text.splitlines():
        s = ln.strip()
        if not s:
            continue
        if s == prev:
            run += 1
            if run < 3:                # 整行完全相同最多保留 2 次
                out_lines.append(ln)
        else:
            prev, run = s, 0
            out_lines.append(ln)
    text = "\n".join(out_lines)
    text = _RE_TAG_RUN.sub('', text)
    text = _RE_TOKEN_RUN.sub(r'\1', text)
    text = _RE_WORD_RUN.sub(r'\1', text)
    text = re.sub(r'\n{3,}', '\n\n', text)
    return text.strip()


def reclean_all():
    """清理存量转写稿（transcripts/*.md 的正文部分，头部元信息保留）。"""
    total_before = total_after = 0
    files = 0
    for md in ARCHIVE.glob("*/transcripts/*.md"):
        raw = md.read_text(errors="replace")
        sep = "\n---\n\n"
        if sep in raw:
            head, body = raw.split(sep, 1)
        else:
            head, body = "", raw
        cleaned = clean_transcript(body)
        if cleaned != body:
            md.write_text(head + sep + cleaned + "\n" if head else cleaned + "\n")
        total_before += len(body)
        total_after += len(cleaned)
        files += 1
    log(f"存量清理: {files} 篇，{total_before} → {total_after} 字符"
        f"（压缩 {100 - total_after * 100 // max(1, total_before)}%）")
    return files


def load_state():
    if STATE.exists():
        return json.loads(STATE.read_text())
    return {"last_sync": 0, "phone_ok": False, "meetings": [], "topics": [], "raw": [], "errors": []}


def save_state(st):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(st, ensure_ascii=False, indent=1))


def day_dir(ts_ms):
    return ARCHIVE / datetime.fromtimestamp(ts_ms / 1000).strftime("%Y-%m-%d")


def notify_mac(title, msg):
    subprocess.run(["osascript", "-e",
                    f'display notification "{msg}" with title "{title}"'],
                   capture_output=True)


def sync_once(cfg):
    st = load_state()
    phone = Phone(cfg)
    now = int(time.time() * 1000)
    files = topics = meetings = None
    http_ok = False
    try:
        phone.ping()
        phone.pair(cfg["pair_code"])
        http_ok = True
    except Exception as e:
        # FR-1.2 ADB 降级：HTTP 失联时经 USB 拉取（无法 ack，记 pending）
        try:
            files, topics, meetings = AdbSource(cfg).load_all()
            log("HTTP 不可达，已切换 ADB 通道拉取索引")
        except Exception as ae:
            st["phone_ok"] = False
            # FR-1.4 离线告警：>30 分钟不可达发一次 macOS 通知（成功后重置，可再次告警）
            if now - st.get("last_success_ts", now) > 30 * 60_000 and not st.get("offline_alerted"):
                notify_mac("fx-recorder", "手机超过 30 分钟不可达（HTTP 与 ADB 均失败）")
                st["offline_alerted"] = True
                log("已发送离线告警通知（>30min 不可达）")
            st["errors"] = ([f"phone unreachable: {e}"] + st.get("errors", []))[:20]
            save_state(st)
            log(f"手机不可达（HTTP+ADB）: {e} / {ae}")
            return 1

    st["phone_ok"] = True
    st["last_success_ts"] = now
    st["offline_alerted"] = False

    # HTTP 通道可用时：先补交历史 pending ack，再拉索引
    if http_ok:
        pending = st.get("pending_acks", [])
        if pending:
            try:
                phone.ack(pending)
                log(f"补交 ADB 时期的 ack {len(pending)} 个")
                st["pending_acks"] = []
            except Exception:
                pass
        files = phone.api("files")
        topics = phone.api("topics")
        meetings = phone.api("meetings")

    # 下载通道随索引来源走
    adb = None if http_ok else AdbSource(cfg)
    acked = []
    for f in files:
        if f.get("ackedAtMs") is not None or f["status"] not in ("sealed", "cleaned"):
            continue
        d = day_dir(f["startedAtMs"]); (d / "raw").mkdir(parents=True, exist_ok=True)
        m4a = d / "raw" / f"{f['id']}.m4a"
        js = d / "raw" / f"{f['id']}.json"
        try:
            if not m4a.exists():
                if http_ok:
                    phone.download(f["audio"], m4a)
                    phone.download(f["sidecar"], js)
                else:
                    adb.download(f["audio"], m4a)
                    adb.download(f["sidecar"], js)
            if m4a.exists() and (f["sizeBytes"] == 0 or m4a.stat().st_size == f["sizeBytes"]):
                acked.append(f["id"])
        except Exception as e:
            log(f"下载失败 {f['id']}: {e}")
    if acked:
        if http_ok:
            phone.ack(acked)
            log(f"下载并 ack {len(acked)} 个文件")
        else:
            st["pending_acks"] = list(set(st.get("pending_acks", []) + acked))
            log(f"ADB 下载 {len(acked)} 个文件（ack 待 HTTP 恢复后补交）")

    # 索引快照
    st["meetings"] = meetings
    st["topics"] = topics
    st["raw"] = [{"id": f["id"], "startedAtMs": f["startedAtMs"], "endedAtMs": f["endedAtMs"],
                  "status": f["status"], "sizeBytes": f["sizeBytes"]} for f in files]
    st["last_sync"] = int(time.time() * 1000)
    save_state(st)

    # 切分 + 转写
    segment_all(st)
    transcribe_all(cfg, st)
    # 静态日报（今日.html）
    try:
        build_daily_html(cfg, st)
    except Exception as e:
        log(f"日报生成异常: {e}")
    # Mac 端存储循环（音频到期/磁盘水位）
    try:
        cleanup_mac(cfg)
    except Exception as e:
        log(f"Mac 清理异常: {e}")
    return 0


# ------------------------------------------------------------------ 切分

def _raw_local_path(r):
    return day_dir(r["startedAtMs"]) / "raw" / f"{r['id']}.m4a"


def cut_clip(src: Path, start_s: float, end_s: float, out: Path):
    """无损切一段（-c copy）。"""
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-ss", f"{start_s:.3f}",
                    "-to", f"{end_s:.3f}", "-i", str(src), "-c", "copy", str(out)],
                   check=True, capture_output=True)


def concat_clips(parts, out: Path):
    if len(parts) == 1:
        parts[0].rename(out)
        return
    lst = out.parent / f".{out.stem}_concat.txt"
    lst.write_text("".join(f"file '{p}'\n" for p in parts))
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0",
                    "-i", str(lst), "-c", "copy", str(out)], check=True, capture_output=True)
    lst.unlink()
    for p in parts:
        p.unlink()


def cut_interval(state, start_ms, end_ms, out: Path):
    """按绝对时间区间从 raw 底片切出实体文件（支持跨多个底片）。"""
    parts = []
    for r in state.get("raw", []):
        rs, re_ = r["startedAtMs"], r["endedAtMs"]
        if re_ <= start_ms or rs >= end_ms:
            continue
        src = _raw_local_path(r)
        if not src.exists():
            continue
        s = max(start_ms, rs) - rs
        e = min(end_ms, re_) - rs
        part = out.parent / f".part_{r['id']}_{len(parts)}.m4a"
        try:
            cut_clip(src, s / 1000, e / 1000, part)
            parts.append(part)
        except subprocess.CalledProcessError as ex:
            log(f"切分失败 {r['id']}: {ex.stderr.decode()[:200] if ex.stderr else '?'}")
    if not parts:
        return False
    concat_clips(parts, out)
    return True


def overlap_ratio(a_s, a_e, b_s, b_e):
    inter = max(0, min(a_e, b_e) - max(a_s, b_s))
    return inter / max(1, a_e - a_s)


def segment_all(st):
    """手动会议优先切分；与会议重叠>50% 的自动话题跳过（included_in_meeting）。
    durationMs 等字段做容错（HTTP 与 ADB 两通道字段来源略有差异）。"""
    done = []
    for m in st.get("meetings", []):
        if m["status"] == "no_voice":
            continue
        dur_ms = m.get("durationMs") or ((m.get("endMs") or m["startMs"]) - m["startMs"])
        d = day_dir(m["startMs"]); (d / "meetings").mkdir(parents=True, exist_ok=True)
        hhmm = datetime.fromtimestamp(m["startMs"] / 1000).strftime("%H%M")
        mins = max(1, round(dur_ms / 60000))
        out = d / "meetings" / f"{hhmm}_会议_{mins}分.m4a"
        if out.exists():
            m["clip"] = str(out)
            continue
        if cut_interval(st, m["startMs"], m["endMs"], out):
            m["clip"] = str(out)
            done.append(out.name)
            log(f"会议切分: {out.name}")
    for t in st.get("topics", []):
        if t["status"] == "low_value":
            t["clip"] = None
            continue
        overlapped = any(
            overlap_ratio(t["startMs"], t["endMs"], m["startMs"], m["endMs"] or m["startMs"]) > 0.5
            for m in st.get("meetings", []))
        t["included_in_meeting"] = overlapped
        if overlapped:
            t["clip"] = None
            continue
        d = day_dir(t["startMs"]); (d / "topics").mkdir(parents=True, exist_ok=True)
        hhmm = datetime.fromtimestamp(t["startMs"] / 1000).strftime("%H%M")
        mins = max(1, round(t["durationMs"] / 60000))
        out = d / "topics" / f"{hhmm}_话题_{mins}分.m4a"
        if out.exists():
            t["clip"] = str(out)
            continue
        if cut_interval(st, t["startMs"], t["endMs"], out):
            t["clip"] = str(out)
            done.append(out.name)
            log(f"话题切分: {out.name}")
    save_state(st)
    return done


# ------------------------------------------------------------------ 转写

def transcribe_one(cfg, clip: Path, md_out: Path):
    py = cfg.get("transcribe_py", DEFAULT_TRANSCRIBE_PY)
    script = cfg.get("transcribe_script", DEFAULT_TRANSCRIBE_SCRIPT)
    proc = subprocess.run(["nice", "-n", "10", py, script, str(clip),
                           "--language", "zh", "--no-whisper"],
                          capture_output=True, text=True, timeout=1800)
    if proc.returncode != 0:
        log(f"转写失败 {clip.name}: {proc.stderr[-300:]}")
        return False
    txt_file = clip.with_suffix(".transcript.txt")
    if not txt_file.exists():
        log(f"转写产物缺失 {txt_file}")
        return False
    body = txt_file.read_text()
    body = clean_transcript(body)   # 静默段复读幻觉清理（[SPK]复读/短语复读/单字复读）
    header = (f"# {clip.stem}\n\n"
              f"- 音频: `{clip.name}`\n"
              f"- 转写时间: {datetime.now().strftime('%Y-%m-%d %H:%M')}\n\n---\n\n")
    md_out.parent.mkdir(parents=True, exist_ok=True)
    md_out.write_text(header + body)
    txt_file.unlink()
    return True


def transcribe_all(cfg, st):
    items = [(m, m["clip"]) for m in st.get("meetings", []) if m.get("clip")]
    items += [(t, t["clip"]) for t in st.get("topics", []) if t.get("clip")]
    for item, clip in items:
        clip = Path(clip)
        md = clip.parent.parent / "transcripts" / (clip.stem + ".md")
        item["transcript"] = str(md) if md.exists() else item.get("transcript")
        if md.exists():
            continue
        log(f"转写: {clip.name}")
        if transcribe_one(cfg, clip, md):
            item["transcript"] = str(md)
            save_state(st)
    save_state(st)


# ------------------------------------------------------------------ probe / status / launchd

def probe(cfg):
    """30s 轻探测：2 分钟内新关闭的会议或话题 → 立即跑一拍。
    （会议结束打点 / 手机端「立即出稿」按钮都靠它实现 ≈30s 快速出稿）"""
    try:
        phone = Phone(cfg)
        phone.pair(cfg["pair_code"])
        meetings = phone.api("meetings")
        topics = phone.api("topics")
    except Exception:
        return 0
    now = time.time() * 1000
    for m in meetings:
        if m.get("endMs") and now - m["endMs"] < 120_000:
            log(f"探测到新结束会议 {m['id']}，触发立即同步")
            return sync_once(cfg)
    for t in topics:
        if now - t.get("endMs", 0) < 120_000 and t.get("closeReason") in ("manual", "expedite"):
            log(f"探测到立即出稿的话题 {t['id']}，触发立即同步")
            return sync_once(cfg)
    return 0


def status(cfg):
    st = load_state()
    ok = False
    try:
        ok = Phone(cfg).ping().get("app") == "fx-recorder"
    except Exception:
        pass
    print(json.dumps({
        "phone_ok": ok,
        "last_sync": st.get("last_sync"),
        "meetings": len(st.get("meetings", [])),
        "topics": len(st.get("topics", [])),
        "raw_files": len(st.get("raw", [])),
        "data_dir": str(DATA_DIR),
        "errors": st.get("errors", [])[:3],
    }, ensure_ascii=False, indent=1))


def install_launchd():
    import plistlib
    py = sys.executable
    me = str(Path(__file__).resolve())
    agents = Path.home() / "Library/LaunchAgents"
    agents.mkdir(parents=True, exist_ok=True)

    def plist(name, interval, args):
        p = agents / f"{name}.plist"
        p.write_bytes(plistlib.dumps({
            "Label": name, "ProgramArguments": [py, me] + args,
            "StartInterval": interval, "RunAtLoad": True,
            "StandardErrorPath": str(LOGS / f"{name}.err"),
            "EnvironmentVariables": {"PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"},
        }))
        subprocess.run(["launchctl", "unload", str(p)], capture_output=True)
        subprocess.run(["launchctl", "load", str(p)], check=True)
        return p

    LOGS.mkdir(parents=True, exist_ok=True)
    p1 = plist("com.fxrecorder.sync", 300, ["once"])
    p2 = plist("com.fxrecorder.probe", 30, ["probe"])
    log(f"launchd 已安装: {p1.name}(5min) + {p2.name}(30s)")


def uninstall_launchd():
    agents = Path.home() / "Library/LaunchAgents"
    for name in ("com.fxrecorder.sync", "com.fxrecorder.probe"):
        p = agents / f"{name}.plist"
        subprocess.run(["launchctl", "unload", str(p)], capture_output=True)
        p.unlink(missing_ok=True)
    log("launchd 已卸载")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "once"
    if cmd == "config":
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        CONFIG.write_text(json.dumps({
            "phone": sys.argv[2], "pair_code": sys.argv[3], "port": 8080,
        }, indent=1))
        print(f"配置已写入 {CONFIG}")
        return
    if cmd == "install":
        install_launchd(); return
    if cmd == "uninstall":
        uninstall_launchd(); return
    if cmd == "clean":
        reclean_all(); return
    cfg = load_config()
    if cmd == "once":
        sys.exit(sync_once(cfg))
    elif cmd == "probe":
        sys.exit(probe(cfg))
    elif cmd == "status":
        status(cfg)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
