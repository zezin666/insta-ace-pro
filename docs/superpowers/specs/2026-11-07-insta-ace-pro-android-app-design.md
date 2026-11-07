# Insta360 Ace Pro 第三方安卓相册 App —— 设计文档

日期：2026-11-07
状态：待评审

## 1. 背景与目标（为什么做）

Insta360 官方 App 功能全，但体积大、启动慢，且绑定账号、云服务、编辑和社区。使用者（一加 Ace 3 Pro，ColorOS 16）只想要一个更轻的工具：连接相机 WiFi 后，直接在手机上看卡里的照片和视频，把需要的素材下载到系统相册。

官方不向个人开发者开放 Ace Pro 的 SDK，OSC 协议也只覆盖 X/ONE 系列，因此本项目基于社区逆向的相机协议实现。

**成功的样子**：相机开机、手机连上相机 WiFi 后，打开 App 5 秒内看到按日期分组的相册，缩略图正常显示，点开能看照片和视频，多选下载后文件出现在系统相册里，全程不需要登录、不弹广告。

## 2. 目标 / 非目标 / 反目标

### 目标（v1）

1. 首页即相册：列出相机存储卡内的照片和视频，按日期分组，显示缩略图。
2. 查看：照片全屏预览、双指缩放；视频在线播放（无需先下载）；左右滑动切换上一个/下一个。
3. 下载：单选/多选下载到系统相册，显示进度，支持断点续传、失败重试；已下载文件有标记。
4. 连接：手动连接相机 WiFi 后，App 自动检测并绑定该网络；首页有清晰的未连接引导。
5. 轻量：无账号、无云端、无广告、无编辑功能；冷启动和列表滚动流畅。

### 非目标（v1 明确不做）

- 相机控制：录制、拍照、改拍摄参数。
- 删除相机内文件。
- App 内自动连接相机 WiFi（`WifiNetworkSpecifier`）。
- 分流 VPN（连相机 WiFi 时保持移动数据上网）。
- 360 拼接、剪辑、滤镜、云备份、固件升级。
- iOS、平板专门适配、多相机同时连接。

### 反目标（技术上做出来也算失败）

- 需要 root、越狱或破坏相机安全机制才能用。
- 必须依赖官方 App 先激活/授权才能读取文件（若真机探测发现此限制，需重新评估范围）。
- 为了"功能多"把 App 做成官方 App 的臃肿替代品。
- 下载过程中覆盖或静默删除用户已有文件。

## 3. 约束

- 目标设备：一加 Ace 3 Pro / ColorOS 16（Android 16），个人使用，侧载安装，不上架应用商店。
- 无官方 SDK、无官方文档；协议来自社区逆向，固件升级可能失效。
- 相机 WiFi 无互联网，速度有限；Android 需要把 socket 绑定到该网络。
- ColorOS 的电池管理可能杀掉后台任务，下载必须用前台服务并提示用户关闭电池优化。
- 相机 IP 默认 `192.168.42.1`，默认 WiFi 密码 `88888888`（以相机屏幕显示为准）。

## 4. 关键决策（已定）

| 决策 | 选择 | 理由 |
|---|---|---|
| 平台/语言 | Android，Kotlin | 用户要求；官方推荐技术栈 |
| UI | Jetpack Compose + Material 3 | 官方推荐，迭代快 |
| 架构 | 单模块 + 包内分层（data/domain/ui），MVVM，Repository | 项目规模小，避免多模块构建开销 |
| 异步 | Kotlin Coroutines + Flow | 官方推荐 |
| DI | Hilt | 官方推荐；若构建复杂度成为问题可退回手写构造注入 |
| 网络层 | OkHttp | 下载、Range、超时控制成熟 |
| 播放 | Media3 ExoPlayer | 支持 HTTP 渐进播放，可自定义 DataSource |
| 图片加载 | Coil 3 | Compose 友好，可接自定义数据源 |
| Protobuf | `protobuf-javalite` + 从 `lib-one-proto` 反推的 `.proto` | 只保留少量消息，体积小 |
| 存储 | MediaStore（`DCIM/Insta360`） | 下载后直接出现在系统相册 |
| 连接方式 | 用户手动连相机 WiFi；App 检测并绑定 | 避开 Android 13+ WiFi 权限和 ColorOS 兼容问题 |
| 授权回退 | 如必须，允许一次性使用官方 App 完成激活/授权 | 用户已确认可接受；之后本 App 独立工作 |
| 断点续传范围 | 仅下载服务存活期间续传（WiFi 断开/重连可继续） | 用户选择 v1 简化；不处理杀进程/重启恢复 |
| 临时文件位置 | App 私有 `filesDir/downloads/`（不用 `cacheDir`） | 避免系统清缓存导致续传失效 |
| minSdk / targetSdk / compileSdk | 29 / 35 / 35 | 本机已安装 android-35 平台；Android 16 设备向下兼容，后续可升 36 |
| 分发 | 侧载 APK | 个人使用 |

## 5. 技术方案

### 5.1 网络与连接

相机开启 WiFi 后是一个无互联网的 AP，手机获得 `192.168.42.x` 地址。

- App 启动/回到前台时，遍历 `ConnectivityManager.allNetworks`，找出 `TRANSPORT_WIFI` 且能连通 `192.168.42.1:6666` 的网络。
- 不对 SSID 做匹配（避免申请定位/附近设备权限），以实际 TCP 可达为准。
- 所有相机请求（OkHttp、协议 socket、ExoPlayer）都必须绑定到这个 `Network`：
  - 自定义 `SocketFactory`：创建 socket 后调用 `network.bindSocket(socket)`。
  - OkHttp 使用该 `SocketFactory`；Media3 使用基于该 OkHttpClient 的 `OkHttpDataSource`。
- 相机 IP 可在设置里覆盖，默认 `192.168.42.1`。
- 未连接时首页显示引导：打开相机 → 手机 WiFi 连接 `Insta360 Ace Pro …` → 密码在相机屏幕上 → 点击"重新检测"。

### 5.2 协议层（TCP 6666）

**协议资料来源**（仓库内不预置这些文件，阶段 0 按需下载/生成）：

- [RigacciOrg/insta360-wifi-api](https://github.com/RigacciOrg/insta360-wifi-api)：`insta360.py` 提供帧格式、命令码、同步/保活实现；`pb2/` 提供 X 系列 protobuf。
- [PyPI lib-one-proto 0.1.4](https://pypi.org/project/lib-one-proto/)：从官方安卓 App 的 `libOne.so` 提取的 protobuf 描述，含 `get_file_list`、`get_file_extra`、`delete_files`。阶段 0 用 `protoc` 反生成最小 `.proto`，放入 `app/src/main/proto/`。
- [akonstster/go-ultra-transfer PROTOCOL.md](https://github.com/akonstster/go-ultra-transfer/blob/main/docs/PROTOCOL.md)：GO Ultra 的实测记录，帧格式/命令号可参考，但**型号行为不保证与 Ace Pro 一致**。

阶段 0 的另一项产出：确认 Ace Pro 1 代的命令码与消息字段；若与 `lib-one-proto` 一致，直接使用其生成的 `.proto`。

社区逆向结果（需真机验证）：

- 帧格式：`[u32 LE 总长度(含这4字节)][payload]`
- 同步包：payload `06 00 00` + ASCII `syNceNdinS`，连接后立即发送。
- 保活包：payload `05 00 00`，约每 2 秒一次；约 10 秒无数据判定断线。
- 消息头（12 字节）：`04 00 00` + `命令码 u16 LE` + `02` + `序号 u24 LE` + `80 00 00`，后接 protobuf。
- 响应：同一序号，响应码 200 成功 / 400 / 500；通知类消息码 ≥ 8000（如 8234 直播参数）。
- 每个响应/通知按序号与请求关联；未知通知忽略但记录日志。

v1 需要的命令：

| 码 | 名称 | 请求 | 响应 |
|---|---|---|---|
| — | 同步 | `syNceNdinS` | 相机回显 |
| — | 保活 | `05 00 00` | 无 |
| 13 | GET_FILE_LIST | `{media_type=2, start, limit}` | `{uri[], total_count}` |
| 11 | GET_FILE_EXTRA | `{uri, extra_type=2}` | 缩略图（原始字节，待验证） |
| 12 | DELETE_FILES | `{uri[]}` | `{fail_uri[]}`（v1 不调用） |

`MediaType` 枚举：`VIDEO=0, PHOTO=1, VIDEO_AND_PHOTO=2, DNG=3, MP4=4, JPG=5, INS_DATA=6`。

文件列表 limit 实测上限 100/页；Ace Pro 1（固件 `v1.1.0_build1`）实测 `total_count=356`、4 页拉完、计数准确，按 `total_count` 翻页即可。

### 5.3 文件传输（HTTP 80）

- `GET http://192.168.42.1<uri>` 获取文件，无需会话鉴权。实测 `HEAD` 返回 200 + `Content-Length`，`Range` 返回 206 + `Content-Range`。
- 注意：`GET /DCIM/` 实测返回 **403**（无目录列表），`/osc/*` 返回 **400**；文件列表只能走 TCP 命令 13。
- 支持 `Range`（206），用于断点续传和视频拖拽；`HEAD` 取大小。
- 下载与播放都绑定相机网络。
- 若 `uri` 是完整 URL，取其 path 再拼默认 host。

### 5.4 缩略图

采用逐级回退，主路径已由真机探测确定：

1. **TCP `GET_FILE_EXTRA(extra_type=2)` 返回 raw NV12 缩略图**（实测）。响应体前 36 字节为 9 个小端 u32：
   `[1][total][1][0][width][height][0][0][0]`，随后是 NV12（Y 平面 `w*h`，再是交错 UV `w*h/2`），末尾多出的 4 字节忽略。
   实测照片 704×544、574,504 字节；视频 896×512、688,168 字节。
2. `extra_type=1` 返回的是 protobuf 文件元数据（序列号/型号/固件），不是图片，v1 不使用。
3. `extra_type=3` 返回另一种二进制格式（非 JPEG），v1 不使用。
4. 照片兜底：HTTP Range 取文件头 ~64KB，用 `ExifInterface` 取内嵌缩略图。
5. 视频兜底：缓存 `.lrv` 低清代理，用 `MediaMetadataRetriever` 抽帧；失败则显示占位图。

协议缩略图不是 JPEG，必须实现 NV12 → ARGB 转换。缩略图存内存 LRU（按 uri 键），磁盘缓存放在 App cache 目录，避免重复走 WiFi。

### 5.5 数据模型

```kotlin
data class CameraFile(
    val uri: String,          // 协议返回的原始 uri
    val path: String,         // 归一化后的 /DCIM/... 路径
    val name: String,
    val kind: Kind,           // PHOTO / VIDEO / OTHER
    val sizeBytes: Long?,     // 列表可能不含，需 HEAD 补齐
    val capturedAt: Instant?, // 优先从文件名解析
    val durationMs: Long?,    // 视频，待协议扩展或播放时读取
)
```

- 文件名解析：`^(VID|IMG|LRV|THM|PRO_VID|PRO_IMG|LIV)_(\d{8})_(\d{6})(?:_(\d{2}))?_(\d{3})`（实测样本均匹配，路径形如 `/DCIM/Camera01/IMG_20260726_192311_149.jpg`）。
- 过滤掉 `.lrv`、`.thm`、隐藏文件。
- `.dng`：列表和查看器展示其内嵌 JPEG 预览（Coil 不能直接解码 DNG），下载时保存原始 `.dng`。
- `.insv/.insp` 等无法直接解码的格式：列表显示通用占位缩略图，查看器显示"仅支持下载"，下载时原样保存。Ace Pro 1 代是否产生这类文件以阶段 0 探测为准。
- 分组：按 `capturedAt` 的日期降序。

### 5.6 相册首页

- `LazyVerticalGrid`，3 列；粘性日期头。
- 顶部：相机状态条（已连接/未连接、电量可选、剩余空间可选）、筛选（全部/照片/视频）。
- 每个格子：缩略图、视频时长角标（**仅当已知时显示，未知则省略**；v1 不为角标单独请求元数据）、已下载勾选角标。
- 空状态、加载骨架、错误重试。
- 下拉刷新重新拉取文件列表。

### 5.7 查看器

- `HorizontalPager` 左右切换。
- 照片：Coil 加载原图（HTTP 绑定网络），Compose 手势缩放/平移/双击。
- 视频：Media3 ExoPlayer + `OkHttpDataSource`；播放/暂停/进度/全屏；失败时提示"下载后播放"。
- 底部/顶部显示文件名、大小、拍摄时间；操作：下载、分享（仅已下载）、删除（v1 不提供）。

### 5.8 下载

- 前台服务（`dataSync` 类型）+ 通知进度，避免 ColorOS 后台杀进程。
- 队列串行下载（相机 WiFi 带宽有限），选中的文件按列表顺序。
- 先写入 App 私有 `filesDir/downloads/` 临时文件（非 `cacheDir`，避免系统清缓存），支持 HTTP Range 续传；完成后插入 MediaStore。
  - 视频 → `MediaStore.Video`，`RELATIVE_PATH=DCIM/Insta360`
  - 照片 → `MediaStore.Images`，同上
  - 其他 → `MediaStore.Downloads`
  - 使用 `IS_PENDING=1`，完成后再置 0，避免相册出现半截文件。
- 已下载判断（v1）：只标记**本 App 下载**的文件——查询 MediaStore 中 `RELATIVE_PATH=DCIM/Insta360` 的 `DISPLAY_NAME` 并校验大小。不申请 `READ_MEDIA_IMAGES/VIDEO`，因此官方 App 或 USB 导入的同名文件不会被标记，可能重复下载（已知限制，后续版本再扩展）。
- 失败分类：相机断线、空间不足、HTTP 错误、文件已存在；可重试。
- 电量/存储预检：下载前提示剩余空间不足。

### 5.9 错误与诊断

- 首页状态：未连接 / 连接中 / 已连接 / 连接断开 / 协议不兼容。
- 内置"诊断"页（设置里长按版本号进入）：显示 WiFi 网络、IP、TCP 连接、命令往返、HTTP 探测结果、最近错误日志。
- 协议未知字段/未知通知写日志，便于固件升级后排查。

### 5.10 权限

- `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`
- `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`（Android 14+）
- `POST_NOTIFICATIONS`（Android 13+，下载通知）
- 不需要定位/附近设备权限（不读 SSID）；不需要存储写权限（MediaStore 自有插入）。

## 6. 阶段 0：真机协议探测（必须先做）

在写主体代码前，用一台连上相机 WiFi 的电脑跑一次性 Python 探测脚本（脚本放在 `tools/probe/`，不属于 App 代码），回答以下问题：

1. `GET http://192.168.42.1/`、`/DCIM/`、`/osc/info`、`/osc/state`：状态码与内容。
2. TCP 6666：同步包是否回显；保活是否维持连接。
3. 命令 13：不带授权能否返回文件列表；`limit` 最大多少；`uri` 是相对路径还是完整 URL；`total_count` 是否准确。
4. 文件命名：照片/视频/`LRV`/`THM`/`DNG` 的实际前缀与扩展名。
5. HTTP：`HEAD` 和 `Range` 是否返回大小/206；下载 1MB 是否正常。
6. 命令 11：对照片和视频分别请求 `extra_type=0/1/2/3`，记录响应原始头 64 字节与长度，判断缩略图格式。
7. 是否需要命令 39 授权；若需要，授权流程是什么。
8. 记录 SSID、频段、HTTP 响应头、WiFi 断开后重连行为。

**产出**：`docs/protocol/ace-pro-1-notes.md` + 原始抓包/十六进制 fixture。
**通过标准**：能稳定列出全部文件，并成功下载一张照片和一个视频；确定缩略图主路径。

**执行状态**：用户确认相机现在可用，阶段 0 可立即执行；M1 及之后的里程碑以本阶段通过为前置门槛。

若探测发现命令号与 X 系列不同，按实测结果修正第 5.2/5.4 节，再进入开发。

## 7. 里程碑（粗估，不含探测）

| 阶段 | 内容 | 验收 |
|---|---|---|
| M0 | 协议探测 | 第 6 节通过标准 |
| M1 | 工程骨架、网络绑定、相机检测、TCP 客户端、文件列表 | 真机列出文件名 |
| M2 | 相册网格、日期分组、缩略图、筛选 | 滚动流畅，缩略图正确 |
| M3 | 照片查看、视频在线播放、左右切换 | 不下载也能看 |
| M4 | 下载队列、前台服务、Range 续传、MediaStore | 断网重连续传，相册可见 |
| M5 | 多选、已下载标记、空/错状态、诊断页、打磨 | 通过第 8 节验收 |

## 8. 验收标准（v1）

- 相机开机、手机连上相机 WiFi，打开 App 5 秒内进入相册界面（"进入"指 UI 骨架可见；有本地缓存时立即显示缓存列表并在后台刷新，无缓存时显示加载骨架，5 秒内完成首屏文件列表拉取；1000+ 文件允许分页逐步填充）。
- 1000+ 文件下列表可流畅滚动，缩略图逐步加载，不 OOM。
- 照片可全屏缩放；视频可直接在线播放，可拖动进度。
- 多选下载有进度；中途断 WiFi 后重连可续传；完成后系统相册可见完整文件。
- 已下载文件有标记，重复下载不产生重复文件。
- 未连接、相机断线、空间不足都有明确提示，不崩溃。
- 全程无账号、无网络请求发往互联网（可用抓包确认）。

## 9. 风险与回退

| 风险 | 影响 | 回退 |
|---|---|---|
| Ace Pro 1 代协议与 X 系列/GO Ultra 有差异 | 文件列表或缩略图不可用 | 阶段 0 先探测；差异大则先只做 HTTP 目录列表 + 下载 |
| HTTP 目录列表不可用 | 无法绕过 TCP 协议 | 必须实现命令 13；schema 已找到，风险可控 |
| 缩略图接口格式不同 | 网格加载慢/黑图 | 回退到 EXIF 缩略图；视频用 LRV 抽帧 |
| ColorOS 杀后台 | 下载中断 | 前台服务 + 电池优化白名单引导 |
| 读取文件需要官方 App 授权 | 首次使用多一步 | 用户已确认接受一次性授权；授权状态尽量持久化，之后独立工作 |
| WiFi 频繁掉线 | 体验差 | 自动重连、续传；提示关闭 WiFi 省电 |
| 固件升级改协议 | 功能失效 | 诊断页 + 日志；版本化错误提示 |

## 10. 留给构建者的决定

- 包名、App 名称（默认 `com.acegallery` / "Ace 相册"）。
- 是否引入 Room（建议 v1 先用 MediaStore 查询 + DataStore，文件多时再引入）。
- 缩略图内存/磁盘缓存的具体容量与淘汰策略。
- 下载并发数（建议 1）与重试次数（建议 3，指数退避）。
- 诊断页的具体字段与日志保留策略。
- 主题色、图标与文案细节。

### 已由设计方确定（评审补充）

- 视频时长角标：懒加载，未知时不显示，不在 v1 单独拉取元数据。
- DNG / 无法解码的格式：DNG 用内嵌 JPEG 预览；未知格式显示"仅下载"占位。
- "5 秒进相册"按 §8 的定义验收，允许缓存先显示、后台刷新。
- 已下载标记 v1 只认本 App 的下载记录，不申请额外媒体权限。
