# 实时控制、Live 图与水印导出 —— 设计文档

日期：2026-11-08
状态：待评审
关联文档：
- `docs/superpowers/specs/2026-11-07-insta-ace-pro-android-app-design.md`（v1 原设计）
- `docs/research/official-export-watermark.md`（官方导出/水印调研）
- `docs/protocol/ace-pro-1-notes.md`（真机协议探测）

## 1. 背景与目标

Ace 相册（`com.acegallery`）已经能连接 Insta360 Ace Pro 1，浏览、预览、下载相机文件，并具备实时预览、照片水印导出、视频转 Live 图的基础能力。本轮要解决三件实际使用中不顺手的事：

1. **实时画面**：保留现在的上下分区布局，但「拍照」「录制」按钮要能自动把相机切到对应模式；预览比例要跟随相机实际推流比例。
2. **视频转 Live 图**：让用户自己选择 3 秒片段和封面帧；导出的文件要能被 ColorOS 相册和微信识别为实况照片。
3. **水印导出**：把导出从下载中独立出来，水印样式按官方 App 还原（文字 + 徕卡标），位置提供底部居中和四角五个预设，为后续图片编辑留出扩展空间。

### 已确认的关键结论

- Live 图格式问题已定位并修复：旧导出使用 `oplus_8388608`（type 2，无 ASCII 前缀），ColorOS 16 不识别；真机原相机文件使用 **Exif UserComment type 7 + `ASCII\0\0\0oplus_10485792`**。对齐后，ColorOS 相册和微信均可正常播放。该修复已在验证版中确认。
- 真机原相机的 XMP 只包含 `GCamera` + `OpCamera` + `Container`，不包含小米 `MiCamera` / `MicroVideo` 旧字段；导出时已对齐。
- 相机模式切换协议：命令 **54 `SET_SYNC_CAPTURE_MODE`**、命令 **55 `GET_SYNC_CAPTURE_MODE`**；相机通知 **8211 `SYNC_CAPTURE_MODE_UPDATE`**。
- 实时推流参数：相机通知 **8234 `UPDATE_LIVE_STREAM_PARAMS`**，携带 `res_width`、`res_height`、`res_fps` 和裁剪信息。
- 水印样式已通过 mockup 确认：参考 `docs/superpowers/specs/assets/2026-11-08-watermark-mockup-crop.png`（对比官方参考 `...-official-watermark-reference.png`）。

## 2. 范围

### 目标

- 实时画面：拍照/录制按钮自动切换相机模式，动作完成后保持该模式；预览按实际推流比例显示。
- 实时画面：状态区显示当前是照片还是视频模式；切换期间有忙碌状态和错误提示。
- Live 图：用户选择任意 3 秒片段和封面帧；导出文件在 ColorOS 相册、微信中可正常作为实况照片播放。
- 水印：独立的导出入口；官方样式（Insta360 字标 + Ace Pro 镂空圆角矩形 + CO-ENGINEERED WITH + 徕卡标）；底部居中 + 四角五个预设位置。
- 导出与下载严格区分：下载保存原片；导出生成新副本，原片不动。
- 为后续图片编辑扩展留出结构：导出编辑器可以逐步加入裁剪、滤镜等操作，水印作为最后一步应用。

### 非目标（本轮不做）

- 视频水印导出（重编码并叠加动态水印）。
- Live 图片段长度自定义（固定 3 秒；视频不足 3 秒时导出全长）。
- 水印自由拖动（只做五个预设位置）。
- 实时画面参数（分辨率、FOV、曝光等）的编辑。
- 云服务、账号、删除相机文件、360 拼接。
- 多相机同时连接。

### 反目标

- 不能为了加水印覆盖或修改原片。
- 不能把相机控制做成“必须手动在相机上先切模式”的半成品。
- 不能为了 Live 图兼容性引入向互联网发送业务请求的行为。
- 不能因为新增通知/模式切换让现有下载、缩略图、查看器功能回归。

## 3. 约束与已确认决策

- 目标设备：一加 Ace 3 Pro / ColorOS 16（本次真机验证设备 `PJX110`，Android 16）；应用仍需兼容 Android 13 / MIUI（此前验收设备 Redmi K40）。
- 相机：Insta360 Ace Pro 1，固件 `v1.1.0_build1`，相机 IP `192.168.42.1`，TCP 6666 + HTTP 80。
- 现有架构：单模块 `:app`，Kotlin/Compose/Hilt，`CameraSession` 统一持有连接，`CameraProtocolClient` 负责 TCP 帧与序号关联，所有相机 socket 必须绑定相机 Wi-Fi 网络。
- 单元测试必须在 JVM 上运行；Android 专属行为通过接口隔离。相机协议测试使用 `FakeCameraServer`。
- 界面文案中文；包名 `com.acegallery` 不变。
- 用户已确认的交互决策：
  - 实时画面保留上下分区简洁布局。
  - 拍照后保持拍照模式，录制后保持录像模式，不切回之前模式。
  - 非普通子模式（HDR/间隔/连拍/AEB 夜景/延时等）下按「拍照」或「录制」，一律切回普通照片/普通视频，覆盖相机当前子模式。
  - 拍照/录制互斥：录制中禁用「拍照」，拍照进行中禁用「录制」。
  - 水印位置只做底部居中 + 四角五个预设。
  - 水印样式按已确认的 mockup 还原。
  - 导出有独立入口，与下载区分。
- 范围变更说明：v1 原设计把“相机控制”列为非目标；实时预览已在后续提交中加入，本轮进一步加入模式切换、拍照、录制。该变更已由用户确认。

## 4. 功能一：实时画面模式切换与比例

### 4.1 现状

- `LiveViewScreen` 使用固定 16:9 的 `Box` + `TextureView`；`LiveViewDecoder` 用固定 1920×1080 配置 MediaCodec。
- `CameraSession.takePicture()` 直接发命令 3；`startRecording()` 直接发命令 4。相机不在对应模式时操作无效。
- `CameraProtocolClient` 只处理有序号响应的 `0x04` 消息；没有序号的通知（≥ 8000）被忽略。
- 相机模式与推流比例信息目前没有进入 UI。

### 4.2 协议

命令码（`CameraCommands`）：

| 常量 | 值 | 用途 |
|---|---|---|
| `START_LIVE_STREAM` | 1 | 开始实时预览（已有） |
| `STOP_LIVE_STREAM` | 2 | 停止实时预览（已有） |
| `TAKE_PICTURE` | 3 | 拍照（已有） |
| `START_CAPTURE` | 4 | 开始录像（已有） |
| `STOP_CAPTURE` | 5 | 停止录像（已有） |
| `SET_SYNC_CAPTURE_MODE` | 54 | 切换照片/视频模式（新增） |
| `GET_SYNC_CAPTURE_MODE` | 55 | 查询当前模式（新增） |

通知码（新增）：

| 常量 | 值 | 用途 |
|---|---|---|
| `NOTIFICATION_SYNC_CAPTURE_MODE_UPDATE` | 8211 | 模式变化通知（无字段） |
| `NOTIFICATION_CAM_SUBMODE_CHANGE` | 8275 | 照片/视频子模式变化（可选） |
| `NOTIFICATION_UPDATE_LIVE_STREAM_PARAMS` | 8234 | 实时推流宽高/帧率/裁剪信息 |

`camera.proto` 新增（字段号必须与相机一致）：

```proto
enum SyncCaptureMode {
  SYNC_MODE_UNKNOWN = 0;
  SYNC_MODE_NORMAL_IMAGE = 1;
  SYNC_MODE_NORMAL_VIDEO = 2;
  SYNC_MODE_HDR_IMAGE = 3;
  SYNC_MODE_HDR_VIDEO = 4;
  SYNC_MODE_INTERVAL_IMAGE = 5;
  SYNC_MODE_TIMELAPSE_VIDEO = 6;
  SYNC_MODE_BURST_PHOTO = 7;
  SYNC_MODE_BULLETTIME_VIDEO = 8;
  SYNC_MODE_TIMESHIFT_VIDEO = 9;
  SYNC_MODE_AEB_NIGHT_PHOTO = 10;
}

message SetSyncCaptureMode { SyncCaptureMode capture_mode = 1; }
message SetSyncCaptureModeResp {}
message GetSyncCaptureMode {}
message GetSyncCaptureModeResp { SyncCaptureMode capture_mode = 1; }

message WindowCropInfo {
  uint32 src_width = 1;
  uint32 src_height = 2;
  uint32 dst_width = 3;
  uint32 dst_height = 4;
  int32 crop_offset_x = 5;
  int32 crop_offset_y = 6;
}

message LiveStreamParamsUpdate {
  uint32 res_width = 2;
  uint32 res_height = 3;
  uint32 res_fps = 4;
  WindowCropInfo window_crop_info = 6;
  WindowCropInfo half_window_crop_info = 7;
}

message NotificationSyncCaptureModeUpdate {}
```

说明：`LiveStreamParamsUpdate` 只声明本轮需要的字段；protobuf 会忽略未知字段（如 `video_encode_type`）。`NotifyCameraSubMode` 如需要可同样只声明 `photo_submode`/`video_submode`/`sensor` 的 varint 字段。

### 4.3 模式状态与操作

`CameraSession` 增加：

- `captureMode: StateFlow<CaptureMode>`，领域枚举 `CaptureMode { UNKNOWN, PHOTO, VIDEO }`，不把 protobuf 类型暴露给 UI。
- `liveStreamParams: StateFlow<LiveStreamParams?>`，`LiveStreamParams(width, height, fps)`。
- `suspend fun setCaptureMode(mode: CaptureMode)`。
- `takePicture()`：若当前不是 `PHOTO`，先 `setCaptureMode(PHOTO)`，成功后发命令 3。
- `startRecording()`：若当前不是 `VIDEO`，先 `setCaptureMode(VIDEO)`，成功后发命令 4。
- `stopRecording()`：保持命令 5。
- 连接成功后发命令 55 查询当前模式；查询失败时保持 `UNKNOWN`，首次按钮操作仍会尝试发命令 54。
- 模式切换成功后乐观更新 `captureMode`；收到 8211 时重新查询命令 55 或确认当前模式。
- 模式切换失败（超时/非 200）时抛出可读异常，UI 显示错误且不执行拍照/录像。

时序：

```
press 拍照
  -> if mode != PHOTO: request(54, SetSyncCaptureMode{IMAGE})
  -> 等待响应（建议 5s 超时；可再等 200-300ms 让相机稳定）
  -> request(3, TakePicture{NORMAL})
  -> 成功提示；模式保持 PHOTO

press 录制
  -> if mode != VIDEO: request(54, SetSyncCaptureMode{VIDEO})
  -> 等待响应
  -> request(4, StartCapture{NORMAL})
  -> 再次点击：request(5, StopCapture)
  -> 模式保持 VIDEO
```

实现时把“切模式 + 动作”封装成 `continueAfterModeSwitch` 一类的串行流程，避免用户连续点击导致命令交叉。按钮在 `busy` 时禁用。

关于子模式（已确认）：本轮明确把「拍照」映射为 `SYNC_MODE_NORMAL_IMAGE`，「录制」映射为 `SYNC_MODE_NORMAL_VIDEO`。相机当前若处于 HDR、间隔、连拍、AEB 夜景、延时等非普通子模式，按下按钮会切回普通模式。UI 可在状态区或按钮旁提示“将切换到普通照片/视频”，但提示不阻塞操作；不保留、不记忆相机原有子模式。

### 4.4 推流比例

真实比例以相机通知 8234 为准：

- `CameraProtocolClient` 增加通知流（例如 `Flow<CameraNotification>`，`CameraNotification(code: Int, body: ByteArray)`）。
- `handleMessage` 在没有匹配序号且响应码 ≥ 8000 时，把通知发到通知流，而不是静默丢弃。
- `CameraSession` 收集通知：
  - 8234 → `LiveStreamParamsUpdate.parseFrom(body)` → 更新 `liveStreamParams`；只接受 `res_width > 0 && res_height > 0`。
  - 8211 → 触发模式确认（重新查 55）。
  - 8275 → 可选，更新子模式显示。
- 比例字段优先级（必须按这个顺序，避免拉伸）：
  1. 8234 的 `window_crop_info` 若存在且 `dst_width > 0 && dst_height > 0`，用 `dst_width/dst_height`（这是可见输出尺寸；`src_*` 和 `crop_offset_*` 用于理解裁剪，不直接当比例）。
  2. 8234 的 `res_width/res_height`（仅当没有可用的 `window_crop_info` 时）。
  3. `LiveViewDecoder` 从 `MediaCodec.getOutputFormat()` 读到的实际宽高（增加回调/StateFlow）。
  4. 默认 16:9。
- `liveStreamParams` 建议同时保存 `resWidth/resHeight`、`dstWidth/dstHeight` 和 `fps`，UI 只消费最终比例，避免把裁剪逻辑散落到 Compose。

UI：

- 预览框使用 `Modifier.aspectRatio(ratio)`，宽高比来自上述优先级。
- 预览最大高度建议取可用高度的 55%；若按比例算出的高度超过上限，则在固定高度区域内居中显示并留黑边（letterbox），下方按钮位置不动。
- 状态区显示“照片”/“视频”；切换期间显示“切换模式…”，拍照/录制期间显示对应忙碌文案。
- 按钮启用规则（已确认）：录制中「拍照」禁用；拍照进行中「录制」禁用；`busy` 时两者都禁用。ViewModel 维护 `recording`、`capturing`（或 `busy` + 当前动作）状态，按钮的 enabled 条件据此计算。
- 横屏：不锁定方向。实时页使用响应式布局：预览仍在控制区上方，最大高度改为“可用高度减去控制区高度”；控制区保持一行。高度不足时预览按比例缩小并留黑边。横屏作为验收项之一。

关于“相机在 4:3 模式但推流仍是 16:9”的情况：

- 第一阶段只实现 8234/解码器实际比例；如果相机本身推 4:3，这一阶段就满足需求。
- 如果真机确认 8234 恒为 16:9，则作为条件任务追加：开始实时预览时根据当前模式分辨率请求匹配的推流分辨率：
  - 16:9 → `RES_1920_1080P30`（29）
  - 4:3 → `RES_1920_1440P30`（42）
  - 1:1 → `RES_1280_1280P30`（89）或 `RES_1152_1152P30`（74）
- 当前模式分辨率通过命令 10 `GET_PHOTOGRAPHY_OPTIONS` 查询，`PhotographyOptions` 里的 `photo_size_id`（字段 30）或 `record_resolution`（字段 31）可推导比例；这些字段当前尚未加入 `camera.proto`，属于条件任务的一部分。
- 若相机不接受请求，仍以 8234/解码器实际尺寸为准，不能强行拉伸画面；保持 16:9 并记录为已知限制。

### 4.5 错误与恢复

- 模式切换失败：显示“无法切换相机模式”，不拍照/不录像。
- **模式查询/切换不采用致命超时**：`CameraProtocolClient.request` 增加 `fatalOnTimeout: Boolean = true` 参数；命令 54/55 使用 `false`。这样相机忽略查询/切换时只显示错误，不会把 transport 标记为死亡、连带杀掉实时预览；其它命令保持现有行为。若相机确实断线，后续 `get()` 会走既有重连。
- 拍照/录像失败：沿用现有 `LiveViewViewModel` 的错误提示。
- 模式切换后推流中断：若 1 秒内收不到视频包，自动重新发送开始预览命令（命令 1）一次；仍失败则提示用户退出重进。
- 连接断开：沿用 `CameraSession` 的 `get()/connect()` 重连逻辑；实时页重新进入时重新查询模式并开始预览。
- 拍照/录制互斥（已确认）：录制中「拍照」禁用；拍照进行中「录制」禁用；`busy` 时两者都禁用。这样不会在录制中发送切模式命令打断录像，也不会在拍照命令进行中启动录制。

## 5. 功能二：视频转 Live 图编辑器

### 5.1 现状

- 查看器顶部「LIVE」按钮打开 `LivePhotoExportDialog`。
- 对话框有两个 Slider：片段起点、封面位置；片段固定 3 秒；默认封面 1.5 秒。
- `LivePhotoExporter.export(video, file, startMs, coverMs)` 负责裁剪、转码、封面、封装和写入相册。
- 文件格式已修复并被 ColorOS 16 相册与微信接受。

### 5.2 交互设计

把当前 AlertDialog 升级为更清晰的导出编辑页（全屏 sheet 或独立页面）：

- 顶部/中部：封面帧预览（按视频方向显示）。
- 片段选择：
  - 一个时间轴 Slider，选择 3 秒窗口的起点；显示「起点 – 终点」。
  - 范围：`0 .. max(0, duration - 3000)`。
  - 视频不足 3 秒：起点固定 0，导出全长，并显示提示「视频不足 3 秒，将导出全长」。
- 封面选择：
  - 第二个 Slider，范围「片段起点 .. 片段终点」，显示当前封面时间。
  - 默认封面 = 片段起点 + 1.5 秒（clamp 到片段终点）；视频不足 1.5 秒时取片段中点。
  - 拖动时防抖 100–150ms 更新缩略图，避免频繁 `MediaMetadataRetriever`。
- 「导出」按钮；导出中禁用并显示进度；失败显示可读错误。
- 可选（构建者决定）：一个「预览片段」按钮，用 ExoPlayer 播放选中的 3 秒；不做也不影响核心需求。

### 5.3 导出实现

- 复用 `LivePhotoExporter`，但把片段长度参数化：
  - `export(video, file, startMs, coverMs)` 内部计算 `clipDurationMs = min(3000, duration - startMs)`。
  - 封面 `coverMs` 必须 clamp 到 `[startMs, startMs + clipDurationMs]`；封面取帧失败时回退到片段第一帧。
  - Transformer 的 `ClippingConfiguration` 使用 `endPositionMs = startMs + clipDurationMs`。
  - 视频不足 3 秒时导出实际长度；进度/提示要与实际长度一致。
- 文件结构（已修复，作为回归约束）：
  - `SOI → APP1 Exif（type 7 UserComment: ASCII\0\0\0oplus_10485792）→ APP1 XMP（GCamera + OpCamera + Container，无 MiCamera/MicroVideo）→ JPEG → EOI → MP4`。
  - `OpCamera:VideoLength` 与 `Container:Item:Length` = 追加的 MP4 字节数；MP4 起点 = 文件长度 - 声明的视频长度。
  - 保留音轨（源视频有音频时）；Media3 Transformer 默认会保留，导出后需验证。
  - 命名 `MVIMG_*.jpg`。
- 导出结果写入 `DCIM/Insta360`，原视频不动。
- 失败回退：Transformer 失败时沿用现有 `MediaMuxer` remux 回退；remux 失败再报错。

### 5.4 边界

- 视频时长未知/0：禁用导出并提示。
- 视频旋转：封面按 `METADATA_KEY_VIDEO_ROTATION` 旋转；片段转码由 Media3 处理方向。导出后封面和片段方向要一致。
- 大视频/高码率：3 秒片段仍可能很大；保持现有 H.264 转码，不做额外码率调整（可视需要设置目标码率，作为构建者决定）。
- 封面帧时间与片段起点相同：允许；显示提示即可。

### 5.5 测试

- JVM：片段/封面 clamp 逻辑、时长不足 3 秒、封面越界、文件字节结构（把 Exif/XMP 构造抽成纯函数/类后测试）。
- 真机：ColorOS 相册动态播放；微信实况开关预览与发送；音频存在；不同视频（横/竖、长短）各测一次。

## 6. 功能三：官方样式水印导出

### 6.1 现状

- 查看器照片底部有独立「导出」按钮，打开 `PhotoExportSheet`。
- `WatermarkExporter` 在当前实现里画的是「∞ + Insta360 Ace Pro」的半透明圆角底条，四角位置。
- 导出会生成 `_WM.jpg`，原图不动。

### 6.2 已确认的样式

参考：
- 官方参考局部：`docs/superpowers/specs/assets/2026-11-08-official-watermark-reference.png`
- 已确认 mockup：`docs/superpowers/specs/assets/2026-11-08-watermark-mockup-crop.png`（整体 `...-watermark-mockup.png`）

样式结构（以 4032×3024 横图为基准，所有尺寸按图片宽度等比缩放）：

- 第一行：
  - `Insta360` 白色 condensed bold，字高约 72px，宽度约 330px。
  - 与 `Ace Pro` 组之间约 30px。
  - `Ace Pro` 组是一个白色描边圆角矩形：高约 92px，圆角半径约 18px。
  - 组内：`Ace` 位于一个白色填充圆角矩形中，文字镂空（透出照片）；`Pro` 为白色文字，位于同一描边矩形内。
  - 建议尺寸：`Ace` 填充宽约 179px（文字 151px + 左右各 14px），`Ace` 与 `Pro` 间距约 14px，`Pro` 宽约 137px，右侧留白约 26px。
- 第二行：
  - `CO-ENGINEERED WITH` 白色大写，字号约 34px，字间距约 4px。
  - 徕卡标：红色圆形 + 白色 Leica 手写体，直径约 116px（比第一行圆角矩形高度长约 1.26 倍）。
  - 徕卡标与第一行圆角矩形右侧间距约 26px。
  - `CO-ENGINEERED WITH` 右端与徕卡标左侧间距 = 一个字符宽度（按小字号 `C` 的宽度，约 20px）。
  - 第二行文字与徕卡标垂直中心对齐。
- 整体：
  - 无背景底条、无边框。
  - 默认底部居中；第一行整体和徕卡标共同构成的水印包围盒在图片水平居中。
  - 参考图底部留白约 85px（3024 高）。

字体：使用 Android 系统 `sans-serif-condensed`（bold 用于第一行，normal 用于第二行）近似官方 condensed 字体；如果真机对比偏差明显，再考虑内置字体文件（构建者决定）。

徕卡标资源：从用户提供的官方参考图裁出透明 PNG，放入 `app/src/main/res/drawable-nodpi/leica_logo.png`（建议 512×512，圆形 alpha 遮罩）。仅用于个人侧载应用，不对外分发。

### 6.3 位置预设

只做 5 个预设，不做自由拖动：

- 底部居中（默认）
- 左上
- 右上
- 左下
- 右下

布局规则：

- 使用水印整体包围盒（第一行 + 第二行/徕卡标）定位。
- 底部居中：包围盒水平居中，底部留白约图片高度的 2.8%（参考 85/3024）。
- 四角：对应边留白约短边的 3%；左右角对齐包围盒左/右边缘。
- 各预设的尺寸比例不变，只改变锚点。
- 横图/竖图都使用同一套比例；竖图建议以短边为基准做缩放上限，避免水印过大（构建者需在真机竖图上校准）。

### 6.4 渲染实现

在 `WatermarkExporter` 内用 `Canvas` 绘制：

1. 解码照片并按 EXIF orientation 旋转到正向；用 `ARGB_8888` 可变位图，避免不必要的整图 copy。
2. 计算布局（纯函数）：
   - `scale = imageWidth / 4032f`（横图）；竖图用短边基准并 clamp。
   - 根据预设计算整体包围盒的左上角。
   - 计算第一行、第二行、徕卡标的矩形。
3. 绘制第一行：
   - `Insta360` 白色。
   - `Ace Pro` 组：先画白色填充的 `Ace` 圆角矩形，再用 `saveLayer` + `PorterDuff.Mode.CLEAR` 把 `Ace` 文字从填充中镂空（透出照片）；恢复后画组描边和白色 `Pro`。
   - 注意层叠顺序：镂空必须在图层内完成，避免把照片本身擦掉。
4. 绘制第二行：
   - `CO-ENGINEERED WITH` 白色，按字符或 `Paint.letterSpacing` 控制字距。
   - 绘制徕卡 PNG，保持透明通道。
5. 以 JPEG quality 95 压缩，写入 MediaStore（`DCIM/Insta360`，`_WM.jpg`），原图不动。
6. 尽量保留源 EXIF（至少 orientation 归正、拍摄时间/机型）；若完整复制 EXIF 成本高，构建者可只保留时间与机型，并在文档中记录限制。

### 6.5 导出入口与编辑器

- 查看器照片底部保留独立「导出」按钮（与顶部「下载」分开）。
- 点击后打开全屏导出编辑器（可以是独立 route，也可以是全屏 sheet，构建者决定）：
  - 照片预览（缩放适配）。
  - 5 个位置预设按钮/Chisel。
  - 选择后预览立即更新。
  - 「导出」按钮；进度、成功、失败提示。
- 导出后提示「已导出带水印照片」，并保留原图。
- 为未来编辑扩展：
  - 定义 `PhotoExportOptions`（位置、将来的裁剪/滤镜参数）。
  - 导出管线按「编辑操作列表 + 最后水印」组织；本轮只实现水印。
  - 后续加裁剪/滤镜时，预览和导出复用同一套 `Canvas`/Bitmap 处理。

### 6.6 内存与质量

- 先用 `BitmapFactory.Options.inJustDecodeBounds` 取尺寸，再按全分辨率解码。
- 不做 `original.copy()` 的额外整图复制；尽量用 `inMutable = true` 原地绘制。
- 若内存不足：
  - 捕获 `OutOfMemoryError`，用 `inSampleSize = 2` 重试，并提示「图片较大，已降低导出分辨率」。
  - Ace Pro 12MP 照片（4032×3024）应走全分辨率路径。
- 导出质量：JPEG 95；保留方向与基本 EXIF。
- 预览与导出使用同一套布局函数，保证所见即所得。

## 7. 协议与代码改动清单

### 协议 / 相机层

- `app/src/main/proto/camera.proto`：新增 `SyncCaptureMode`、`SetSyncCaptureMode`、`GetSyncCaptureMode`、`WindowCropInfo`、`LiveStreamParamsUpdate`、`NotificationSyncCaptureModeUpdate`；如需要再补 `PhotographyOptions` 的 `photo_size_id`/`record_resolution`。
- `CameraCommands`：新增 54、55，以及通知常量 8211、8234（8275 可选）。
- `CameraTransport`：新增通知 Flow；`FakeCameraServer` 与测试用 fake transport 默认返回空流，避免破坏现有测试。
- `CameraProtocolClient`：无序号通知发到通知流；已有响应关联逻辑不变。
- `CameraSession`：新增 `captureMode`、`liveStreamParams`、`setCaptureMode`；`takePicture`/`startRecording` 先切模式；连接后查询 55。
- `CameraGateway`：暴露 `captureMode`、`liveStreamParams`、`setCaptureMode`。
- `LiveViewDecoder`：暴露实际解码宽高（`MediaCodec.getOutputFormat()`），作为 8234 的回退。

### UI 层

- `LiveViewViewModel`：模式、推流参数、切换/拍照/录制状态；按钮防抖与错误提示。
- `LiveViewScreen`：状态区显示照片/视频；预览按实际比例 + 最大高度 + letterbox；按钮忙碌状态。
- `LivePhotoExportDialog`：升级为片段/封面编辑器；时间轴和封面预览。
- `LivePhotoExporter`：片段长度参数化；保持已修复的 Exif/XMP 结构；保留音轨。
- `WatermarkExporter`：官方样式、5 个预设、镂空圆角矩形、徕卡标。
- `PhotoExportSheet` → 全屏/大尺寸导出编辑器；位置预设 chips。
- `ViewerScreen`：照片「导出」按钮保持独立；视频「LIVE」入口保持。
- `app/src/main/res/drawable-nodpi/leica_logo.png`：透明徕卡标资源。

### 测试

- `CameraProtoTest` / 新协议测试：54/55 编解码、8211/8234 解析。
- `CameraSessionTest`：切模式后拍照、切模式后录制、切换失败不执行动作、通知更新比例。
- `LivePhotoExporter`：时间 clamp、Exif/XMP 结构回归。
- `WatermarkLayout`：5 个位置、横竖图、边界不越界（纯 Kotlin 布局测试）。
- 现有 74 个单元测试全部保持通过。

## 8. 测试计划与验收

### 自动化

- `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest` 全绿。
- `JAVA_HOME=/d/JDK17 ./gradlew :app:assembleDebug` 成功。
- 新增纯 JVM 测试覆盖：模式命令序列、通知解析、片段/封面 clamp、水印布局。

### 真机（一加 Ace 3 Pro / ColorOS 16）

实时画面：

1. 相机在视频模式时点「拍照」：自动切照片模式，拍照成功，模式保持照片。
2. 相机在照片模式时点「录制」：自动切视频模式，开始录制；再次点击停止，模式保持视频。
3. 相机切到 4:3 / 16:9：预览框比例跟随，画面不拉伸；按钮位置不跳动。
4. 连续快速点击按钮：不会发出交叉命令，不崩溃。
5. 切换模式后预览中断能自动恢复。

Live 图：

1. 选择一个非默认起点的 3 秒片段和自定义封面，导出。
2. ColorOS 相册显示动态照片并播放。
3. 微信分享显示实况开关且可预览、可发送。
4. 视频不足 3 秒时导出全长且提示正确。
5. 导出文件包含音频（源视频有音频时）。

水印：

1. 五个位置各导出一张，位置符合 mockup。
2. 横图/竖图、12MP/48MP（或最大照片）各测一次，水印比例合理。
3. 原图不变，新文件出现在 `DCIM/Insta360`。
4. 旋转照片导出后方向正确。
5. 与官方参考图对照，文字/圆角矩形/徕卡标比例可接受。

回归：

- 下载、断点续传、缩略图、查看器、设置、诊断页不受影响。
- 除相机 IP 外无互联网请求。

## 9. 风险与限制

| 风险 | 影响 | 应对 |
|---|---|---|
| 命令 54 在 Ace Pro 1 上需要额外字段或存在时序要求 | 模式切换失败 | 真机先做最小验证；必要时等待 8211 通知或增加延迟 |
| 相机不发送 8234 通知 | 比例无法从通知获取 | 回退到解码器实际宽高；再回退 16:9 |
| 相机始终推 16:9，即使拍摄模式是 4:3 | 预览比例仍不匹配 | 按当前拍摄分辨率请求 4:3 推流；不支持则记录为已知限制 |
| 模式切换导致推流重启/短暂黑屏 | 体验差 | 显示“切换模式…”；1 秒无包自动重启预览 |
| 官方字体无法完全复刻 | 水印与官方有小差异 | 用 sans-serif-condensed 近似；必要时内置字体（构建者决定） |
| 徕卡标资源/商标 | 分发风险 | 仅个人侧载使用；不对外分发 |
| 48MP 照片解码 OOM | 导出失败 | 全分辨率优先；OOM 时 inSampleSize=2 重试并提示 |
| 重新编码丢失 EXIF | 拍摄信息丢失 | 至少保留 orientation/时间/机型；完整复制作为增强项 |
| 微信/ColorOS 版本差异 | Live 图识别回归 | 保持已修复的 Exif/XMP 结构；每次改动做真机回归 |

## 10. 已确认决策与留给构建者的决定

### 10.1 已确认的产品决策

1. **非普通子模式**：按下「拍照」/「录制」一律切回普通照片/普通视频，不保留相机原有 HDR/间隔/连拍/AEB 夜景/延时等子模式。
2. **拍照/录制互斥**：录制中禁用「拍照」，拍照进行中禁用「录制」；`busy` 时两者都禁用。不实现“录制中直接拍照”。

### 10.2 构建者可自行决定

以下不影响用户可见行为，构建者可自行决定并在实现中记录：

- 导出编辑器用独立 Compose route 还是全屏 `ModalBottomSheet`。
- Live 图编辑器是否加“预览片段”播放按钮。
- 片段转码是否设置目标码率（当前默认）。
- 竖图水印缩放的具体基准（短边比例/上限）。
- 是否完整复制源 EXIF，还是只保留方向/时间/机型。
- 模式切换后的稳定等待时间（建议 200–300ms，按真机调整）。
- `NotifyCameraSubMode` 是否纳入本轮（仅用于显示子模式时可做）。

## 11. 验收标准（本轮）

- 实时画面：拍照/录制按钮能自动切换相机模式并保持；预览比例跟随实际推流；切换过程有明确状态，不崩溃。
- Live 图：用户能选择 3 秒片段和封面帧；导出文件在 ColorOS 16 相册和微信中均能作为实况照片正常播放。
- 水印：照片有独立导出入口；官方样式在五个预设位置均正确；原图不被修改；横竖图和大图导出可用。
- 现有下载/缩略图/查看器功能无回归；单元测试全绿。
