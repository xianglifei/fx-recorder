// fx-recorder 控制台主进程：界面与管线分离——本窗口退出不影响 launchd 管线运行
const { app, BrowserWindow, ipcMain, shell, clipboard } = require("electron");
const { execFile, spawn } = require("child_process");
const fs = require("fs");
const path = require("path");
const os = require("os");

const REPO = path.resolve(__dirname, "..");
const FXSYNC = path.join(REPO, "fxsync.py");
const DATA_DIR = path.join(os.homedir(), "fx-recorder-data");
const STATE = path.join(DATA_DIR, "state.json");
const AGENTS = path.join(os.homedir(), "Library", "LaunchAgents");
const PLISTS = {
  sync: path.join(AGENTS, "com.fxrecorder.sync.plist"),
  probe: path.join(AGENTS, "com.fxrecorder.probe.plist"),
};

function run(cmd, args) {
  return new Promise((resolve) => {
    execFile(cmd, args, { timeout: 15000 }, (err, stdout, stderr) =>
      resolve({ ok: !err, stdout: stdout || "", stderr: stderr || "" }));
  });
}

let win;

function createWindow() {
  win = new BrowserWindow({
    width: 960, height: 680, title: "fx-recorder 控制台",
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true },
  });
  win.setMenuBarVisibility(false);
  win.loadFile("index.html");
}

app.whenReady().then(createWindow);
app.on("window-all-closed", () => app.quit());

// ------------------------------------------------------------ IPC

// 服务状态：launchctl list 中存在 label 即为已加载运行
ipcMain.handle("svc-status", async () => {
  const r = await run("launchctl", ["list"]);
  const loaded = r.ok && /com\.fxrecorder\.(sync|probe)/.test(r.stdout);
  return { loaded };
});

ipcMain.handle("svc-on", async () => {
  await run("launchctl", ["load", PLISTS.sync]);
  await run("launchctl", ["load", PLISTS.probe]);
  return (await ipcMain.handle("svc-status", null, null));
});
ipcMain.handle("svc-off", async () => {
  await run("launchctl", ["unload", PLISTS.sync]);
  await run("launchctl", ["unload", PLISTS.probe]);
  return (await ipcMain.handle("svc-status", null, null));
});

// 立即同步：触发一拍（不阻塞界面）
ipcMain.handle("sync-now", async () => {
  const logF = fs.openSync(path.join(DATA_DIR, "logs", "manual.log"), "a");
  const child = spawn("/usr/bin/python3", [FXSYNC, "once"], {
    detached: true, stdio: ["ignore", logF, logF],
  });
  child.unref();
  return { started: true };
});

// 数据：state.json + 归档扫描
ipcMain.handle("get-data", async () => {
  let st = {};
  try { st = JSON.parse(fs.readFileSync(STATE, "utf8")); } catch {}
  const items = [];
  const fileSize = (p) => { try { return fs.statSync(p).size; } catch { return 0; } };
  // 会议与话题合并列表（含产物路径与大小）
  for (const m of st.meetings || [])
    items.push({ kind: "meeting", ...m, clipSize: m.clip ? fileSize(m.clip) : 0 });
  for (const t of st.topics || []) {
    if (!t.included_in_meeting)
      items.push({ kind: "topic", ...t, clipSize: t.clip ? fileSize(t.clip) : 0 });
  }
  // 手机连通性
  const ping = await fetch(`http://${(await phoneAddr())}/api/status`, { signal: AbortSignal.timeout(3000) })
    .then(r => r.ok).catch(() => false);
  return {
    phone_ok: ping,
    last_sync: st.last_sync || 0,
    items,
    data_dir: DATA_DIR,
  };
});

async function phoneAddr() {
  try {
    const cfg = JSON.parse(fs.readFileSync(path.join(DATA_DIR, "config.json"), "utf8"));
    return `${cfg.phone}:${cfg.port || 8080}`;
  } catch { return "127.0.0.1:8080"; }
}

ipcMain.handle("open-path", (e, p) => shell.openPath(p));
ipcMain.handle("show-item", (e, p) => shell.showItemInFolder(p));
ipcMain.handle("copy-text", (e, t) => { clipboard.writeText(t); return true; });
ipcMain.handle("open-dir", () => shell.openPath(DATA_DIR));
ipcMain.handle("open-log", () => shell.openPath(path.join(DATA_DIR, "logs", "sync.log")));
