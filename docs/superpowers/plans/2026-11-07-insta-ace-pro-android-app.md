# Insta360 Ace Pro 第三方安卓相册 App 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Android 上实现一个轻量第三方相册 App：连接 Insta360 Ace Pro 的 WiFi 后浏览、预览并下载相机内的照片和视频。

**Architecture:** 单模块 Kotlin/Compose 应用。TCP 6666 的「自定义帧 + protobuf」负责文件列表与缩略图，HTTP 80 负责文件下载和视频流；所有相机 socket 通过 Android `Network.bindSocket` 绑定到相机 WiFi。UI 采用 MVVM + StateFlow，下载采用前台服务 + 串行队列。

**Tech Stack:** Kotlin 2.1、Jetpack Compose (Material 3)、Hilt、OkHttp 4.12、Media3 1.5、protobuf-javalite、DataStore、JUnit4 + MockWebServer。

**Spec:** `docs/superpowers/specs/2026-11-07-insta-ace-pro-android-app-design.md`

## Global Constraints

- 包名 `com.acegallery`，App 名称「Ace 相册」，界面文案中文。
- `minSdk 29`、`compileSdk 35`、`targetSdk 35`；JDK 17；Gradle 8.11.1；Android SDK 位于 `D:\Android\Sdk`。
- 版本基线（全部为已验证存在的稳定版）：AGP 8.7.3、Kotlin 2.1.0、KSP 2.1.0-1.0.29、Compose BOM 2024.12.01、Hilt 2.53.1、OkHttp 4.12.0、Media3 1.5.1、Coil 3.0.4、DataStore 1.1.1、protobuf-javalite 4.29.1、protobuf-gradle-plugin 0.9.4。
- 所有发往 `192.168.42.1` 的请求必须绑定相机 WiFi 的 `Network`；App 不得向互联网发送任何业务请求。
- v1 只读：不删除、不修改相机文件，不提供拍摄控制。
- 下载串行执行，失败重试 3 次（1s/2s/4s）；临时文件放 `filesDir/downloads/`，不用 `cacheDir`。
- 每个任务先写失败测试再实现；每个任务结束必须提交。
- 单元测试必须能在 JVM 上运行（Android 专属行为用接口隔离 + 手动验证）。

## Review Focus

- **下载中 WiFi 断开/相机重连**：必须从临时文件的现有字节继续下载，不能从头重来或产生重复文件。
- **文件列表分页异常**（返回空页、重复页、`total_count` 不准）：必须终止翻页、去重，不能死循环。
- **无法解析的文件名/未知格式**：仍应出现在列表里（OTHER 或隐藏规则明确），不能崩溃或静默丢失。
- **超大文件与超大卡**（4GB+ 视频、1000+ 文件）：偏移量必须用 `Long`，下载必须流式复制，不能把整个文件读进内存。
- **相机返回非 200、畸形帧或未知通知**：请求必须超时/抛出可读异常，接收循环必须继续存活，不能卡死或崩溃。

---

### Task 0: 真机协议探测工具

**Files:**
- Create: `tools/probe/probe.py`
- Create: `tools/probe/test_probe.py`
- Create (运行后): `docs/protocol/ace-pro-1-notes.md`
- Create (运行后): `docs/protocol/report.json`

**Interfaces:**
- Produces: 探测结论（命令码、分页上限、uri 形态、缩略图格式、HTTP 行为），供 Task 3–12 使用。若实测与文档不一致，先修正 spec 第 5.2/5.4 节再继续。

- [ ] **Step 1: 写失败的编码单元测试**

`tools/probe/test_probe.py`，用标准库 `unittest`：

```python
import unittest
from probe import build_get_file_list, frame, header

class EncodingTest(unittest.TestCase):
    def test_get_file_list_bytes(self):
        self.assertEqual(build_get_file_list(2, 0, 100), bytes.fromhex("080210001864"))

    def test_frame_prefixes_length(self):
        self.assertEqual(frame(bytes.fromhex("050000")), bytes.fromhex("07000000050000"))

    def test_header_layout(self):
        self.assertEqual(header(13, 1), bytes.fromhex("0400000d0002010000800000"))
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd tools/probe && python -m unittest test_probe.py -v`
Expected: FAIL，`ModuleNotFoundError` 或 `ImportError`。

- [ ] **Step 3: 实现 `tools/probe/probe.py` 的编码与基础网络函数**

标准库实现，不使用第三方包。必须提供：

```python
def encode_varint(value: int) -> bytes
def build_get_file_list(media_type: int, start: int, limit: int) -> bytes
def frame(payload: bytes) -> bytes                 # 4 字节小端长度（含这 4 字节）
def header(command: int, seq: int) -> bytes        # 12 字节：04 00 00 | cmd u16le | 02 | seq u24le | 80 00 00
class CameraSocket:
    def __init__(self, host: str, port: int = 6666)
    def open(self) -> None                          # 发送 sync + keepalive
    def send_request(self, command: int, payload: bytes, timeout: float = 5.0) -> bytes
    def close(self) -> None
```

探测内容（全部只读，禁止发送删除命令）：

1. HTTP：`GET /`、`GET /DCIM/`、`GET /osc/info`、`GET /osc/state`，记录状态码、响应头、正文前 512 字节。
2. TCP：连接 6666，发送 sync（`06 00 00` + `syNceNdinS`）与 keepalive（`05 00 00`），记录相机回包。
3. 命令 13：`media_type=2, start=0, limit=100`，解码 `repeated string uri = 1` 与 `uint32 total_count = 2`；再试 `limit=200/500`，记录实际上限；继续翻页直到空页或达到 total。
4. 抽取前 10 个 uri，记录相对路径/完整 URL 形态、扩展名、前缀。
5. 对第一个文件发 `HEAD`，再发 `Range: bytes=0-1048575` 下载 1MB，记录状态码与 `Content-Range`。
6. 命令 11：对第一个照片和第一个视频，分别用 `extra_type=0/1/2/3` 请求，记录响应前 64 字节、长度、以及是否像 protobuf。
7. 记录相机是否要求命令 39 授权（先不发送，能列文件即不需要）。
8. 结果写入 `docs/protocol/report.json`，并生成 `docs/protocol/ace-pro-1-notes.md`；原始抓包可放 `tools/probe/out/`（已忽略）。

- [ ] **Step 4: 运行单元测试确认通过**

Run: `cd tools/probe && python -m unittest test_probe.py -v`
Expected: PASS（3 tests）。

- [ ] **Step 5: 真机运行探测**

用户操作：电脑连接相机 WiFi（SSID 以 `Insta360` 开头，密码见相机屏幕，默认 `88888888`），然后：

```bash
python tools/probe/probe.py --host 192.168.42.1 --out docs/protocol
```

Expected: 生成报告；`report.json` 中 `get_file_list.uris` 非空，`http.range.status == 206`。

- [ ] **Step 6: 把实测结论补进 spec**

若命令码、分页上限、uri 形态、缩略图格式与 spec 不同，修改 `docs/superpowers/specs/2026-11-07-insta-ace-pro-android-app-design.md` 的 5.2/5.4 节，并在本计划对应任务里改常量。

- [ ] **Step 7: 提交**

```bash
git add tools/probe docs/protocol
git commit -m "chore: add Ace Pro protocol probe tool and results"
```

---

### Task 1: 工程脚手架与 App 壳

**Files:**
- Create: `.gitignore`, `local.properties`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/acegallery/AceGalleryApp.kt`, `app/src/main/java/com/acegallery/MainActivity.kt`, `app/src/main/java/com/acegallery/ui/theme/Theme.kt`, `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/acegallery/SmokeTest.kt`

**Interfaces:**
- Produces: 可构建、可安装的空壳 App；后续所有任务在此工程内添加代码。

- [ ] **Step 1: 初始化仓库与忽略文件**

```bash
git init
```

`.gitignore` 包含：`.gradle/`、`build/`、`app/build/`、`local.properties`、`.idea/`、`*.iml`、`tools/gradle/`、`tools/probe/out/`、`*.apk`。

- [ ] **Step 2: 引导 Gradle Wrapper（本机没有全局 Gradle）**

```bash
mkdir -p tools/gradle
curl -L -o /tmp/gradle-8.11.1-bin.zip https://services.gradle.org/distributions/gradle-8.11.1-bin.zip
unzip -q /tmp/gradle-8.11.1-bin.zip -d tools/gradle
tools/gradle/gradle-8.11.1/bin/gradle wrapper --gradle-version 8.11.1
```

Expected: 生成 `gradlew`、`gradlew.bat`、`gradle/wrapper/`。

- [ ] **Step 3: 写失败测试**

`app/src/test/java/com/acegallery/SmokeTest.kt`：

```kotlin
package com.acegallery

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test fun `junit runs`() = assertEquals(4, 2 + 2)
}
```

- [ ] **Step 4: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.SmokeTest`
Expected: FAIL（工程尚未配置）。

- [ ] **Step 5: 配置 Gradle 与 App 壳**

`gradle/libs.versions.toml` 使用 Global Constraints 的版本基线。`app/build.gradle.kts` 启用：

- 插件：`com.android.application`、`org.jetbrains.kotlin.android`、`org.jetbrains.kotlin.plugin.compose`、`com.google.devtools.ksp`、`com.google.dagger.hilt.android`、`com.google.protobuf`
- `buildFeatures { compose = true; buildConfig = true }`
- `compileOptions` / `kotlinOptions` 均用 Java 17
- protobuf：`protoc { artifact = "com.google.protobuf:protoc:4.29.1" }`，`builtins { create("java") { option("lite") } }`

依赖：core-ktx、activity-compose、lifecycle-runtime-compose、Compose BOM + material3、navigation-compose、Hilt + KSP + `androidx.hilt:hilt-navigation-compose`、OkHttp、Media3（exoplayer/ui/datasource-okhttp）、Coil、DataStore、protobuf-javalite；测试 junit、coroutines-test、mockwebserver。

`MainActivity` 加 `@AndroidEntryPoint`，用 `setContent { AceGalleryTheme { Text("Ace 相册") } }`；`AceGalleryApp` 加 `@HiltAndroidApp`，Manifest 注册 Application 和 Activity，并声明 `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`。

`local.properties`：`sdk.dir=D:\\Android\\Sdk`。

- [ ] **Step 6: 构建并运行测试**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 安装到手机验证**

连接手机（开启 USB 调试），Run:

```bash
/d/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: 启动后显示「Ace 相册」。

- [ ] **Step 8: 提交**

```bash
git add .
git commit -m "chore: scaffold Android Compose app"
```

---

### Task 2: 相机文件模型与文件名解析

**Files:**
- Create: `app/src/main/java/com/acegallery/model/CameraFile.kt`
- Create: `app/src/main/java/com/acegallery/model/FileNameParser.kt`
- Create: `app/src/main/java/com/acegallery/model/CameraPath.kt`
- Test: `app/src/test/java/com/acegallery/model/FileNameParserTest.kt`
- Test: `app/src/test/java/com/acegallery/model/CameraPathTest.kt`

**Interfaces:**
- Produces:
  - `enum class CameraFileKind { PHOTO, VIDEO, OTHER }`
  - `data class CameraFile(val uri: String, val path: String, val name: String, val kind: CameraFileKind, val sizeBytes: Long? = null, val capturedAt: Instant? = null)`
  - `object FileNameParser { data class Parsed(val kind: CameraFileKind, val capturedAt: Instant?, val hidden: Boolean); fun parse(name: String, zone: ZoneId = ZoneId.systemDefault()): Parsed }`
  - `object CameraPath { fun pathOf(uri: String): String; fun fileName(path: String): String }`

- [ ] **Step 1: 写失败测试**

`FileNameParserTest` 断言：

```kotlin
@Test fun `parses video name`() {
    val p = FileNameParser.parse("VID_20260101_120000_001.mp4", ZoneOffset.UTC)
    assertEquals(CameraFileKind.VIDEO, p.kind)
    assertEquals(Instant.parse("2026-01-01T12:00:00Z"), p.capturedAt)
    assertFalse(p.hidden)
}

@Test fun `parses photo name with lens segment`() {
    val p = FileNameParser.parse("IMG_20260101_120000_00_001.jpg", ZoneOffset.UTC)
    assertEquals(CameraFileKind.PHOTO, p.kind)
}

@Test fun `hides lrv and thm`() {
    assertTrue(FileNameParser.parse("LRV_20260101_120000_001.lrv").hidden)
    assertTrue(FileNameParser.parse("THM_20260101_120000_001.thm").hidden)
}

@Test fun `unknown name is other and not hidden`() {
    val p = FileNameParser.parse("something_weird.bin")
    assertEquals(CameraFileKind.OTHER, p.kind)
    assertNull(p.capturedAt)
    assertFalse(p.hidden)
}
```

`CameraPathTest` 断言：

```kotlin
@Test fun `extracts path from absolute url`() =
    assertEquals("/DCIM/Camera01/VID_1.mp4", CameraPath.pathOf("http://192.168.42.1/DCIM/Camera01/VID_1.mp4"))

@Test fun `keeps relative path`() =
    assertEquals("/DCIM/Camera01/VID_1.mp4", CameraPath.pathOf("DCIM/Camera01/VID_1.mp4"))

@Test fun `extracts filename`() =
    assertEquals("VID_1.mp4", CameraPath.fileName("/DCIM/Camera01/VID_1.mp4"))
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.model.*`
Expected: FAIL，找不到类。

- [ ] **Step 3: 实现模型与解析**

- 正则：`^(VID|IMG|LRV|THM|PRO_VID|PRO_LRV|PRO_IMG|LIV)_(\d{8})_(\d{6})(?:_(\d{2}))?_(\d{3})`，忽略大小写。
- 扩展名：视频 `.mp4/.mov/.insv/.insp`；照片 `.jpg/.jpeg/.dng`；隐藏 `.lrv/.thm/.list/.log/.txt/.bin`。
- 无匹配时：扩展名决定 kind，否则 OTHER；`capturedAt = null`。
- 时间按传入 `zone` 把文件名中的本地时间转成 `Instant`。
- `CameraPath.pathOf`：含 `://` 时用 `java.net.URI` 取 `path`，否则补前导 `/`。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.model.*`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/acegallery/model app/src/test/java/com/acegallery/model
git commit -m "feat: add camera file model and filename parser"
```

---

### Task 3: TCP 帧与消息头编解码

**Files:**
- Create: `app/src/main/java/com/acegallery/camera/CameraFrame.kt`
- Create: `app/src/main/java/com/acegallery/camera/MessageCodec.kt`
- Create: `app/src/main/java/com/acegallery/camera/CameraCommands.kt`
- Create: `app/src/main/java/com/acegallery/camera/CameraProtocolException.kt`
- Test: `app/src/test/java/com/acegallery/camera/CameraFrameTest.kt`

**Interfaces:**
- Produces:
  - `object CameraFrame { fun encode(payload: ByteArray): ByteArray; fun read(input: InputStream): ByteArray? }`
  - `data class MessageHeader(val command: Int, val seq: Int, val responseCode: Int)`
  - `object MessageCodec { fun encodeHeader(command: Int, seq: Int): ByteArray; fun parseHeader(bytes: ByteArray): MessageHeader }`
  - `object CameraCommands { const val GET_FILE_EXTRA = 11; const val DELETE_FILES = 12; const val GET_FILE_LIST = 13; const val RESPONSE_OK = 200 }`
  - `class CameraProtocolException(val responseCode: Int, message: String) : IOException(message)`

- [ ] **Step 1: 写失败测试**

先在 `app/src/test/java/com/acegallery/TestHex.kt` 放一个辅助：`fun bytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()`，再写：

```kotlin
@Test fun `frame prefixes little endian length`() =
    assertArrayEquals(bytes("07000000050000"), CameraFrame.encode(bytes("050000")))

@Test fun `read returns one frame then null on eof`() {
    val input = ByteArrayInputStream(bytes("07000000050000"))
    assertArrayEquals(bytes("050000"), CameraFrame.read(input))
    assertNull(CameraFrame.read(input))
}

@Test fun `header encodes command and 24 bit seq`() =
    assertArrayEquals(bytes("0400000d0002010000800000"), MessageCodec.encodeHeader(13, 1))

@Test fun `header parses response code`() {
    val h = MessageCodec.parseHeader(bytes("040000c80002010000800000"))
    assertEquals(200, h.responseCode)
    assertEquals(1, h.seq)
}

@Test(expected = IOException::class)
fun `rejects frame length below four`() {
    CameraFrame.read(ByteArrayInputStream(bytes("03000000")))
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.camera.CameraFrameTest`
Expected: FAIL。

- [ ] **Step 3: 实现编解码**

- `CameraFrame.read`：循环读满 4 字节；首字节即 EOF 返回 `null`；长度 `< 4` 或 `> 16*1024*1024` 抛 `IOException("bad frame length")`；再读 `length-4` 字节。
- `MessageCodec.parseHeader`：要求 `size >= 12`；`command = bytes[3] | bytes[4]<<8`；`seq = bytes[6] | bytes[7]<<8 | bytes[8]<<16`；`responseCode` 同样取 offset 3 的 u16。
- 所有多字节字段小端。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.camera.CameraFrameTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/acegallery/camera app/src/test/java/com/acegallery/camera
git commit -m "feat: add camera TCP frame codec"
```

---

### Task 4: Protobuf 消息定义

**Files:**
- Create: `app/src/main/proto/camera.proto`
- Test: `app/src/test/java/com/acegallery/camera/CameraProtoTest.kt`

**Interfaces:**
- Produces: `com.acegallery.proto.CameraProto` 下的 `GetFileList`、`GetFileListResp`、`GetFileExtra`、`DeleteFiles`、`DeleteFilesResp`、`MediaType`。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `encodes get file list`() {
    val msg = CameraProto.GetFileList.newBuilder()
        .setMediaType(CameraProto.MediaType.VIDEO_AND_PHOTO)
        .setStart(100).setLimit(100).build()
    assertArrayEquals(bytes("080210641864"), msg.toByteArray())
}

@Test fun `parses file list response`() {
    // field1 uri="a", field1 uri="b", field2 total=2
    val resp = CameraProto.GetFileListResp.parseFrom(bytes("0a01610a01621002"))
    assertEquals(listOf("a", "b"), resp.uriList)
    assertEquals(2, resp.totalCount)
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.camera.CameraProtoTest`
Expected: FAIL，找不到 `CameraProto`。

- [ ] **Step 3: 写 `.proto` 并配置生成**

`app/src/main/proto/camera.proto`：

```proto
syntax = "proto3";
package insta360.messages;
option java_package = "com.acegallery.proto";
option java_outer_classname = "CameraProto";

enum MediaType { VIDEO = 0; PHOTO = 1; VIDEO_AND_PHOTO = 2; DNG = 3; MP4 = 4; JPG = 5; INS_DATA = 6; }
message GetFileList { MediaType media_type = 1; uint32 start = 2; uint32 limit = 3; }
message GetFileListResp { repeated string uri = 1; uint32 total_count = 2; }
message GetFileExtra { string uri = 1; uint32 extra_type = 2; }
message DeleteFiles { repeated string uri = 1; }
message DeleteFilesResp { repeated string fail_uri = 1; }
```

确认 `app/build.gradle.kts` 的 protobuf 块已按 Task 1 配置，生成 Java lite 类。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.camera.CameraProtoTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/proto app/src/test/java/com/acegallery/camera/CameraProtoTest.kt
git commit -m "feat: add camera protobuf messages"
```

---

### Task 5: TCP 协议客户端

**Files:**
- Create: `app/src/main/java/com/acegallery/camera/CameraTransport.kt`
- Create: `app/src/main/java/com/acegallery/camera/CameraProtocolClient.kt`
- Test: `app/src/test/java/com/acegallery/camera/CameraProtocolClientTest.kt`
- Test helper: `app/src/test/java/com/acegallery/camera/FakeCameraServer.kt`

**Interfaces:**
- Produces:
  - `interface CameraTransport { fun start() {}; fun isAlive(): Boolean = true; fun request(command: Int, payload: ByteArray, timeoutMs: Long = 5000): ByteArray; fun close() }`
  - `class CameraProtocolTimeoutException(message: String) : IOException(message)`
  - `class CameraProtocolClient(socket: Socket, scope: CoroutineScope, keepAliveMs: Long = 2000) : CameraTransport`
  - `override fun start()`：发送 sync + keepalive，启动接收与保活协程。

- [ ] **Step 1: 写失败测试与假相机**

`FakeCameraServer` 用 `ServerSocket(0)`，行为：

- 接受连接后读取 sync 帧，断言 payload == `06 00 00` + `"syNceNdinS"`。
- 读取 keepalive。
- 收到 command 13 的请求后，回一帧：12 字节头（command=200、同一 seq）+ `GetFileListResp(uri=["/DCIM/a.mp4"], total=1)`。
- 可切换为返回 responseCode=500 或永不响应。

测试：

```kotlin
@Test fun `request returns payload for matching sequence`() = runTest {
    FakeCameraServer().use { server ->
        val client = CameraProtocolClient(server.socket(), backgroundScope)
        client.start()
        val body = client.request(CameraCommands.GET_FILE_LIST, CameraProto.GetFileList.getDefaultInstance().toByteArray())
        val resp = CameraProto.GetFileListResp.parseFrom(body)
        assertEquals(listOf("/DCIM/a.mp4"), resp.uriList)
    }
}

@Test fun `non 200 response throws`() = runTest {
    // server returns code 500
    assertFailsWith<CameraProtocolException> { client.request(...) }
}

@Test fun `timeout when camera never answers`() = runTest {
    assertFailsWith<CameraProtocolTimeoutException> { client.request(..., timeoutMs = 200) }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.camera.CameraProtocolClientTest`
Expected: FAIL。

- [ ] **Step 3: 实现客户端**

- 发送：`CameraFrame.encode(MessageCodec.encodeHeader(command, seq) + payload)`。
- `seq` 从 0 自增，24 位回绕。
- 接收循环：`CameraFrame.read` 后看首字节：
  - `0x04`：解析头；按 `seq` 找 `CompletableDeferred<ByteArray>`；responseCode != 200 时用 `CameraProtocolException` 完成；无匹配则当通知忽略。
  - `0x05`/`0x06`：控制包，忽略。
  - 其他：记录并继续。
- 保活：每 `keepAliveMs` 发一次 `05 00 00`。
- `request` 用 `withTimeout(timeoutMs)`，超时抛 `CameraProtocolTimeoutException`（新增，继承 `IOException`）。
- `close` 取消 scope、关闭 socket、以异常完成所有 pending。
- 接收循环遇到 `IOException` 时完成所有 pending、把 `isAlive` 置为 false 并退出；`close` 同样置 false。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.camera.CameraProtocolClientTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/acegallery/camera app/src/test/java/com/acegallery/camera
git commit -m "feat: add camera TCP protocol client"
```

---

### Task 6: 相机网络发现与绑定

**Files:**
- Create: `app/src/main/java/com/acegallery/network/CameraNetwork.kt`
- Create: `app/src/main/java/com/acegallery/network/AndroidCameraNetwork.kt`
- Test: `app/src/test/java/com/acegallery/network/CameraNetworkSelectorTest.kt`

**Interfaces:**
- Produces:
  - `data class NetworkCandidate(val isWifi: Boolean, val reachesCamera: Boolean)`
  - `object CameraNetworkSelector { fun select(candidates: List<NetworkCandidate>): Int? }`
  - `interface CameraNetworkFinder { suspend fun find(host: String, port: Int): Network? }`
  - `class AndroidCameraNetworkFinder(context: Context) : CameraNetworkFinder`
  - `class BoundSocketFactory(private val network: Network) : SocketFactory`
  - `object CameraHttpClientFactory { fun create(network: Network): OkHttpClient }`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `selects first wifi that reaches camera`() {
    val candidates = listOf(
        NetworkCandidate(true, false),
        NetworkCandidate(false, true),
        NetworkCandidate(true, true),
    )
    assertEquals(2, CameraNetworkSelector.select(candidates))
}

@Test fun `returns null when nothing reaches camera`() {
    assertNull(CameraNetworkSelector.select(listOf(NetworkCandidate(true, false))))
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.network.CameraNetworkSelectorTest`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `AndroidCameraNetworkFinder.find`：遍历 `ConnectivityManager.allNetworks`，对每个 `TRANSPORT_WIFI` 网络，在 IO 线程创建 `Socket`、`network.bindSocket(socket)`、`connect(InetSocketAddress(host, port), 800)`；成功则返回该 `Network`。用 `NetworkCandidate` 收集后交给 selector。
- `BoundSocketFactory.createSocket()`：`Socket().also { network.bindSocket(it) }`。
- `CameraHttpClientFactory`：OkHttpClient.Builder，超时 connect 5s/read 30s，`.socketFactory(BoundSocketFactory(network))`。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.network.CameraNetworkSelectorTest`
Expected: PASS。

- [ ] **Step 5: 真机手动验证**

在 Task 1 的 App 壳里临时加一个按钮调用 `AndroidCameraNetworkFinder.find("192.168.42.1", 6666)`，装到手机、连接相机 WiFi，Logcat 应打印找到的 `Network`。验证后移除临时按钮。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/acegallery/network app/src/test/java/com/acegallery/network
git commit -m "feat: add camera wifi network discovery and binding"
```

---

### Task 7: 文件列表仓库（分页）

**Files:**
- Create: `app/src/main/java/com/acegallery/repository/CameraFileRepository.kt`
- Test: `app/src/test/java/com/acegallery/repository/CameraFileRepositoryTest.kt`

**Interfaces:**
- Consumes: `CameraTransport`、`CameraProto`、`CameraFile`、`FileNameParser`、`CameraPath`。
- Produces:
  - `class CameraFileRepository(private val transport: CameraTransport, private val pageSize: Int = 100)`
  - `suspend fun listFiles(): List<CameraFile>`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `collects all pages and dedupes`() = runTest {
    val transport = FakeTransport(listOf(
        resp(listOf("/DCIM/a.mp4"), total = 3),
        resp(listOf("/DCIM/b.jpg", "/DCIM/a.mp4"), total = 3),
        resp(listOf("/DCIM/c.mp4"), total = 3),
        resp(emptyList(), total = 3),
    ))
    val files = CameraFileRepository(transport, pageSize = 2).listFiles()
    assertEquals(listOf("a.mp4", "b.jpg", "c.mp4"), files.map { it.name })
}

@Test fun `stops after empty first page`() = runTest {
    val files = CameraFileRepository(FakeTransport(listOf(resp(emptyList(), 0)))).listFiles()
    assertTrue(files.isEmpty())
}

@Test fun `filters hidden files`() = runTest {
    val files = CameraFileRepository(FakeTransport(listOf(
        resp(listOf("/DCIM/a.mp4", "/DCIM/LRV_a.lrv", "/DCIM/THM_a.thm"), 3)
    ))).listFiles()
    assertEquals(listOf("a.mp4"), files.map { it.name })
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.repository.CameraFileRepositoryTest`
Expected: FAIL。

- [ ] **Step 3: 实现**

- 请求体：`GetFileList.newBuilder().setMediaType(VIDEO_AND_PHOTO).setStart(start).setLimit(pageSize)`。
- 循环最多 100 页；每页 `start += resp.uriCount`；`resp.uriCount == 0` 或 `start >= resp.totalCount` 时停止。
- 用 `LinkedHashMap<String, CameraFile>` 按归一化 `path` 去重，保留首次出现顺序。
- `CameraFile` 的 `path = CameraPath.pathOf(uri)`、`name = CameraPath.fileName(path)`、`kind/capturedAt` 来自 `FileNameParser`；隐藏文件丢弃。
- `sizeBytes` 先留 `null`（Task 8 补）。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.repository.CameraFileRepositoryTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/acegallery/repository app/src/test/java/com/acegallery/repository
git commit -m "feat: add paginated camera file list repository"
```

---

### Task 8: HTTP 文件源（HEAD / Range / 下载）

**Files:**
- Create: `app/src/main/java/com/acegallery/network/CameraFileSource.kt`
- Create: `app/src/main/java/com/acegallery/network/HttpCameraFileSource.kt`
- Test: `app/src/test/java/com/acegallery/network/HttpCameraFileSourceTest.kt`

**Interfaces:**
- Produces:
  - `interface CameraFileSource { fun size(path: String): Long?; fun download(path: String, dest: File, offset: Long = 0, onProgress: (Long) -> Unit = {}): Long; fun readRange(path: String, offset: Long, length: Int): ByteArray }`
  - `class HttpCameraFileSource(private val baseUrl: String, private val client: OkHttpClient) : CameraFileSource`
  - `fun interface CameraFileSourceProvider { suspend fun get(): CameraFileSource }`

- [ ] **Step 1: 写失败测试（MockWebServer）**

```kotlin
@Test fun `head returns content length`() {
    server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Length", "1000"))
    assertEquals(1000L, source.size("/DCIM/a.mp4"))
}

@Test fun `download resumes with range header`() {
    server.enqueue(MockResponse().setResponseCode(206).setBody("world"))
    val dest = File(tempDir, "a.part").apply { writeText("hello") }
    source.download("/DCIM/a.mp4", dest, offset = 5)
    assertEquals("helloworld", dest.readText())
    assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
}

@Test fun `server ignoring range restarts from zero`() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("abc"))
    val dest = File(tempDir, "a.part").apply { writeText("old") }
    source.download("/DCIM/a.mp4", dest, offset = 3)
    assertEquals("abc", dest.readText())
}

@Test fun `read range returns requested bytes`() {
    server.enqueue(MockResponse().setResponseCode(206).setBody("0123456789"))
    assertArrayEquals("0123".toByteArray(), source.readRange("/DCIM/a.jpg", 0, 4))
}

@Test fun `range header supports offsets beyond 2gb`() {
    server.enqueue(MockResponse().setResponseCode(206).setBody("x"))
    val dest = File(tempDir, "big.part")
    source.download("/DCIM/big.mp4", dest, offset = 5_000_000_000L)
    assertEquals("bytes=5000000000-", server.takeRequest().getHeader("Range"))
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.network.HttpCameraFileSourceTest`
Expected: FAIL。

- [ ] **Step 3: 实现**

- URL：`baseUrl.trimEnd('/') + path`（`path` 以 `/` 开头）。
- `size`：HEAD，`Content-Length` 转 `Long`，失败返回 `null`。
- `download`：
  - `offset > 0` 时设 `Range: bytes=$offset-`。
  - 响应 206 → append；响应 200 且 `offset > 0` → 截断 `dest` 并从头写；响应 416 → 若 `dest.length()` 已达 `Content-Range` 总量则视为完成，否则抛异常。
  - 用 8KB buffer 流式复制并回调累计字节；返回新的总长度。
  - 所有长度/偏移用 `Long`。
- `readRange`：`Range: bytes=$offset-${offset + length - 1}`，返回响应体字节。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.network.HttpCameraFileSourceTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/acegallery/network app/src/test/java/com/acegallery/network
git commit -m "feat: add resumable HTTP camera file source"
```

---

### Task 9: MediaStore 落库

**Files:**
- Create: `app/src/main/java/com/acegallery/storage/MediaStoreTarget.kt`
- Create: `app/src/main/java/com/acegallery/storage/MediaStoreWriter.kt`
- Create: `app/src/main/java/com/acegallery/storage/AndroidMediaStoreWriter.kt`
- Test: `app/src/test/java/com/acegallery/storage/MediaStoreTargetTest.kt`

**Interfaces:**
- Produces:
  - `enum class MediaStoreCollection { VIDEO, IMAGE, DOWNLOAD }`
  - `data class MediaStoreTarget(val collection: MediaStoreCollection, val relativePath: String, val mimeType: String)`
  - `object MediaStoreTargets { fun forFile(file: CameraFile): MediaStoreTarget }`
  - `interface MediaStoreWriter { fun createPending(file: CameraFile): Uri; fun copyInto(uri: Uri, source: File); fun publish(uri: Uri); fun delete(uri: Uri); fun findDownloaded(file: CameraFile): Uri? }`
  - `class AndroidMediaStoreWriter(context: Context) : MediaStoreWriter`
  - `app/src/main/java/com/acegallery/di/StorageModule.kt`：`@Provides @Singleton MediaStoreWriter`

- [ ] **Step 1: 写失败测试（只测纯映射，不引用 Android 类）**

```kotlin
@Test fun `video goes to video collection under DCIM Insta360`() {
    val t = MediaStoreTargets.forFile(file("a.mp4", CameraFileKind.VIDEO))
    assertEquals(MediaStoreCollection.VIDEO, t.collection)
    assertEquals("DCIM/Insta360", t.relativePath)
    assertEquals("video/mp4", t.mimeType)
}

@Test fun `photo goes to image collection`() {
    val t = MediaStoreTargets.forFile(file("a.jpg", CameraFileKind.PHOTO))
    assertEquals(MediaStoreCollection.IMAGE, t.collection)
    assertEquals("image/jpeg", t.mimeType)
}

@Test fun `dng uses image dng mime`() {
    assertEquals("image/x-adobe-dng", MediaStoreTargets.forFile(file("a.dng", CameraFileKind.PHOTO)).mimeType)
}

@Test fun `other goes to downloads`() {
    val t = MediaStoreTargets.forFile(file("a.bin", CameraFileKind.OTHER))
    assertEquals(MediaStoreCollection.DOWNLOAD, t.collection)
    assertEquals("Download/Insta360", t.relativePath)
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.storage.MediaStoreTargetTest`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `MediaStoreTargets.forFile` 按扩展名映射 MIME：mp4/mov→video/*，jpg/jpeg→image/jpeg，dng→image/x-adobe-dng，其他→application/octet-stream。
- `AndroidMediaStoreWriter.createPending`：按 `MediaStoreCollection` 选择 `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` / `Images.Media.EXTERNAL_CONTENT_URI` / `Downloads.EXTERNAL_CONTENT_URI`；`ContentValues` 写 `DISPLAY_NAME`、`MIME_TYPE`、`RELATIVE_PATH`、`IS_PENDING=1`，`insert` 返回 Uri。
- `copyInto`：`contentResolver.openOutputStream(uri)` 用 8KB buffer 从 `File` 复制。
- `publish`：`IS_PENDING=0`。
- `delete`：`contentResolver.delete`。
- `findDownloaded`：按 `file.name` 查询 `DISPLAY_NAME=?`、`RELATIVE_PATH=?`（目标表），若 `file.sizeBytes` 非空再校验 `SIZE=?`；返回首个 Uri。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.storage.MediaStoreTargetTest`
Expected: PASS。

- [ ] **Step 5: 真机手动验证**

在 Task 1 壳里临时插入一条 1KB 测试文件到 MediaStore，确认系统相册出现；验证后删除测试代码。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/acegallery/storage app/src/test/java/com/acegallery/storage
git commit -m "feat: add MediaStore writer"
```

---

### Task 10: 下载管理器与前台服务

**Files:**
- Create: `app/src/main/java/com/acegallery/download/DownloadManager.kt`
- Create: `app/src/main/java/com/acegallery/download/DownloadService.kt`
- Create: `app/src/main/java/com/acegallery/di/DownloadModule.kt`
- Test: `app/src/test/java/com/acegallery/download/DownloadManagerTest.kt`

**Interfaces:**
- Consumes: `CameraFileSourceProvider`、`MediaStoreWriter`。
- Produces:
  - `fun interface FreeSpaceChecker { fun freeBytes(): Long }`
  - `interface DownloadQueue { val items: StateFlow<List<DownloadManager.Item>>; fun enqueue(files: List<CameraFile>); fun cancelAll(); suspend fun isDownloaded(file: CameraFile): Boolean }`
  - `fun tempNameFor(path: String): String`（`sha256(path) + ".part"`）
  - `class DownloadManager(private val sourceProvider: CameraFileSourceProvider, private val writer: MediaStoreWriter, private val tempDir: File, private val scope: CoroutineScope, private val freeSpace: FreeSpaceChecker, private val retryCount: Int = 3) : DownloadQueue`
  - `sealed interface State { data object Queued; data class Downloading(val bytes: Long, val total: Long?); data object Done; data class Failed(val reason: String) }`
  - `data class Item(val file: CameraFile, val state: State)`
  - `val items: StateFlow<List<Item>>`
  - `fun enqueue(files: List<CameraFile>)`、`fun cancelAll()`

- [ ] **Step 1: 写失败测试**

用 `FakeFileSource`（记录 offset、可抛异常）和 `FakeMediaStoreWriter`：

```kotlin
@Test fun `downloads queued files serially`() = runTest {
    val manager = DownloadManager(fakeProvider, fakeWriter, tempDir, backgroundScope)
    manager.enqueue(listOf(fileA, fileB))
    advanceUntilIdle()
    assertEquals(listOf(fileA.path, fileB.path), fakeSource.downloaded)
    assertTrue(manager.items.value.all { it.state == State.Done })
}

@Test fun `resumes from existing part file`() = runTest {
    File(tempDir, tempNameFor(fileA)).writeBytes(ByteArray(10))
    manager.enqueue(listOf(fileA))
    advanceUntilIdle()
    assertEquals(10L, fakeSource.lastOffset)
}

@Test fun `retries three times then fails`() = runTest {
    fakeSource.failAlways = true
    manager.enqueue(listOf(fileA))
    advanceUntilIdle()
    assertEquals(3, fakeSource.attempts)
    assertTrue(manager.items.value.single().state is State.Failed)
}

@Test fun `retry fetches a fresh source each attempt`() = runTest {
    fakeProvider.failFirst = true
    manager.enqueue(listOf(fileA))
    advanceUntilIdle()
    assertEquals(2, fakeProvider.gets)
}

@Test fun `storage full deletes pending row`() = runTest {
    fakeWriter.failOnCopy = true
    manager.enqueue(listOf(fileA))
    advanceUntilIdle()
    assertTrue(fakeWriter.deleted.isNotEmpty())
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.download.DownloadManagerTest`
Expected: FAIL。

- [ ] **Step 3: 实现 DownloadManager**

- `enqueue` 用 `LinkedHashMap<String, Item>` 去重，状态 `Queued`。
- 单个 worker 协程串行处理（`Channel` 或 `Mutex` + 循环）；队列空时退出。
- 每个文件：每次尝试都重新 `val source = sourceProvider.get()`（断线时触发重连），`val total = source.size(path)`；先查 `writer.findDownloaded(file.copy(sizeBytes = total))`，非空直接 `Done`（不重复下载）；若 `total != null && freeSpace.freeBytes() < total` → `Failed("存储空间不足")`。
- 临时名 `sha256(path) + ".part"`；`offset = temp.length()`；调用 `source.download(path, temp, offset)`，进度用 `total` 显示。
- 成功后：`createPending` → `copyInto` → `publish` → 删除临时文件 → `Done`。
- 异常：重试 `retryCount` 次，退避 1s/2s/4s，保留临时文件；最终 `Failed(reason)`；若已创建 MediaStore 行则 `delete`。
- `reason` 分类：连接/超时类 → `"相机连接断开"`；HTTP 4xx/5xx → `"相机返回错误（HTTP xxx）"`；空间类 → `"存储空间不足"`；其他 → 异常消息。
- `cancelAll` 取消 worker 并把未完成项标为 `Failed("已取消")`。
- 新增测试：已存在的文件被跳过（`findDownloaded` 返回非空时 `download` 不被调用）；空间不足时不下载。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.download.DownloadManagerTest`
Expected: PASS。

- [ ] **Step 5: 实现 DownloadService**

- `@AndroidEntryPoint` 前台服务，`onStartCommand` 中 `startForeground(notification)`，观察 `DownloadManager.items`，显示「正在下载 n/m」；全部结束或失败后 `stopSelf`。
- `DownloadModule` 提供：`CoroutineScope(SupervisorJob() + Dispatchers.IO)`（`@Singleton`）、`AndroidFreeSpaceChecker(context)`、`DownloadManager`，以及 `DownloadQueue` 绑定到 `DownloadManager`。
- Manifest 注册：`android:foregroundServiceType="dataSync"`、`android:exported="false"`。
- 通知渠道 `downloads`。

- [ ] **Step 6: 编译并手动验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。真机验证在 Task 15 做。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/acegallery/download app/src/test/java/com/acegallery/download
git commit -m "feat: add serial download manager and foreground service"
```

---

### Task 11: 相机会话与连接状态

**Files:**
- Create: `app/src/main/java/com/acegallery/session/CameraState.kt`
- Create: `app/src/main/java/com/acegallery/session/CameraSession.kt`
- Create: `app/src/main/java/com/acegallery/di/CameraModule.kt`
- Test: `app/src/test/java/com/acegallery/session/CameraSessionTest.kt`

**Interfaces:**
- Consumes: `CameraNetworkFinder`、`CameraTransport`、`CameraFileRepository`、`CameraHttpClientFactory`。
- Produces:
  - `sealed interface CameraState { data object Disconnected; data object Connecting; data class Connected(val host: String); data class Error(val message: String) }`
  - `interface CameraGateway { val state: StateFlow<CameraState>; suspend fun connect(host: String = "192.168.42.1"); fun disconnect(); suspend fun listFiles(): List<CameraFile>; fun repository(): CameraFileRepository; fun transport(): CameraTransport; fun httpClient(): OkHttpClient; suspend fun get(): CameraFileSource }`
  - `@Singleton class CameraSession @Inject constructor(private val networkFinder: CameraNetworkFinder, private val transportFactory: (Socket) -> CameraTransport, private val httpClientFactory: (Network) -> OkHttpClient, private val scope: CoroutineScope) : CameraGateway, CameraFileSourceProvider`
    - `val state: StateFlow<CameraState>`
    - `suspend fun connect(host: String = "192.168.42.1")`
    - `fun disconnect()`
    - `suspend fun listFiles(): List<CameraFile>`
    - `fun repository(): CameraFileRepository`
    - `fun transport(): CameraTransport`
    - `fun httpClient(): OkHttpClient`
    - `override fun get(): CameraFileSource`
  - `CameraModule`：提供 `CameraNetworkFinder`、应用级 `CoroutineScope`、`(Socket) -> CameraTransport`、`(Network) -> OkHttpClient`，以及 `CameraGateway` 绑定到 `CameraSession`。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `connect without camera network reports error`() = runTest {
    val session = session(finder = FakeFinder(null))
    session.connect()
    assertTrue(session.state.value is CameraState.Error)
}

@Test fun `connect and list files success`() = runTest {
    val session = session(finder = FakeFinder(fakeNetwork), transport = FakeTransport(listOf(page)))
    session.connect()
    assertTrue(session.state.value is CameraState.Connected)
    assertEquals(1, session.listFiles().size)
}

@Test fun `list files before connect throws`() = runTest {
    assertFailsWith<IllegalStateException> { session.listFiles() }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.session.CameraSessionTest`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `connect`：若当前为 `Connected` 且 `transport.isAlive()`，直接返回；否则置 `Connecting`；`finder.find(host, 6666)`；为空 → `Error("未连接相机 WiFi。请先在系统设置里连接相机的 WiFi。")`；否则 `BoundSocketFactory(network).createSocket().connect(InetSocketAddress(host, 6666), 5000)`，用 `transportFactory(socket)` 创建 transport 并 `start()`；创建 `HttpCameraFileSource("http://$host", httpClientFactory(network))` 与 `CameraFileRepository(transport)`；置 `Connected(host)`；保存引用。
- `disconnect`：关闭 transport，清空引用，置 `Disconnected`。
- `listFiles` / `repository` / `httpClient` / `transport`：未连接抛 `IllegalStateException("未连接相机")`。
- `override suspend fun get(): CameraFileSource`：若 `transport` 为空或 `!transport.isAlive()`，先 `connect(lastHost)`；返回当前 `HttpCameraFileSource`。这样下载重试与缩略图加载会自动重连。
- 连接异常 → `Error(可读消息)`；`lastHost` 记住上次成功的 host。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.session.CameraSessionTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/acegallery/session app/src/test/java/com/acegallery/session
git commit -m "feat: add camera session and connection state"
```

---

### Task 12: 缩略图管线

**Files:**
- Create: `app/src/main/java/com/acegallery/thumbnail/ThumbnailSource.kt`
- Create: `app/src/main/java/com/acegallery/thumbnail/Nv12Decoder.kt`
- Create: `app/src/main/java/com/acegallery/thumbnail/ThumbnailDiskCache.kt`
- Create: `app/src/main/java/com/acegallery/thumbnail/ThumbnailLoader.kt`
- Create: `app/src/main/java/com/acegallery/di/ThumbnailModule.kt`
- Test: `app/src/test/java/com/acegallery/thumbnail/Nv12DecoderTest.kt`
- Test: `app/src/test/java/com/acegallery/thumbnail/ThumbnailLoaderTest.kt`

**Interfaces:**
- Produces:
  - `data class Thumbnail(val width: Int, val height: Int, val argb: IntArray)`
  - `interface ThumbnailSource { suspend fun load(file: CameraFile): Thumbnail? }`
  - `object Nv12Decoder { fun decode(data: ByteArray, width: Int, height: Int): Thumbnail }`
  - `class ThumbnailDiskCache(private val dir: File) { fun get(key: String): Thumbnail?; fun put(key: String, thumb: Thumbnail) }`（用 `DataOutputStream` 存 width/height/argb）
  - `class ThumbnailLoader(sources: List<ThumbnailSource>, diskCache: ThumbnailDiskCache? = null, maxEntries: Int = 200)`：`suspend fun load(file: CameraFile): Thumbnail?`；查找顺序：内存 LRU → 磁盘 → sources，命中后回填两层缓存。
  - UI 层用 `Bitmap.createBitmap(thumb.argb, thumb.width, thumb.height, Bitmap.Config.ARGB_8888)` 显示。

- [ ] **Step 1: 写失败测试（不引用 Android 类）**

```kotlin
@Test fun `nv12 converts yuv to argb`() {
    // 2x2: Y all 0 (black), UV neutral 128
    val data = byteArrayOf(0, 0, 0, 0, 128.toByte(), 128.toByte())
    val thumb = Nv12Decoder.decode(data, 2, 2)
    assertEquals(4, thumb.argb.size)
    assertEquals(0xFF000000.toInt(), thumb.argb[0])
}

@Test fun `loader falls back to next source`() = runTest {
    val thumb = Thumbnail(1, 1, intArrayOf(0xFF00FF00.toInt()))
    val loader = ThumbnailLoader(listOf(sourceReturning(null), sourceReturning(thumb)))
    assertSame(thumb, loader.load(file))
}

@Test fun `loader caches second call`() = runTest {
    val source = CountingSource(Thumbnail(1, 1, intArrayOf(0)))
    val loader = ThumbnailLoader(listOf(source))
    loader.load(file)
    loader.load(file)
    assertEquals(1, source.calls)
}

@Test fun `disk cache round trips thumbnail`() {
    val cache = ThumbnailDiskCache(tempDir)
    val thumb = Thumbnail(2, 2, intArrayOf(1, 2, 3, 4))
    cache.put("k", thumb)
    val loaded = cache.get("k")
    assertEquals(2, loaded?.width)
    assertArrayEquals(intArrayOf(1, 2, 3, 4), loaded?.argb)
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.thumbnail.*`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `Nv12Decoder.decode`：Y 平面 `w*h`，UV 平面交错 `U,V`；BT.601 全范围转 RGB，alpha 0xFF，返回 `Thumbnail(w, h, argb)`。
- `ProtocolThumbnailSource`：`CameraTransport.request(11, GetFileExtra(uri, extra_type=2))`。响应体前 36 字节是 9 个小端 u32：`[1][total][1][0][width][height][0][0][0]`；NV12 从 offset 36 开始，长度 `width*height*3/2`；末尾多出的 4 字节忽略。宽度 = offset 16 的 u32，高度 = offset 20 的 u32。校验长度后调用 `Nv12Decoder.decode(body.copyOfRange(36, 36 + w*h*3/2), w, h)`。实测照片 704×544、视频 896×512。
- `HttpThumbnailSource`：`readRange(path, 0, 65536)` 后 `BitmapFactory.decodeByteArray`；用于卡内 `THM_` 文件或服务端缩略图可用的情况。
- `ExifThumbnailSource`：`readRange(path, 0, 65536)`，用 `ExifInterface` 从字节流取缩略图；JPG 与 DNG 都先走这里（AndroidX ExifInterface 可读 DNG 的内嵌预览）；把解码后的 Bitmap 转成 `Thumbnail`（`getPixels`），失败返回 null，UI 显示占位图。此 source 依赖 Android，不做 JVM 单测，由真机验证。
- `ThumbnailLoader`：每个 source 内部捕获异常并返回 null；先查内存、再查磁盘，未命中按顺序尝试 sources，成功后写入磁盘与内存。
- `ThumbnailModule`：提供 `ThumbnailDiskCache(File(context.cacheDir, "thumbs"))` 和 `ThumbnailLoader(listOf(ProtocolThumbnailSource(session), HttpThumbnailSource(session), ExifThumbnailSource(session)), diskCache)`，`@Singleton`。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.thumbnail.*`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/acegallery/thumbnail app/src/test/java/com/acegallery/thumbnail
git commit -m "feat: add thumbnail pipeline"
```

---

### Task 13: 相册首页（网格、分组、筛选）

**Files:**
- Create: `app/src/main/java/com/acegallery/ui/gallery/GalleryGrouping.kt`
- Create: `app/src/main/java/com/acegallery/ui/gallery/GalleryViewModel.kt`
- Create: `app/src/main/java/com/acegallery/ui/gallery/GalleryScreen.kt`
- Create: `app/src/main/java/com/acegallery/ui/gallery/ThumbnailTile.kt`
- Create: `app/src/main/java/com/acegallery/ui/AppNav.kt`
- Test: `app/src/test/java/com/acegallery/ui/gallery/GalleryGroupingTest.kt`
- Test: `app/src/test/java/com/acegallery/ui/gallery/GalleryViewModelTest.kt`

**Interfaces:**
- Produces:
  - `enum class FileFilter { ALL, PHOTOS, VIDEOS }`
  - `data class GallerySection(val date: LocalDate?, val files: List<CameraFile>)`
  - `object GalleryGrouping { fun group(files: List<CameraFile>, filter: FileFilter, zone: ZoneId = ZoneId.systemDefault()): List<GallerySection> }`
  - `data class GalleryUiState(val connection: CameraState, val sections: List<GallerySection>, val filter: FileFilter, val isLoading: Boolean, val error: String?, val selected: Set<String>, val selectionMode: Boolean)`
  - `@HiltViewModel class GalleryViewModel @Inject constructor(session: CameraGateway, downloadManager: DownloadQueue, thumbnailLoader: ThumbnailLoader) : ViewModel { val state: StateFlow<GalleryUiState>; fun refresh(); fun setFilter(filter: FileFilter); fun disconnect() }`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `groups by date newest first`() {
    val sections = GalleryGrouping.group(listOf(fileAt("2026-01-01T10:00Z"), fileAt("2026-01-02T10:00Z")), FileFilter.ALL, ZoneOffset.UTC)
    assertEquals(listOf(LocalDate.of(2026,1,2), LocalDate.of(2026,1,1)), sections.map { it.date })
}

@Test fun `filters photos`() {
    val sections = GalleryGrouping.group(listOf(photo, video), FileFilter.PHOTOS)
    assertTrue(sections.flatMap { it.files }.all { it.kind == CameraFileKind.PHOTO })
}

@Test fun `refresh exposes files`() = runTest {
    val vm = GalleryViewModel(FakeSession(files), ...)
    vm.refresh()
    advanceUntilIdle()
    assertEquals(1, vm.state.value.sections.sumOf { it.files.size })
}

@Test fun `refresh error shows message`() = runTest {
    val vm = GalleryViewModel(FakeSession(error = "未连接"), ...)
    vm.refresh()
    advanceUntilIdle()
    assertEquals("未连接", vm.state.value.error)
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.ui.gallery.*`
Expected: FAIL。

- [ ] **Step 3: 实现分组与 ViewModel**

- `group`：先按 filter 过滤，再按 `capturedAt` 的 `LocalDate` 分组；日期降序；组内按 `capturedAt` 降序；`null` 日期放最后一组。
- 测试用 `MainDispatcherRule`（`Dispatchers.setMain(StandardTestDispatcher())`，结束 `resetMain()`），每个 ViewModel 测试类加 `@get:Rule`。
- `GalleryViewModel`：`@HiltViewModel class GalleryViewModel @Inject constructor(session: CameraGateway, downloadManager: DownloadQueue, thumbnailLoader: ThumbnailLoader) : ViewModel()`。`refresh()` 先 `session.connect()` 再 `session.listFiles()`；成功更新 sections；异常写入 `error`。`setFilter` 重新分组。`disconnect()` 调 session。
- ViewModel 构造参数用接口：`CameraGateway` 由 Hilt 绑定到 `CameraSession`。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.ui.gallery.*`
Expected: PASS。

- [ ] **Step 5: 实现 Compose 界面**

- `GalleryScreen`：`Scaffold` + 顶部状态条（已连接/未连接 + 刷新按钮）+ 三个筛选 Chip + `LazyVerticalGrid(GridCells.Fixed(3))`，日期头用 `item(span = { GridItemSpan(maxLineSpan) })`；支持下拉刷新（Material3 `PullToRefreshBox`）；点击 tile 进入查看器（传入当前筛选后的扁平列表与 index）。
- `ThumbnailTile`：`LaunchedEffect(file.path) { thumb = thumbnailLoader.load(file) }`；非空时用 `Bitmap.createBitmap(thumb.argb, thumb.width, thumb.height, ARGB_8888).asImageBitmap()` 显示，空则占位；v1 不显示视频时长角标（没有可靠元数据）；已下载角标由 Task 15 接入。
- 空状态、加载骨架、错误与重试。
- 未连接时显示引导文案与「重新检测」。
- `AppNav`：`NavHost` 注册 `gallery`、`viewer/{index}`（当前实现用进程内保存的列表，避免把大列表序列化进路由）；`MainActivity` 的 `setContent` 改为 `AceGalleryAppNav()`。

- [ ] **Step 6: 编译并手动验证**

Run: `./gradlew :app:assembleDebug`，安装后连接相机 WiFi，Expected: 能看到缩略图网格。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/acegallery/ui/gallery app/src/test/java/com/acegallery/ui/gallery
git commit -m "feat: add gallery grid with grouping and filters"
```

---

### Task 14: 查看器（照片缩放 + 视频播放）

**Files:**
- Create: `app/src/main/java/com/acegallery/ui/viewer/PhotoCache.kt`
- Create: `app/src/main/java/com/acegallery/ui/viewer/ViewerScreen.kt`
- Create: `app/src/main/java/com/acegallery/ui/viewer/VideoPlayer.kt`
- Test: `app/src/test/java/com/acegallery/ui/viewer/PhotoCacheTest.kt`

**Interfaces:**
- Consumes: `CameraGateway`、`CameraFileSource`。
- Produces:
  - `class PhotoCache(private val sourceProvider: CameraFileSourceProvider, private val cacheDir: File) { suspend fun get(file: CameraFile): File }`
  - `@Composable fun ViewerScreen(files: List<CameraFile>, initialIndex: Int, session: CameraGateway, onBack: () -> Unit)`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `photo cache downloads once`() = runTest {
    val cache = PhotoCache(fakeProvider, tempDir)
    val f1 = cache.get(photo)
    val f2 = cache.get(photo)
    assertEquals(f1, f2)
    assertEquals(1, fakeSource.downloads)
}

@Test fun `photo cache redownloads when size differs`() = runTest {
    val f = File(tempDir, "cache/" + hash(photo.path)).apply { writeBytes(ByteArray(3)) }
    cache.get(photo)
    assertEquals(1, fakeSource.downloads)
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.ui.viewer.PhotoCacheTest`
Expected: FAIL。

- [ ] **Step 3: 实现 PhotoCache**

- 目标文件 `cacheDir/photos/<sha256(path)>.<ext>`；存在且大小等于 `source.size(path)` 时直接返回；否则下载覆盖。
- 下载失败抛可读异常。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.ui.viewer.PhotoCacheTest`
Expected: PASS。

- [ ] **Step 5: 实现查看器**

- `HorizontalPager`，页码与 `initialIndex` 同步。
- 照片：`PhotoCache.get` → Coil `AsyncImage(model = file)`；外层 `Modifier.pointerInput` 实现双指缩放/平移/双击复位（使用 `detectTransformGestures`）。
- 视频：`VideoPlayer` 用 `ExoPlayer.Builder(context)` + `OkHttpDataSource.Factory { callFactory }`（`callFactory` 来自 `session.httpClient()`），URL = `http://<host><path>`；显示播放/暂停/进度；失败 Toast「播放失败，可下载后观看」。
- 不可预览格式（`.insv`/`.insp`/`CameraFileKind.OTHER`）：不创建播放器、不尝试解码，显示「该格式仅支持下载」+ 下载按钮。
- 顶部显示文件名、大小、拍摄时间；大小未知时用 `source.size(path)` 异步补全；返回按钮。
- 分享：文件已缓存到本地或已在 MediaStore 时，显示分享按钮，用 `Intent.ACTION_SEND` + `FileProvider`。

- [ ] **Step 6: 编译并手动验证**

Run: `./gradlew :app:assembleDebug`；真机 Expected: 照片可缩放、视频可播放/拖进度。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/acegallery/ui/viewer app/src/test/java/com/acegallery/ui/viewer
git commit -m "feat: add photo viewer and video player"
```

---

### Task 15: 多选下载、进度与已下载标记

**Files:**
- Modify: `app/src/main/java/com/acegallery/ui/gallery/GalleryViewModel.kt`
- Modify: `app/src/main/java/com/acegallery/ui/gallery/GalleryScreen.kt`
- Modify: `app/src/main/java/com/acegallery/ui/gallery/ThumbnailTile.kt`
- Create: `app/src/main/java/com/acegallery/ui/gallery/DownloadSheet.kt`
- Test: `app/src/test/java/com/acegallery/ui/gallery/SelectionTest.kt`

**Interfaces:**
- Produces:
  - `GalleryViewModel.toggleSelection(path: String)`、`fun downloadSelected()`、`fun clearSelection()`
  - `val downloadedPaths: StateFlow<Set<String>>`（来自 `MediaStoreWriter.findDownloaded`）
  - `val downloads: StateFlow<List<DownloadManager.Item>>`

- [ ] **Step 1: 写失败测试（沿用 Task 13 的 `MainDispatcherRule`）**

```kotlin
@Test fun `long press enters selection mode`() = runTest {
    val vm = viewModelWith(files)
    vm.toggleSelection(files[0].path)
    assertTrue(vm.state.value.selectionMode)
    assertEquals(setOf(files[0].path), vm.state.value.selected)
}

@Test fun `download selected enqueues only selected`() = runTest {
    vm.toggleSelection(files[0].path)
    vm.downloadSelected()
    assertEquals(listOf(files[0]), fakeDownloadManager.enqueued)
}

@Test fun `clear selection exits mode`() = runTest {
    vm.toggleSelection(files[0].path)
    vm.clearSelection()
    assertFalse(vm.state.value.selectionMode)
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.ui.gallery.SelectionTest`
Expected: FAIL。

- [ ] **Step 3: 实现**

- 网格 `combinedClickable(onClick, onLongClick)`：普通点击进查看器；长按进选择模式。
- 选择模式下点击切换选中；顶部显示「已选 n」，底部操作栏「下载」「取消」。
- `downloadSelected()` 把选中的 `CameraFile` 交给 `DownloadManager.enqueue`（DownloadManager 内部跳过已存在文件），并启动 `DownloadService`；清空选择。
- `DownloadSheet`：显示每个文件的进度/失败原因，失败可重试。
- 已下载：启动时对当前列表逐个 `downloadQueue.isDownloaded(file)`（内部走 `MediaStoreWriter.findDownloaded`，IO 线程），得到 `downloadedPaths`；Tile 显示勾选角标。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.ui.gallery.SelectionTest`
Expected: PASS。

- [ ] **Step 5: 真机验证**

Expected: 多选 2 个文件下载，通知显示进度，文件出现在系统相册；重复下载不产生重复文件；下载中关闭 WiFi 再连上，下载从断点继续。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/acegallery/ui/gallery app/src/test/java/com/acegallery/ui/gallery
git commit -m "feat: add multi-select downloads and downloaded badges"
```

---

### Task 16: 设置、诊断与权限

**Files:**
- Create: `app/src/main/java/com/acegallery/settings/SettingsRepository.kt`
- Create: `app/src/main/java/com/acegallery/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/acegallery/ui/diagnostics/DiagnosticsScreen.kt`
- Create: `app/src/main/java/com/acegallery/di/SettingsModule.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/acegallery/MainActivity.kt`
- Test: `app/src/test/java/com/acegallery/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `class SettingsRepository(private val dataStore: DataStore<Preferences>) { val cameraHost: Flow<String>; suspend fun setCameraHost(host: String) }`，默认 `192.168.42.1`。
  - `SettingsModule`：提供 `DataStore<Preferences>`（`context.preferencesDataStoreFile("settings")`）与 `SettingsRepository`。
  - `DiagnosticsRunner(session: CameraGateway, sourceProvider: CameraFileSourceProvider) { suspend fun run(): List<DiagnosticResult> }`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `camera host defaults to 192_168_42_1`() = runTest {
    assertEquals("192.168.42.1", repo.cameraHost.first())
}

@Test fun `camera host persists`() = runTest {
    repo.setCameraHost("10.0.0.1")
    assertEquals("10.0.0.1", repo.cameraHost.first())
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.settings.SettingsRepositoryTest`
Expected: FAIL。

- [ ] **Step 3: 实现**

- `SettingsRepository` 用 DataStore Preferences；测试用临时文件 + `PreferenceDataStoreFactory`（允许注入 DataStore 以便 JVM 测试）。
- 设置页：相机 IP 输入、保存；「诊断」入口；「电池优化」引导（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。
- 在 `AppNav` 注册 `settings`、`diagnostics` 路由，并在相册顶部加设置入口。
- 诊断页：显示 WiFi 网络、TCP 连接、命令 13 往返、HTTP HEAD、缩略图结果；失败显示错误文本；支持复制。
- 确认 Task 1 的 Manifest 权限声明齐全（`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`）。
- Android 13+ 启动时请求 `POST_NOTIFICATIONS`（被拒也不阻塞下载）。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.acegallery.settings.SettingsRepositoryTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/acegallery/settings app/src/main/java/com/acegallery/ui app/src/main/AndroidManifest.xml app/src/main/java/com/acegallery/MainActivity.kt
git commit -m "feat: add settings, diagnostics and permissions"
```

---

### Task 17: 真机验收与发布包

**Files:**
- Modify: 按验收发现的问题修改
- Create: `docs/acceptance/2026-11-07-v1-acceptance.md`
- Create: `app/build/outputs/apk/debug/app-debug.apk`（构建产物，不提交）

**Interfaces:**
- Produces: 可侧载的 release APK 与验收记录。

- [ ] **Step 1: 跑完整单元测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS。

- [ ] **Step 2: 构建可安装的 APK**

v1 个人侧载直接用 debug 签名：

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。需要正式签名时再生成 keystore 并配置 `signingConfigs`（keystore 不入库）。

- [ ] **Step 3: 逐条执行验收（真机）**

1. 相机开机、手机连相机 WiFi，打开 App 5 秒内进入相册。
2. 1000+ 文件（或用现有卡）滚动流畅，缩略图逐步出现，不 OOM。
3. 照片全屏、双指缩放、双击复位。
4. 视频在线播放、拖动进度。
5. 多选下载，通知进度正确，系统相册可见完整文件。
6. 下载中断开 WiFi → 重连 → 从断点继续；无重复文件。
7. 已下载标记正确。
8. 未连接、空间不足、相机断线均有提示，不崩溃。
9. 抓包确认除相机 IP 外无互联网请求。
10. 冷启动/旋转/切后台不崩溃。

- [ ] **Step 4: 记录验收结果**

`docs/acceptance/2026-11-07-v1-acceptance.md` 逐条写「通过/不通过 + 证据」。

- [ ] **Step 5: 提交并打标签**

```bash
git add docs/acceptance
git commit -m "docs: record v1 acceptance results"
git tag v1.0.0
```

---

## 执行顺序说明

- Task 0 与 Task 1 可并行；Task 3 起依赖 Task 0 的实测结论。
- Task 2–10 是纯 JVM 可测的协议/存储层；Task 11 起需要 Android 环境。
- 每个任务结束提交一次；不要跨任务混合提交。
