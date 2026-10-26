# fx-recorder

办公室常驻录音助手（Android / 小米手机，v0.2.0）。**手机端功能已全部完成**：常驻录音 → 三条时间线切分（raw 底片 / 自动话题 / 手动会议）→ 存储循环 → 局域网 HTTP 传输。Mac 端管线（拉取/切分/转写/日报）见规划文档 `06-需求文档-Mac管线.md`，待开发。

## 三条时间线（核心架构）

| 线 | 谁说了算 | 产出 |
|---|---|---|
| **raw 底片** | 无人（30 分钟机械切片，全量永续） | `raw_*.m4a` + sidecar，兜底一切 |
| **自动话题** | Silero VAD（3s 开门 +30s 预录 / 静默 5min 关门 / 45min 强切） | topics 索引（含发声片统计 avgBurstMs） |
| **手动会议** | 你打点（通知栏/状态页「会议开始/结束」） | meetings 索引；**点结束立即封盘**，Mac 约 2 分钟出逐字稿 |

三条线相互独立、都以绝对时间锚定；防呆：忘点结束自动补齐（最后人声时刻）、无会议点结束忽略、1h 超时提醒、no_voice 警示。

## 传输（局域网 HTTP :8080）

- 网页（人）：`http://<手机IP>:8080` + 6 位配对码（状态页显示，可刷新）
- API（脚本）：`/api/status` `/api/files` `/api/topics` `/api/meetings` `/files/{id}` `POST /api/ack`
- 安全：未配对一律 401；连错 5 次锁 10 分钟
- 存储循环：72h 过期 + 5GB 配额；已 ack 24h 释放；未 ack 未过期永不删
- USB/adb pull 为 fallback 路径

## 构建

JDK 17 + Android SDK 35；AGP 8.7.3 / Kotlin 2.1.21 / KSP 2.1.21-2.0.2 / Room 2.7.1。

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 安装打开，授麦克风（自动开录；手动停止后不复活）；
2. 完成 MIUI 保活三步（状态页有指引：自启动 / 省电无限制 / 锁任务）；
3. 开会时点「会议开始」，散会点「会议结束」（振动一下确认，两点闭眼可辨）；
4. 电脑浏览器访问状态页显示的地址拉文件，或等 Mac 管线自动接管。

## 项目结构

```
app/src/main/java/com/fx/recorder/
├── FxApp.kt                    # 崩溃捕获（写文件，下次启动入库）
├── recorder/
│   ├── RecordingEngine.kt      # AudioRecord 采集循环 + 固定窗口切片 + 麦克风自愈
│   ├── AacFileWriter.kt        # MediaCodec(AAC-LC) + MediaMuxer(M4A)
│   ├── AudioFileWriter.kt      # 写入器接口 + WAV 降级实现
│   └── RecorderBus.kt          # 运行时状态 StateFlow 桥（通知/UI 共享）
├── vad/
│   └── BandEnergyVad.kt        # 能量滞回 + 频带比 + 噪声底自适应 VAD
├── service/
│   ├── RecordingService.kt     # 前台服务（microphone）
│   ├── KeepAlive.kt            # 心跳排程 + 电池白名单检查
│   ├── Receivers.kt            # 心跳自愈 + 开机自启
│   └── Notifications.kt        # 常驻通知/异常提醒
├── store/
│   ├── Entities.kt / Daos.kt / AppDatabase.kt
│   └── SessionRepository.kt    # 落盘/丢弃决策/sidecar/水位清理
├── settings/AppSettings.kt     # 全部可调参数
└── ui/                         # Compose：状态 / 文件 / 设置
```

## 验收自查（对应 PRD §7）

| 场景 | 状态 |
|---|---|
| A3 纯键盘声窗口被丢弃 | ✅ VAD 频带比 + 人声占比阈值（需真机验证参数） |
| A6 产出文件可直接送 ASR | ✅ AAC-LC/16k/mono M4A（需真机验证） |
| A7 手动杀进程自动恢复 | ✅ 心跳链（Android 12+ 部分 ROM 限制时发通知提醒手动恢复） |
| A8 磁盘将满自动清理 | ✅ 水位 <1GB 触发，清到 2GB |
| A1 MIUI 72h 不中断 | ⚠️ 依赖用户完成保活三步设置，需真机长测 |

## 注意

- 本项目仅录制并保存在本机，不联网、不上传任何数据。
- 持续录音涉及在场人员语音（敏感个人信息），使用前请告知并取得共识。
