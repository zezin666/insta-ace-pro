# Ace 相册（Ace Gallery）

一款为 **Insta360 Ace Pro 1** 运动相机打造的轻量 Android 相册应用，通过相机自带的 Wi-Fi 直连，在手机上浏览、下载、预览相机文件，并支持官方风格的水印导出、Live 图（实况照片）制作与实时取景控制。

应用为个人侧载工具，**非 Insta360 官方产品**，协议为逆向分析所得，仅对相机做只读访问。

## 功能特性

### 相册浏览与下载
- 直连相机 Wi-Fi（默认 `192.168.42.1`），自动扫描并绑定相机网络。
- 按拍摄日期分组的缩略图网格，支持「全部 / 照片 / 视频」筛选。
- 多选后批量下载原片到系统相册 `DCIM/Insta360`。
- 串行下载队列，断点续传（`.part` 临时文件），Wi-Fi 重连后自动重试（最多 7 次，指数退避）。
- 前台 `dataSync` 服务显示下载进度。

### 查看器
- 左右滑动翻页、双指缩放；视频通过 ExoPlayer 直接流式播放。
- 分享、下载原片、照片水印导出、视频转 Live 图。

### 水印导出（导出 ≠ 下载）
- 按官方 App 样式渲染：Insta360 字标 + `Ace Pro` 镂空圆角矩形 + `CO-ENGINEERED WITH` + 徕卡标。
- 五种预设位置：底部居中、左上、右上、左下、右下。
- 生成新副本 `*_WM.jpg`，保留拍摄时间与方向归一化后的 EXIF，**原片不动**。
- 大图 OOM 时自动降采样重试。

### Live 图编辑器
- 从任意视频中选取 **3 秒片段**（视频不足 3 秒时导出全长）与自定义封面帧。
- 全屏编辑器：片段起点、封面位置双滑块，封面预览防抖刷新，方向自动校正。
- 输出单文件 Motion Photo（`MVIMG_*.jpg`）：`SOI → APP1 Exif → APP1 XMP → JPEG → EOI → MP4`，保留音轨。
- 已在一加 Ace 3 Pro / ColorOS 16 实测：系统相册识别「实况」并可动态播放，微信可识别为实况照片并发送。

### 实时画面
- 实时取景预览，按相机实际推流分辨率显示比例，画面不拉伸。
- 「拍照」「录制」直接使用相机当前模式，不自动切换；录制过程中可继续拍照，画面中断会自动恢复。
- 状态区显示预览/录制状态与录制时长。

### 设置与诊断
- 自定义相机 IP，电池优化白名单入口。
- 诊断页：检查连接、文件列表、HTTP HEAD、缩略图链路。

## 环境要求

| 项目 | 要求 |
|---|---|
| 手机 | Android 10+（`minSdk 29`，`targetSdk 35`），已连接相机 Wi-Fi |
| 相机 | Insta360 Ace Pro 1（固件 `v1.1.0_build1` 验证通过） |
| 构建 | JDK 17、Android SDK（`compileSdk 35`）、Gradle 8.11.1（wrapper） |
| 网络 | 仅访问相机 `192.168.42.1`，应用不发起任何互联网请求 |

## 构建

1. 在项目根目录创建 `local.properties`（已 gitignore）：

```properties
sdk.dir=D:\\Android\\Sdk
```

2. 构建 Release APK（需要使用你自己的签名密钥，见下）：

```bash
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

Release 签名从 gitignored 的 `local.properties` 读取（或同名环境变量）：

```properties
acegallery.release.store=C:/path/to/your-release.jks
acegallery.release.storePassword=****
acegallery.release.keyAlias=your-alias
acegallery.release.keyPassword=****
```

未配置时 `assembleRelease` 仍会产出未签名的 APK。签名密钥请自行备份；更换密钥后无法覆盖安装旧版本。

3. 构建 Debug APK（开发调试）：

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> Windows 下若默认 `JAVA_HOME` 指向 Java 8，需要显式指定 JDK 17：
> `JAVA_HOME=/d/JDK17 ./gradlew :app:assembleRelease`

4. 运行 JVM 单元测试：

```bash
./gradlew :app:testDebugUnitTest
# 单个测试类：./gradlew :app:testDebugUnitTest --tests com.acegallery.export.LivePhotoTimingTest
```

## 安装与使用

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
# 开发调试包：app/build/outputs/apk/debug/app-debug.apk
```

1. 打开相机 Wi-Fi，手机连接相机的热点（SSID 形如 `Ace Pro XXXXXX.OSC`）。
2. 打开「Ace 相册」，等待顶部显示「已连接 192.168.42.1」。
3. **下载**：长按或点选多个文件 → 「下载所选」。
4. **水印导出**：打开照片 → 底部「导出」→ 选择水印位置 → 导出。
5. **Live 图**：打开视频 → 右上「LIVE」→ 调整片段起点与封面 → 导出。
6. **实时画面**：底部「实时画面」→ 预览；「拍照」/「录制」直接使用相机当前模式。

下载的原片与导出副本都写入 `DCIM/Insta360/`。同名导出由系统相册自动追加 ` (1)`、` (2)` 后缀。

## 技术架构

### 双通道传输
- **TCP 6666**：自定义帧（`u32 LE 总长度 + protobuf`）协议，负责文件列表（命令 13）、相机缩略图（命令 11）、实时取景与拍照/录制（命令 1–5）、相机模式/推流参数查询（命令 54/55、通知 8211/8234）。
- **HTTP 80**：文件大小、Range 断点下载、预览与 ExoPlayer 流播放；相机不提供目录列表，仅 TCP 协议可列文件。

### 关键设计
- `CameraSession` 单例持有连接，所有 socket 通过 `Network.bindSocket` 绑定相机 Wi-Fi 网络；`get()` 作为重连入口。
- `CameraProtocolClient` 维护序号关联、同步与心跳，超时/发送失败即标记连接失效。
- 缩略图加载链：相机 NV12 缩略图 → HTTP 头段 JPEG → EXIF 内嵌缩略图，配合内存 LRU 与磁盘缓存。
- 下载与导出严格区分：下载保存原片；导出生成新的处理后副本。
- JVM 单元测试不触碰 Android 类，Android 行为通过 `CameraGateway`、`CameraNetworkFinder`、`MediaStoreWriter`、`CameraFileSource` 等接口隔离。

### 输出格式（Live 图）
- Exif UserComment 必须为 **type 7** + `ASCII\0\0\0oplus_10485792`（ColorOS 16 不识别旧的 `oplus_8388608` ASCII 标记）。
- XMP 仅包含 `GCamera` + `OpCamera` + `Container` 字段，不含小米 `MiCamera` / `MicroVideo`。
- `OpCamera:VideoLength` 与 `Container` MotionPhoto item 长度必须等于追加的 MP4 字节数；`VideoLength` 与时间戳以 `Long` 处理。

## 项目结构

```text
app/src/main/
├── proto/camera.proto            # 相机 TCP 协议消息
├── java/com/acegallery/
│   ├── camera/                   # 帧编解码、协议客户端、命令
│   ├── network/                  # 网络发现、socket/HTTP 绑定
│   ├── session/                  # CameraSession 连接与重连
│   ├── repository/               # 文件列表分页
│   ├── download/                 # 下载队列、前台服务
│   ├── storage/                  # MediaStore 写入
│   ├── thumbnail/                # 缩略图加载链与 NV12 解码
│   ├── export/                   # 水印导出、Live 图封装
│   ├── live/                     # 实时取景解码与录制
│   ├── ui/                       # Compose 界面（相册/实时画面/设置/查看器）
│   └── di/                       # Hilt 绑定
└── res/                          # 资源与主题
tools/probe/                      # 只读协议探测脚本（Python）
```

## 文档

- 设计文档：`docs/superpowers/specs/2026-11-07-insta-ace-pro-android-app-design.md`
- 实时控制 / Live 图 / 水印设计：`docs/superpowers/specs/2026-11-08-live-controls-livephoto-watermark-design.md`
- 协议探测记录：`docs/protocol/ace-pro-1-notes.md`、`docs/protocol/report.json`
- 验收记录：`docs/acceptance/`
- 水印 / Live 图调研：`docs/research/official-export-watermark.md`
- 开发者说明：`CLAUDE.md`

## 已知限制

- 仅支持一台相机、一条相机 Wi-Fi 连接；切换网络需重新检测。
- 对相机只读：不支持删除、上传或修改相机文件。
- Live 图片段固定 3 秒，暂不支持自定义时长；水印位置为五个预设，不支持自由拖动。
- Live 图/实况照片的识别效果与 ROM、微信版本相关，已在 ColorOS 16 + 微信实测通过，其他系统需自行验证。
- 相机实时流固定为 16:9（实测 1280×720），拍照/录像的 4:3 等取景比例无法在预览中还原；预览按真实推流比例显示，不拉伸。请求 4:3 推流分辨率会被相机忽略。
- 相机缩略图为私有 NV12 数据，非标准 JPEG；预览与导出依赖相机固件行为。
- 无云服务、无账号体系；应用不访问互联网。

## 版本历史

- `v1.3.2`：移除首页废弃入口、新增「关于」页；拍照/录制改为直接使用相机当前模式；修复实时预览被拉伸的问题（按真实推流比例显示）；Release 使用正式签名。
- `v1.3.1`：新增自适应应用图标（含单色主题图标）；APK 版本号更新为 1.3.1。
- `v1.3.0`：水印导出编辑器、Live 图片段/封面编辑器、实时画面模式切换与拍照/录制、ColorOS 16 真机验收。
- `v1.2.0`：官方导出与水印调研、功能范围文档。
- `v1.1.0`：查看器翻页与缩放修复。
- `v1.0.0`：首个可用的相册、下载、预览版本（Redmi K40 验收）。

## 声明

本项目为个人学习与自用工具，与 Insta360 无关联。所涉协议来自对自有设备的逆向分析，请仅在自有设备上使用，并遵守当地法律法规与设备厂商条款。
