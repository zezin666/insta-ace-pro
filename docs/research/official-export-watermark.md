# 官方 App 导出/水印调研 + 本轮新功能记录

日期：2026-11-08

## 1. 官方 App：导出与下载的差异

结论：**官方 App 的水印只在「导出」时应用，普通「下载」不会加水印。**

- 官方帮助文档明确写明：水印「只有在导出媒体后才可见，下载媒体或拍摄界面不会显示」。
- 水印设置入口：App → 我的 → 水印设置 → 导出水印设置；或在编辑/导出页 → 保存设置 → 水印设置。可选视频/照片水印、样式与位置。
- 限制：仅适用于非 360 的照片/视频，且必须是相机原片；已由 Insta360 Studio 或 App 导出过的文件不能再次加水印。
- 徕卡镜头机型会自动应用徕卡水印。
- 机内防抖视频直接导出可能默认不加；需要先应用至少一项编辑（滤镜/调色，仅裁剪不算）。
- 因此「下载」= 相机原始文件；「导出」= 经过处理的副本（水印/编辑/重编码），两者是不同文件。

来源：
- [Insta360 官方排查文档：导出无水印/无法添加水印](https://onlinemanual.insta360.com/app/zh-cn/problem_troubleshooting/file_export_exception/no-watermark)
- [English version](https://onlinemanual.insta360.com/app/en-us/problem-troubleshooting/file-export-exception/no-watermark)
- [Insta360 水印设置教程（小红书）](https://www.xiaohongshu.com/discovery/item/692eb95a000000001b027e7f)

## 2. 本 App 的实现

### 2.1 区分「下载」与「导出」

- **下载**：原始文件复制到 `DCIM/Insta360`，不改内容。
- **导出**：
  - 照片：查看器底部「水印」按钮 → 生成 `IMG_..._WM.jpg` 副本，右下角叠加「Insta360 Ace Pro」水印，原图不动。
  - 视频：查看器顶部「LIVE」按钮 → 生成 `IMG_..._MP.jpg` 动态照片（见下）。

### 2.2 视频导出 Live 图（MIUI / ColorOS）

生成单文件 Android Motion Photo：

```
SOI → APP1 XMP → APP1 Exif(OPPO 标记) → JPEG 图像 → EOI → MP4
```

- XMP 同时写入：
  - Google `GCamera:MotionPhoto` / `Container:Directory`（Xiaomi 相册识别所需）
  - 兼容旧字段 `GCamera:MicroVideo` / `MicroVideoOffset`
  - OPPO `OpCamera:*`（`MotionPhotoOwner="oplus"`、`OLivePhotoVersion="2"`、`VideoLength`）
- 附加 116 字节 Exif，`UserComment` 写入 `oplus_8388608`（ColorOS/realme 相册识别所必需）。
- 封面取视频 1.5 秒处的帧；视频片段由 Media3 Transformer 裁剪并重编码为 H.264（3 秒），避免原片码率过高导致文件过大。
- 实测：3 秒导出文件约 6.3 MB；文件内 XMP 声明的视频长度与追加 MP4 起点、文件总长完全一致。
- 分享面板中微信「发送给朋友/朋友圈」入口正常出现；微信 8.0.63+ 安卓端支持发送实况照片（需在发送预览页手动打开「实况」开关，且走「图片」入口，不能走「文件」）。

### 2.3 实时画面

- TCP 6666 命令 1/2 启停预览，命令 3 拍照，命令 4/5 开始/停止录制。
- 流包为 12 字节头 + Annex-B H.264/H.265；实测 Ace Pro 1 为 H.264，MediaCodec 渲染正常。
- 已验证：预览出画面、拍照后相机文件数 +1、录制开始/停止返回成功。

### 2.4 徕卡 Logo 素材

- `app/src/main/res/drawable-nodpi/leica_logo.png`：512×512 透明 PNG。由用户提供的官方水印参考图 `docs/superpowers/specs/assets/2026-11-08-official-watermark-reference.png` 裁剪，经圆形 Alpha 蒙版与高质量放大生成，四角完全透明。
- 该素材仅用于本 App（个人侧载使用，包名 `com.acegallery`），不对外分发。

## 3. 已知限制 / 后续

- 本机 MIUI（Redmi K40）相册未显示第三方 Live 图的「动态照片」标记；文件结构符合 MIUI 识别规范，较新 HyperOS / ColorOS 待实测。微信分享入口已确认可用。
- 视频水印导出（重编码并叠加动态水印）尚未实现；当前「导出」覆盖照片水印与视频→Live 图。
- 官方水印使用其品牌素材与样式；本实现使用文字水印「Insta360 Ace Pro」，并内置裁剪自官方参考图的徕卡 Logo（见 2.4）。
- 录制是否落盘取决于相机当前拍照/录像模式；实时画面页提供基础拍照与录制按钮。
