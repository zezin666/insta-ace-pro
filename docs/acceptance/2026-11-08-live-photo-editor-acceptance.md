# Live 图编辑器验收记录

日期：2026-11-08
任务：Task 5 — 全量测试、真机验收与文档
代码提交：`bb5ae0639182ba3a4c857b1b54ae00b474b48a99`（`feat: add live photo segment and cover editor`），其后为审查修复提交（见 `git log c86e4ec..HEAD`）。

## 结论

**部分完成：构建与 JVM 测试通过；真机验收未执行。** 按用户决定跳过真机验收，ColorOS 16 相册动态播放与微信实况图识别待设备可用时补测（见文末清单）。

## 构建与测试

```bash
JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest :app:assembleDebug
# BUILD SUCCESSFUL
```

- 单元测试：23 个测试套件，**90 个测试，0 失败，0 错误，0 跳过**（`app/build/test-results/testDebugUnitTest/*.xml`，2026-11-08 15:07）。
- 本次新增/相关测试：
  - `LivePhotoTimingTest`（5 个）：3 秒窗口、起点越界 clamp、短视频导出全长、封面 clamp、默认封面取中点。
  - `MotionPhotoContainerTest`（5 个）：XMP 仅含 `GCamera`/`OpCamera`/`Container` 且无 `MiCamera`/`MicroVideo`；Exif UserComment 为 type 7 + `ASCII\0\0\0oplus_10485792`（124 字节）；`APP1 Exif → APP1 XMP` 插入顺序；MP4 追加；空视频被拒绝。
- Debug 构建：`BUILD SUCCESSFUL`，APK `app/build/outputs/apk/debug/app-debug.apk`（39,683,256 字节，2026-11-08 14:47）。

## 安装

```bash
/d/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
# Performing Streamed Install
# Success
```

安装目标为当前唯一连接的设备：

| 项目 | 值 |
|---|---|
| 手机 | Redmi K40，型号 `M2012K11AC`，设备代号 `alioth` |
| 系统 | Android 12（SDK 31），MIUI `V130` |
| adb | `8001f17`（USB） |
| App | `com.acegallery`「Ace 相册」，`versionName=1.0`，`versionCode=1` |
| 相机网络 | 手机已连接相机 Wi-Fi `Ace Pro 6Y48MP.OSC`（5 GHz，`192.168.42.1`） |

## 真机验收未执行

计划要求在**一加 Ace 3 Pro / ColorOS 16**（上一轮水印验收设备 `PJX110`，adb `767e154a`）上验收。本次执行时该设备未连接，adb 仅有 Redmi K40。用户决定跳过真机验收，因此以下项目均**未验证**：

| # | 待补测项 | 判定标准 |
|---|---|---|
| 1 | 非默认片段起点 + 非默认封面导出一条 Live 图 | UI 可调两个 Slider；导出成功提示「已导出 Live 图到相册」 |
| 2 | ColorOS 16 相册动态播放 | 相册中长按照片或点「实况」可播放 3 秒片段 |
| 3 | 微信实况开关 + 预览 + 发送 | 微信选图页出现实况开关，预览可动，发送后接收端可播放 |
| 4 | 音频保留 | 导出文件含源视频音轨（`ffprobe`/MediaInfo 或播放可听） |
| 5 | 文件命名与位置 | `DCIM/Insta360/MVIMG_*.jpg` |
| 6 | 原视频未被修改 | 相机侧与原缓存文件大小/mtime/md5 不变 |
| 7 | 短视频（<3 秒）处理 | UI 显示「视频不足 3 秒，将导出全长」；导出实际全长；封面仍在片段内 |
| 8 | 旋转视频 | 预览与导出封面方向一致，片段方向正确 |
| 9 | Transformer 失败回退 | remux 回退产物可播放，或给出可读错误，不产生空文件 |

代码层面已由 JVM 测试覆盖的回归点：片段/封面 clamp 数学、短视频全长、封面越界、Motion Photo 字节结构（Exif/XMP/追加顺序）。Android 侧的 Media3 转码、`MediaMetadataRetriever` 取帧与 MediaStore 写入无法在 JVM 测试中覆盖，必须真机确认。

## 已知限制与后续

- 编辑器中「封面时间」标签按整秒显示（`coverMs / 1000`），拖动时同一秒内数值不变；不影响导出精度。
- 片段预览按钮（spec 中可选项）未实现。
- 微信「发送」属于对外操作，补测时应使用测试联系人并由用户确认。

## 证据文件

- 测试结果：`app/build/test-results/testDebugUnitTest/`
- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 本次未产生真机截图。
