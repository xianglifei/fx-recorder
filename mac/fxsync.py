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


def load_state():
    if STATE.exists():
        return json.loads(STATE.read_text())
    return {"last_sync": 0, "phone_ok": False, "meetings": [], "topics": [], "raw": [], "errors": []}


def save_state(st):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(st, ensure_ascii=False, indent=1))


def day_dir(ts_ms):
    return ARCHIVE / datetime.fromtimestamp(ts_ms / 1000).strftime("%Y-%m-%d")


def sync_once(cfg):
    st = load_state()
    phone = Phone(cfg)
    try:
        phone.ping()
        phone.pair(cfg["pair_code"])
    except Exception as e:
        st["phone_ok"] = False
        st["errors"] = ([f"phone unreachable: {e}"] + st.get("errors", []))[:20]
        save_state(st)
        log(f"手机不可达: {e}")
        return 1
    st["phone_ok"] = True

    # 1) 下载未 ack 的 raw + sidecar，校验后 ack
    files = phone.api("files")
    acked = []
    for f in files:
        if f.get("ackedAtMs") is not None or f["status"] not in ("sealed", "cleaned"):
            continue
        d = day_dir(f["startedAtMs"]); (d / "raw").mkdir(parents=True, exist_ok=True)
        m4a = d / "raw" / f"{f['id']}.m4a"
        js = d / "raw" / f"{f['id']}.json"
        try:
            if not m4a.exists():
                phone.download(f["audio"], m4a)
                phone.download(f["sidecar"], js)
            if m4a.exists() and m4a.stat().st_size == f["sizeBytes"]:
                acked.append(f["id"])
        except Exception as e:
            log(f"下载失败 {f['id']}: {e}")
    if acked:
        phone.ack(acked)
        log(f"下载并 ack {len(acked)} 个文件")

    # 2) 索引快照
    st["meetings"] = phone.api("meetings")
    st["topics"] = phone.api("topics")
    st["raw"] = [{"id": f["id"], "startedAtMs": f["startedAtMs"], "endedAtMs": f["endedAtMs"],
                  "status": f["status"], "sizeBytes": f["sizeBytes"]} for f in files]
    st["last_sync"] = int(time.time() * 1000)
    save_state(st)

    # 3) 切分 + 转写
    segment_all(st)
    transcribe_all(cfg, st)
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
    """手动会议优先切分；与会议重叠>50% 的自动话题跳过（included_in_meeting）。"""
    done = []
    for m in st.get("meetings", []):
        if m["status"] == "no_voice":
            continue
        d = day_dir(m["startMs"]); (d / "meetings").mkdir(parents=True, exist_ok=True)
        hhmm = datetime.fromtimestamp(m["startMs"] / 1000).strftime("%H%M")
        mins = max(1, round(m["durationMs"] / 60000))
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
    """30s 轻探测：2 分钟内新关闭的会议 → 立即跑一拍。"""
    try:
        phone = Phone(cfg)
        phone.pair(cfg["pair_code"])
        meetings = phone.api("meetings")
    except Exception:
        return 0
    now = time.time() * 1000
    for m in meetings:
        if m.get("endMs") and now - m["endMs"] < 120_000:
            log(f"探测到新结束会议 {m['id']}，触发立即同步")
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
