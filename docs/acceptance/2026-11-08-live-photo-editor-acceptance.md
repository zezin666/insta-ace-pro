# Live 图编辑器验收记录

日期：2026-11-08
任务：Task 5 — 全量测试、真机验收与文档
代码提交：`bb5ae0639182ba3a4c857b1b54ae00b474b48a99`（编辑器实现）+ 真机验收发现问题的修复提交（编辑页改为应用内全屏覆盖层，见 `git log`）。

## 结论

**通过（PASS）。** 在一加 Ace 3 Pro / ColorOS 16 上完成非默认片段 + 自定义封面的导出，ColorOS 相册识别为「实况」并可动态播放，微信识别为实况图（实况开关可用、已发送）。验收中发现并修复了一个底部操作按钮被裁切的布局缺陷。

## 设备与相机

| 项目 | 值 |
|---|---|
| 手机 | OnePlus Ace 3 Pro，型号 `PJX110`，设备代号 `OP5D06L1` |
| 系统 | Android 16（SDK 36），`PJX110_16.0.5.1301(CN01)`，指纹 `OnePlus/PJX110/OP5D06L1:16/UKQ1.231108.001/U.1161b4b_1510d9c_1510d9a:user/release-keys` |
| adb | `767e154a`（USB） |
| App | `com.acegallery`「Ace 相册」，`versionName=1.0`，`versionCode=1`，最后更新 15:46 |
| 相机 | Insta360 Ace Pro，`192.168.42.1`，App 显示「已连接 192.168.42.1」 |
| 相机网络 | 手机连接 `Ace Pro 6Y48MP.OSC`（5 GHz，IP `192.168.42.20`） |
| 源视频 | `VID_20261108_114147_473.mp4`（列表 4/266，时长 13 秒，缓存 222,987,724 B） |

## 构建与测试

```bash
JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest :app:assembleDebug
# BUILD SUCCESSFUL
```

- 单元测试：27 个测试套件，**116 个测试，0 失败，0 错误，0 跳过**（main 已包含同期的实时画面改动）。
- 相关测试：`LivePhotoTimingTest`（5）、`MotionPhotoContainerTest`（5）。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`（39,683,256 B，15:24 由 main 构建；布局修复后 15:46 安装）。

## 真机验收

### 1. 非默认片段 + 自定义封面导出

操作：相册 → `VID_20261108_114147_473.mp4` → 查看器右上「LIVE」→ 等待源视频缓存 → 拖动「片段起点」与「封面位置」→ 导出。

| 轮次 | 片段 / 封面 | 导出文件 | 大小 | 结果 |
|---|---|---|---|---|
| 1（15:31，Dialog 版） | 约 6–9 秒 / 约 8.24 秒 | `MVIMG_20261108_114147_473.jpg` | 27,980,897 B | 通过 |
| 2（15:47，覆盖层版） | 约 5–8 秒 / 约 6.9 秒 | `MVIMG_20261108_114147_473 (1).jpg` | 27,770,234 B | 通过 |

导出文件的字节结构（拉取到电脑解析）：

| 检查 | 轮次 1 | 轮次 2 |
|---|---|---|
| JPEG 结束（EOI）偏移 / 追加 MP4 字节数 | 404,550 / 27,576,345 | 405,051 / 27,365,183 |
| XMP `OpCamera:VideoLength` | 27,576,345（一致） | 27,365,183（一致） |
| XMP `Container` MotionPhoto `Item:Length` | 27,576,345（一致） | 27,365,183（一致） |
| `GCamera:MotionPhotoPresentationTimestampUs` | 2,243,000（封面相对片段起点） | 912,000 |
| Exif UserComment | tag `0x9286`、type **7**、count 22、`ASCII\0\0\0oplus_10485792` | 同左 |
| XMP 字段 | 仅 `GCamera` + `OpCamera` + `Container`，无 `MiCamera`/`MicroVideo` | 同左 |
| 追加 MP4 | H.264 3840×2160 + AAC，时长 3.008 秒 | 同左 |

### 2. ColorOS 16 相册动态播放

- 打开导出图后，相册显示「实况」标记（资源 `com.coloros.gallery3d:id/action_olive_photo`，content-desc「实况照片」）。
- 点击该按钮后连续截帧，照片区域 PSNR 依次为 **19.5 / 19.8 / 21.3 / 24.3 dB**，随后稳定（`inf`）——画面播放约 3 秒后停止，证明动态播放成功。
- 第二张导出图（`(1)`）同样显示「实况」标记。

### 3. 微信实况识别、开关与发送

- 微信「+ → 相册」选择导出图，预览页左下出现「实况」开关；点击后变为绿色勾选（开启）。
- 无障碍节点描述为 **「实况，1, 2026-11-08 15:31」**，微信将文件识别为实况照片。
- 已发送到「文件传输助手」（自己给自己，未向任何联系人/群发送）；聊天中 15:40 出现该图片消息。接收端播放未单独验证（同一账号，需另一台设备）。

### 4. 音频 / 命名 / 原文件

- 音轨：追加 MP4 含 AAC 音轨（`ffprobe`）。
- 命名与位置：`DCIM/Insta360/MVIMG_*.jpg`；重复导出由 MediaStore 追加 ` (1)` 后缀。
- 源文件未被修改：导出源缓存 `5f95b221….mp4`（222,987,724 B）mtime 保持 15:30，两次导出（15:31、15:47）后未变化；相机侧文件仍为只读读取。

### 5. 发现的缺陷与修复

**缺陷：编辑页底部「取消 / 导出」按钮被屏幕底边裁切（约 40 px），文字下半部分不可见。**

- 原因：ColorOS 16 上全屏 `Dialog` 窗口按整屏高度（2779 px）测量内容，但窗口框被状态栏下移（frame `[0,140]–[1264,2780]`），内容比可视区域高 139 px，底部被裁。
- 修复：编辑页改为**应用内全屏覆盖层**（`BackHandler` + `statusBarsPadding()` + `navigationBarsPadding()`），与水印导出编辑器 `PhotoExportEditor` 使用同一模式，不再创建 Dialog 窗口。
- 验证：修复后按钮完整可见（「导出」文字与按钮均在屏内），并完成第二轮导出（见上表）。

## 未覆盖项

| # | 项目 | 原因 / 结论 |
|---|---|---|
| 1 | 视频短于 3 秒 | 相机内容中没有 <3 秒的视频，未实测；逻辑由 `LivePhotoTimingTest` 覆盖 |
| 2 | 旋转（竖屏）视频 | 相机内容中没有旋转视频，未实测；导出器按 `METADATA_KEY_VIDEO_ROTATION` 旋转封面，片段由 Media3 处理方向 |
| 3 | Transformer 失败回退 | 未构造失败输入；remux 兜底已加空片段保护 |
| 4 | 微信接收端播放 | 发送到「文件传输助手」即本人账号，需第二台设备/账号验证接收端 |

## 证据文件

保存在 gitignored 目录 `build/ace-spike/acceptance/live-editor/`：

- 编辑页：`03-editor.png`（修复前裁切）、`34-editor-overlay.png`（修复后）、`35-edited-overlay.png`（片段 5–8 秒 / 封面 6 秒）
- ColorOS：`07-gallery-open.png`（「实况」标记）、`play-1…8.png`（播放截帧）、`36-gallery-second.png`
- 微信：`23-wechat-preview-live.png`（实况开关关闭）、`24-wechat-live-on.png`（开启）、`29-wechat-ft-sent.png`（已发送）
- 导出文件：`out-live.jpg`、`out-live2.jpg`、`clip.mp4`、`clip2.mp4`
- 结构解析：本机 Python/ffprobe 输出（见上表）
