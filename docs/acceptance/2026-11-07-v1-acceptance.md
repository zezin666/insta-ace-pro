# Ace 相册 v1 验收记录

日期：2026-11-08
设备：一加 Ace 3 Pro / ColorOS 16
相机：Insta360 Ace Pro 1，固件 `v1.1.0_build1`
卡片内容：356 个文件（JPG / MP4 / LRV）

## 自动化检查（已通过）

| 项目 | 命令 | 结果 |
|---|---|---|
| 单元测试 | `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest` | 74/74 通过 |
| Debug 构建 | `JAVA_HOME=/d/JDK17 ./gradlew :app:assembleDebug` | BUILD SUCCESSFUL，APK 37 MB |
| HTTP 明文 | 合并 Manifest 检查 | `usesCleartextTraffic="true"` 已生效（下载/照片/视频依赖） |
| 协议探测 | `python tools/probe/probe.py --host 192.168.42.1 --out docs/protocol` | 文件列表 356 个 / 4 页；HEAD 200；Range 206；缩略图 NV12 704×544（照片）、896×512（视频） |

## 真机验收（待执行）

本次会话未连接 Android 设备，以下项目尚未验证。安装：

```bash
/d/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

| # | 项目 | 结果 | 证据 |
|---|---|---|---|
| 1 | 相机开机、手机连相机 WiFi，打开 App 5 秒内进入相册 | 待测 | |
| 2 | 356 个文件下列表滚动流畅，缩略图逐步出现，不 OOM | 待测 | |
| 3 | 照片全屏、双指缩放、双击复位 | 待测 | |
| 4 | 视频在线播放、拖动进度 | 待测 | |
| 5 | 多选下载，通知进度正确，系统相册可见完整文件 | 待测 | |
| 6 | 下载中断开 WiFi → 重连 → 从断点继续；无重复文件 | 待测 | |
| 7 | 已下载标记正确 | 待测 | |
| 8 | 未连接、空间不足、相机断线均有提示，不崩溃 | 待测 | |
| 9 | 抓包确认除相机 IP 外无互联网请求 | 待测 | |
| 10 | 冷启动 / 旋转 / 切后台不崩溃 | 待测 | |

## 已知限制（v1）

- 已下载标记只识别本 App 下载的文件；官方 App 或 USB 导入的同名文件不会被标记。
- 不做 VPN 分流。若官方 App 的分流 VPN 处于开启状态，可能影响相机网络绑定，测试时请先关闭。
- 不做删除、拍摄控制、云服务、360 拼接和编辑。
- 缩略图协议为相机私有 NV12 格式，固件升级后若格式变化，走 HTTP/EXIF 回退。
- 协议依赖社区逆向，固件升级可能导致功能失效；诊断页可帮助定位。

## 验收通过后

```bash
git tag v1.0.0
```
