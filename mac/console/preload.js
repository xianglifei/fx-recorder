const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("fx", {
  svcStatus: () => ipcRenderer.invoke("svc-status"),
  svcOn: () => ipcRenderer.invoke("svc-on"),
  svcOff: () => ipcRenderer.invoke("svc-off"),
  syncNow: () => ipcRenderer.invoke("sync-now"),
  getData: () => ipcRenderer.invoke("get-data"),
  openPath: (p) => ipcRenderer.invoke("open-path", p),
  showItem: (p) => ipcRenderer.invoke("show-item", p),
  copyText: (t) => ipcRenderer.invoke("copy-text", t),
  openDir: () => ipcRenderer.invoke("open-dir"),
  openLog: () => ipcRenderer.invoke("open-log"),
});
