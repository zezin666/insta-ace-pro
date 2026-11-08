# 水印导出功能验收记录

日期：2026-11-08
任务：Task 6 — 水印导出真机验收与文档
代码提交：`dd3b567da7c6a025b9b3849c8ebe9e998371ccf4`（`feat: add dedicated watermarked photo export editor`）

## 设备与相机

| 项目 | 值 |
|---|---|
| 手机 | OnePlus Ace 3 Pro，型号 `PJX110`，设备代号 `OP5D06L1` |
| 系统 | Android 16（SDK 36），构建 `PJX110_16.0.5.1301(CN01)`，指纹 `OnePlus/PJX110/OP5D06L1:16/UKQ1.231108.001/U.1161b4b_1510d9c_1510d9a:user/release-keys` |
| adb | `767e154a`（USB） |
| App | `com.acegallery`「Ace 相册」，`versionName=1.0`，`versionCode=1`，安装/更新时间 `2026-11-08 13:43` |
| 相机 | Insta360 Ace Pro（EXIF `Make=Arashi Vision`、`Model=Insta360 Ace Pro`），地址 `192.168.42.1`，App 显示「已连接 192.168.42.1」 |
| 相机内容 | 266 个文件（含照片与视频）；2026-11-08 6 项，2026-09-12 48 项，2026-09-11 8 项，2026-09-06 23 项等 |

设备初始处于息屏/锁屏状态。通过 `adb shell input keyevent KEYCODE_WAKEUP` 唤醒并以返回键退出锁屏界面后进入桌面，全程未输入或猜测任何 PIN/密码。

## 构建与测试

```bash
JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest :app:assembleDebug
# BUILD SUCCESSFUL in 2s（产物已存在且为当前 HEAD，任务 UP-TO-DATE）

JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --rerun-tasks
# BUILD SUCCESSFUL in 29s（38 个任务全部重新执行）
```

- 单元测试：21 个测试套件，**80 个测试，0 失败，0 错误，0 跳过**（`app/build/test-results/testDebugUnitTest/*.xml`，时间戳 2026-11-08 13:58）。
- Debug 构建：`BUILD SUCCESSFUL`，APK `app/build/outputs/apk/debug/app-debug.apk`（39,683,256 字节，构建于 13:13，与 HEAD `dd3b567` 一致）。
- 安装：

```bash
/d/Android/Sdk/platform-tools/adb.exe -s 767e154a install -r app/build/outputs/apk/debug/app-debug.apk
# Performing Streamed Install
# Success
```

说明：本任务只做验收与文档，未修改任何 App 代码；APK 即 HEAD 已构建产物。

## 五种水印位置

操作路径：相册 → 打开 `IMG_20261108_124138_476.jpg`（查看器 1/266，4,637,793 B，源图 8064×6048 横图）→ 底部「导出」→ 在「导出水印照片」编辑器中点选水印 chips → 点「导出」。导出成功 toast 为「已导出带水印照片」。五种位置均导出到 `/sdcard/DCIM/Insta360/`，并由 MediaStore 索引在 `DCIM/Insta360/`。

几何测量以 8064×6048 导出图为基准（`scale = 8064/4032 = 2`）：设计边距 `EDGE_MARGIN=85×2=170 px`、Logo 间距 `LOGO_GAP=26×2=52 px`。Logo 红色圆形检测包围盒相对 232 px 的 Logo 位图有约 16–18 px 透明内边距，下表换算时已计入。

| 预设 | 结果 | 导出文件（字节） | 证据与测量 |
|---|---|---|---|
| 底部居中 | 通过 | `IMG_20261108_124138_476_WM.jpg`（3,523,226） | 编辑器截图 `04-export-editor.png`；水印墨迹+Logo 合并包围盒 x=3032–5026，中心 4029，对比图像中心 4032，偏差 **3 px（0.04%）**；底部间距：Logo 位图底边 ≈5878 = 6048−170 |
| 左上 | 通过 | `IMG_20261108_124138_476_WM (1).jpg`（3,540,588） | 编辑器截图 `06-editor-top-left.png`；toast `07-export-top-left.png`；Insta360 墨迹左边界 x=182（设计 overallLeft=170，字形左侧留白约 12 px）；组顶墨迹 y=167（设计 170）；Logo 红圆 bbox (1980,324)–(2176,520) |
| 右上 | 通过 | `IMG_20261108_124138_476_WM (2).jpg`（3,497,907） | 编辑器截图 `08-editor-top-right.png`；Logo 红圆 bbox (7680,323)–(7876,521)，换算位图右边界 ≈7893，设计右边距 8064−170=7894（偏差 1 px） |
| 左下 | 通过 | `IMG_20261108_124138_476_WM (3).jpg`（3,526,217） | 编辑器截图 `09-editor-bottom-left.png`；Insta360 墨迹左边界 x=182；Logo 红圆 bbox (1979,5663)–(2176,5860)，位图底边 ≈5878 = 6048−170 |
| 右下 | 通过 | `IMG_20261108_124138_476_WM (4).jpg`（3,514,877） | 编辑器截图 `10-editor-bottom-right.png`；Logo 红圆 bbox (7679,5663)–(7876,5860)，换算位图右边界 ≈7893，设计 7894 |

公共检查（在底部居中 8064×6048 导出图上测量）：

- **Logo 与圆角矩形分离**：白色圆角矩形（Ace/Pro）墨迹右边界 x=4762；Leica Logo 位图左边界 ≈4813（红圆左 4829 − 资产内边距 16）→ 间距 **51 px**，设计值 `LOGO_GAP=26×2=52 px`，通过。
- **小字距 Logo 一个字宽**：`CO-ENGINEERED WITH` 墨迹右边界 x=4759，距 Logo 位图左边界 4813 为 **54 px**；该 19 字符文本平均字符步进为 48.6 px（墨迹宽 875 / 18），即小字右端约在 Logo 左侧 **1.1 个字符宽**处，通过。
- **全部位置在画面内**：五张导出图中水印墨迹与 Logo 均位于 `[0,8064]×[0,6048]` 内，无裁切。
- **视觉对比**：`compare-export-vs-mockup.png` 将底部居中导出水印与 `docs/superpowers/specs/assets/2026-11-08-watermark-mockup-crop.png` 并排对比；`corners-sheet.png` 展示四个角的水印。Insta360 字标、Ace 白色胶囊与透明镂空字、Pro 描边胶囊、右下方小字以及独立红色 Leica 圆标的位置关系与批准稿一致。

## 横图 / 竖图 / 旋转图

| 场景 | 源 | 结果 | 证据 |
|---|---|---|---|
| 横图（48MP） | `IMG_20261108_124138_476.jpg`，8064×6048，4,637,793 B | 导出 8064×6048，EXIF `Orientation=1`，横向上正确 | `03-viewer.png`、`04-export-editor.png`、`out-bottom-center.jpg` 等 |
| 竖图（EXIF 旋转） | `IMG_20260912_182927_461.jpg`，缓存源文件 8064×6048 但 EXIF `Orientation=6`（应顺时针旋转 90° 显示），5,096,369 B | 查看器（11/266）显示为正向竖图；导出 `IMG_20260912_182927_461_WM.jpg` 为 **6048×8064**、EXIF `Orientation=1`，画面正向、水印在底部且完整；与预览一致 | 查看器 `23-rotated-viewer.png`；编辑器预览 `24-rotated-editor.png`；导出 toast `25-rotated-export-toast.png`；水印裁剪 `crop-rotated-bottom-center.png`；拉取文件 `out-rotated-portrait.jpg` |

竖图导出水印测量（scale=6048/4032=1.5，设计边距 127.5 px）：水印墨迹+Logo 包围盒 x=2274–3770，中心 3022，对比图像中心 3024（偏差 2 px）；红圆底部 y=7923，Logo 位图底边约 7936 ≈ 8064−127.5。预览与导出均为正向竖图，未出现方向错乱。

EXIF 复制：横图与竖图导出均保留 `Make=Arashi Vision`、`Model=Insta360 Ace Pro`，`Orientation` 归一为 1（`WatermarkExporter.copyExif` 行为）。

## 最大照片与 OOM 降采样

- 本次导出的 `IMG_20261108_124138_476.jpg` 为 8064×6048（约 48.7 MP），即 Ace Pro 的最高静态照片分辨率；导出结果保持 **8064×6048 全分辨率**。
- 设备上未触发 `OutOfMemoryError`，`decode()` 未回退到 `inSampleSize=2`，因此用户可见的「图片较大，已降低导出分辨率」未出现（该消息仅在 OOM 回退时显示）。过程无崩溃，App 进程在验收期间保持存活，`logcat --pid` 无 FATAL/Exception。

## 原文件未被修改

| 检查 | 验收前 | 验收后 | 结论 |
|---|---|---|---|
| `DCIM/Insta360/IMG_20260912_183032_462_MP.jpg` | md5 `2dc19201efdbea9ae63a07088e793aa7`，25,959,632 B，mtime 1794107585 | 相同 | 未修改 |
| `DCIM/Insta360/MVIMG_20260912_182129_451.jpg` | md5 `950ac2df9172c35cbe110f7c04967fea`，32,585,277 B，mtime 1794108731 | 相同 | 未修改 |
| 导出源缓存 `ea0ecd…jpg`（横图） | 4,637,793 B，mtime 13:44:54（下载时） | 相同；导出发生在 13:45–13:46 | 未被导出改写 |
| 导出源缓存 `2dae22…jpg`（旋转竖图） | 5,096,369 B，mtime 11:13:08 | 相同；导出发生在 13:55 | 未被导出改写 |
| 查看器中的源文件大小 | 横图 4,637,793 B；竖图 5,096,369 B | 导出后查看器仍显示相同大小 | 源文件大小未变 |

`/sdcard/DCIM/Insta360/` 中只新增了 `*_WM.jpg`（6 个：五种位置各 1 个 + 竖图 1 个），没有新增任何与相机原文件同名的文件；相机侧原文件通过只读 HTTP/协议读取，App 不写回相机路径。

MediaStore 查询确认全部新文件位于 `DCIM/Insta360/`：

```text
IMG_20261108_124138_476_WM.jpg          3,523,226
IMG_20261108_124138_476_WM (1).jpg      3,540,588
IMG_20261108_124138_476_WM (2).jpg      3,497,907
IMG_20261108_124138_476_WM (3).jpg      3,526,217
IMG_20261108_124138_476_WM (4).jpg      3,514,877
IMG_20260912_182927_461_WM.jpg          3,949,520
```

同名多次导出由 MediaStore 自动追加 ` (1)`、` (2)` 等后缀，不覆盖已有文件。

## 证据文件

真机截图与拉取的图片保存在 gitignored 目录 `build/ace-spike/acceptance/`（未提交大图）：

- 流程：`02-gallery.png`（已连接相机）、`03-viewer.png`（底部「导出」）、`04-export-editor.png`（默认底部居中）
- 五种位置编辑器截图：`06-editor-top-left.png`、`08-editor-top-right.png`、`09-editor-bottom-left.png`、`10-editor-bottom-right.png`
- 成功 toast：`07-export-top-left.png`、`25-rotated-export-toast.png`
- 旋转竖图：`23-rotated-viewer.png`、`24-rotated-editor.png`
- 水印对比：`corners-sheet.png`、`compare-export-vs-mockup.png`、`crop-bottom-center.png`、`crop-rotated-bottom-center.png`
- 拉取导出图：`out-bottom-center.jpg`、`out-top-left.jpg`、`out-top-right.jpg`、`out-bottom-left.jpg`、`out-bottom-right.jpg`、`out-rotated-portrait.jpg`

## 缺陷与 UX 观察

无阻塞缺陷，五种位置、横竖图、旋转图、48MP 大图均通过。以下为已知的延迟处理项（本任务未修改代码）：

1. **OOM 降采样横幅只显示 1.5 秒且紧接自动关闭编辑器**（Task 5 延迟项）：本次未触发 OOM，未能真机验证；按代码 `notice = 图片较大，已降低导出分辨率` 后 `delay(1500)` 立即 `onExported()` 关闭编辑器。在真机上该提示可能一闪而过。若后续改为 toast/snackbar 或延长显示时间会更稳妥。
2. **编辑器的无障碍隔离未显式验证**（Task 5 延迟项）：编辑器为全屏覆盖层，关闭按钮有 `contentDescription="关闭"`、预览图有「水印预览」、chips 使用文本语义并有 `BackHandler`；但覆盖层下的 pager 语义未被清除，TalkBack 是否仍可聚焦到被遮挡内容未测试，建议后续补充语义隔离/焦点约束。
3. **矮屏面板无滚动或高度上限**（Task 5 延迟项）：底部控制面板为普通 `Column`，预览区域用 `weight(1f)`。在本机 1264×2780 竖屏下布局正常；在极矮屏或横屏下，面板可能挤压预览或裁切内容，本次未复现，建议后续加高度约束/滚动。
4. **字体观感略有差异（非缺陷）**：导出使用 `sans-serif-condensed`（主字标粗体、小字常规），与批准稿的字宽/字距略有差异；位置、尺寸关系与批准稿一致，且 Task 3 brief 明确指定 condensed 字体，本次判定通过。
5. **重复导出产生副本**：MediaStore 对同名文件追加 ` (n)` 后缀，用户对同一位置重复导出会得到多个文件；这是系统相册写入的预期行为，非本功能缺陷。

## 结论

验收通过（PASS）。App 在 OnePlus Ace 3 Pro / Android 16 上完成五种水印位置、横图、EXIF 旋转竖图与 48MP 大图的真机导出；输出为 `DCIM/Insta360` 下新的 `*_WM.jpg`，原文件大小/时间戳/md5 均未变化；无崩溃，无 OOM 降采样。
