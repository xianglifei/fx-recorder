#!/bin/bash
# fx-recorder 控制台启动器（双击运行）
# 注：不用 `npx electron .`——Node 26 的 child_process.spawn 与本环境 Electron launcher
# 存在 ENOENT 兼容问题，直接调 dist 二进制稳定可靠。
cd "$(dirname "$0")"
exec "node_modules/electron/dist/Electron.app/Contents/MacOS/Electron" .
