# Live View Capture Controls and Aspect Ratio Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the live view's 拍照/录制 buttons switch the camera to the correct capture mode automatically, keep that mode afterward, and size the preview to the camera's actual stream aspect ratio.

**Architecture:** Add commands 54/55 and notification handling to the existing TCP protocol client; `CameraSession` owns `CaptureMode` and `LiveStreamParams`; `LiveViewViewModel`/`LiveViewScreen` consume them and enforce photo/record mutual exclusion. The preview uses the 8234 notification's visible size first, then the decoder's actual size, then 16:9.

**Tech Stack:** Kotlin 2.1, coroutines/Flow, protobuf-javalite, Hilt, Jetpack Compose, JUnit4 + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-11-08-live-controls-livephoto-watermark-design.md`

**Starting state:** The working tree already contains the verified Live-photo Exif/XMP fix and the `AppNav` compile fix. Do not revert or duplicate them.

## Global Constraints

- Camera commands: `SET_SYNC_CAPTURE_MODE = 54`, `GET_SYNC_CAPTURE_MODE = 55`.
- Camera notifications: `SYNC_CAPTURE_MODE_UPDATE = 8211`, `CAM_SUBMODE_CHANGE = 8275` (optional), `UPDATE_LIVE_STREAM_PARAMS = 8234`.
- 拍照 always switches to `SYNC_MODE_NORMAL_IMAGE`; 录制 always switches to `SYNC_MODE_NORMAL_VIDEO`; non-normal submodes are discarded. After the action the camera stays in that mode.
- 拍照 is disabled while recording; 录制 is disabled while a photo is in flight; both are disabled while `busy`.
- Commands 54/55 use `fatalOnTimeout = false`; a timeout must not mark the transport dead or kill the live preview.
- Preview aspect ratio precedence: `window_crop_info.dst_width/dst_height` when > 0 → `res_width/res_height` → `LiveViewDecoder` output size → 16:9.
- All camera sockets stay bound to the camera Wi-Fi network; no internet requests.
- JVM unit tests only. Use `JAVA_HOME=/d/JDK17` for every Gradle command.
- UI text is Chinese; package `com.acegallery` unchanged.

## Review Focus

- Camera ignores 54/55: the session must stay `Connected`, live preview keeps running, and the UI shows a retryable error.
- User taps 拍照 while recording (or 录制 while a photo is in flight): no mode command is sent, the in-flight action is not interrupted.
- 8234 includes `window_crop_info`: the preview must use the visible `dst_width/dst_height`, not the encoded `res_*`, and must not stretch.
- Mode switch interrupts the live stream: one automatic restart of the live stream, then a readable error.
- Landscape layout: the preview stays inside the available area and both controls remain visible.

---

### Task 1: Add sync capture mode and live stream params to the protobuf schema

**Files:**
- Modify: `app/src/main/proto/camera.proto`
- Test: `app/src/test/java/com/acegallery/camera/CameraModeProtoTest.kt`

**Interfaces:**
- Produces (generated in `com.acegallery.proto.CameraProto`): `SyncCaptureMode` enum with `SYNC_MODE_NORMAL_IMAGE = 1`, `SYNC_MODE_NORMAL_VIDEO = 2`; `SetSyncCaptureMode`, `SetSyncCaptureModeResp`, `GetSyncCaptureMode`, `GetSyncCaptureModeResp`; `WindowCropInfo`; `LiveStreamParamsUpdate`; `NotificationSyncCaptureModeUpdate`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.acegallery.camera

import com.acegallery.bytes
import com.acegallery.proto.CameraProto
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraModeProtoTest {
    @Test
    fun `encodes set sync capture mode`() {
        val message = CameraProto.SetSyncCaptureMode.newBuilder()
            .setCaptureMode(CameraProto.SyncCaptureMode.SYNC_MODE_NORMAL_IMAGE)
            .build()
        assertEquals("0801", message.toByteArray().joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `parses sync capture mode response`() {
        val response = CameraProto.GetSyncCaptureModeResp.parseFrom(bytes("0802"))
        assertEquals(CameraProto.SyncCaptureMode.SYNC_MODE_NORMAL_VIDEO, response.captureMode)
    }

    @Test
    fun `parses live stream params with visible crop size`() {
        val update = CameraProto.LiveStreamParamsUpdate.parseFrom(
            bytes("10800f18b808201e320618800f20b808"),
        )
        assertEquals(1920, update.resWidth)
        assertEquals(1080, update.resHeight)
        assertEquals(30, update.resFps)
        assertEquals(1920, update.windowCropInfo.dstWidth)
        assertEquals(1080, update.windowCropInfo.dstHeight)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.camera.CameraModeProtoTest`
Expected: FAIL, `Unresolved reference: SyncCaptureMode` or `LiveStreamParamsUpdate`.

- [ ] **Step 3: Add the proto definitions**

Append to `camera.proto` (field numbers are fixed by the camera; do not renumber):

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

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.camera.CameraModeProtoTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/proto/camera.proto app/src/test/java/com/acegallery/camera/CameraModeProtoTest.kt
git commit -m "feat: add capture mode and live stream protobuf messages"
```

---

### Task 2: Add command constants, notification flow, and non-fatal request parameter

**Files:**
- Modify: `app/src/main/java/com/acegallery/camera/CameraCommands.kt`
- Modify: `app/src/main/java/com/acegallery/camera/CameraTransport.kt`
- Test: covered by Task 3

**Interfaces:**
- Produces:
  - `CameraCommands.SET_SYNC_CAPTURE_MODE = 54`, `GET_SYNC_CAPTURE_MODE = 55`
  - `CameraCommands.NOTIFICATION_SYNC_CAPTURE_MODE_UPDATE = 8211`, `NOTIFICATION_CAM_SUBMODE_CHANGE = 8275`, `NOTIFICATION_UPDATE_LIVE_STREAM_PARAMS = 8234`
  - `data class CameraNotification(val code: Int, val body: ByteArray)`
  - `CameraTransport.notifications: Flow<CameraNotification>` (defaults to `emptyFlow()`)
  - `CameraTransport.request(command: Int, payload: ByteArray, timeoutMs: Long = 5_000, fatalOnTimeout: Boolean = true): ByteArray`

- [ ] **Step 1: Add constants and interface members**

`CameraCommands`:

```kotlin
const val SET_SYNC_CAPTURE_MODE = 54
const val GET_SYNC_CAPTURE_MODE = 55
const val NOTIFICATION_SYNC_CAPTURE_MODE_UPDATE = 8211
const val NOTIFICATION_CAM_SUBMODE_CHANGE = 8275
const val NOTIFICATION_UPDATE_LIVE_STREAM_PARAMS = 8234
```

`CameraTransport`:

```kotlin
data class CameraNotification(val code: Int, val body: ByteArray)

val notifications: Flow<CameraNotification> get() = emptyFlow()

fun request(
    command: Int,
    payload: ByteArray,
    timeoutMs: Long = 5_000,
    fatalOnTimeout: Boolean = true,
): ByteArray
```

- [ ] **Step 2: Compile to find all implementations that need updating**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: FAIL listing `CameraProtocolClient` and test fakes that override `request`/do not implement the new members (if the default notification flow is used, only `request` signatures need updating).

- [ ] **Step 3: Update every `CameraTransport` implementation signature**

Update `CameraProtocolClient` and every fake transport in `app/src/test` to the four-parameter `request`. Fakes that do not care can ignore `fatalOnTimeout`. This is a mechanical signature update; keep `notifications`' default `emptyFlow()` unless a test needs to emit.

- [ ] **Step 4: Compile**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/acegallery/camera/CameraCommands.kt app/src/main/java/com/acegallery/camera/CameraTransport.kt app/src/main/java/com/acegallery/camera/CameraProtocolClient.kt app/src/test
git commit -m "feat: add capture mode commands and camera notification flow"
```

---

### Task 3: Emit notifications and support non-fatal timeouts in `CameraProtocolClient`

**Files:**
- Modify: `app/src/main/java/com/acegallery/camera/CameraProtocolClient.kt`
- Modify: `app/src/test/java/com/acegallery/camera/FakeCameraServer.kt`
- Test: `app/src/test/java/com/acegallery/camera/CameraProtocolClientTest.kt`

**Interfaces:**
- Consumes: `CameraNotification`, `CameraCommands` constants from Task 2.
- Produces: notification frames with no pending sequence are emitted on `notifications`; `request(..., fatalOnTimeout = false)` leaves `isAlive()` true on timeout.

- [ ] **Step 1: Write the failing tests**

Add to `CameraProtocolClientTest`:

```kotlin
@Test
fun `notification is emitted without a pending request`() = runTest {
    FakeCameraServer().use { server ->
        val client = CameraProtocolClient(server.socket(), backgroundScope)
        try {
            client.start()
            server.sendNotification(
                CameraCommands.NOTIFICATION_UPDATE_LIVE_STREAM_PARAMS,
                bytes("10800f18b808201e"),
            )
            val notification = withTimeout(1_000) { client.notifications.first() }
            assertEquals(8234, notification.code)
        } finally {
            client.close()
        }
    }
}

@Test
fun `non fatal timeout keeps the transport alive`() = runTest {
    FakeCameraServer(respond = false).use { server ->
        val client = CameraProtocolClient(server.socket(), backgroundScope)
        try {
            client.start()
            assertThrows(CameraProtocolTimeoutException::class.java) {
                client.request(
                    CameraCommands.GET_SYNC_CAPTURE_MODE,
                    ByteArray(0),
                    timeoutMs = 200,
                    fatalOnTimeout = false,
                )
            }
            assertTrue(client.isAlive())
        } finally {
            client.close()
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.camera.CameraProtocolClientTest`
Expected: FAIL — no `sendNotification` method and/or no `notifications` property.

- [ ] **Step 3: Implement notification emission and the timeout flag**

In `CameraProtocolClient`:

- Add a `MutableSharedFlow<CameraNotification>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)` exposed as `override val notifications`.
- In `handleMessage`, when `pending.remove(header.seq)` returns `null` and `header.responseCode >= 8000`, emit `CameraNotification(header.responseCode, frame.copyOfRange(12, frame.size))`.
- In `request`, on `TimeoutCancellationException`, call `markDead()` only when `fatalOnTimeout` is true, then throw the same `CameraProtocolTimeoutException`.

In `FakeCameraServer`, add a thread-safe method:

```kotlin
fun sendNotification(code: Int, body: ByteArray) {
    val socket = accepted ?: error("no client")
    synchronized(socket) {
        socket.getOutputStream().write(
            CameraFrame.encode(MessageCodec.encodeHeader(code, 0) + body),
        )
        socket.getOutputStream().flush()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.camera.CameraProtocolClientTest`
Expected: PASS (all existing + 2 new tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/acegallery/camera/CameraProtocolClient.kt app/src/test/java/com/acegallery/camera/FakeCameraServer.kt app/src/test/java/com/acegallery/camera/CameraProtocolClientTest.kt
git commit -m "feat: emit camera notifications and allow non-fatal request timeouts"
```

---

### Task 4: Add capture mode and live stream state to `CameraSession`

**Files:**
- Modify: `app/src/main/java/com/acegallery/session/CameraState.kt`
- Modify: `app/src/main/java/com/acegallery/session/CameraSession.kt`
- Test: `app/src/test/java/com/acegallery/session/CameraSessionTest.kt`

**Interfaces:**
- Produces:
  - `enum class CaptureMode { UNKNOWN, PHOTO, VIDEO }`
  - `data class LiveStreamParams(val width: Int, val height: Int, val fps: Int)`
  - `CameraGateway.captureMode: StateFlow<CaptureMode>`
  - `CameraGateway.liveStreamParams: StateFlow<LiveStreamParams?>`
  - `CameraGateway.setCaptureMode(mode: CaptureMode): suspend Unit`
  - `CameraSession.takePicture()`: ensures `PHOTO` then sends command 3.
  - `CameraSession.startRecording()`: ensures `VIDEO` then sends command 4.
- Consumes: commands/notifications from Tasks 2–3.

- [ ] **Step 1: Write the failing tests**

Extend `CameraSessionTest` with a recording transport:

```kotlin
@Test
fun `take picture switches to photo mode first`() = runTest {
    val transport = RecordingTransport(initialMode = CameraProto.SyncCaptureMode.SYNC_MODE_NORMAL_VIDEO)
    val session = session(FakeFinder(FakeHandle), transport)
    session.connect()
    session.takePicture()
    assertEquals(
        listOf(
            CameraCommands.GET_SYNC_CAPTURE_MODE,
            CameraCommands.SET_SYNC_CAPTURE_MODE,
            CameraCommands.TAKE_PICTURE,
        ),
        transport.commands.filter { it in setOf(55, 54, 3) },
    )
    assertEquals(CaptureMode.PHOTO, session.captureMode.value)
}

@Test
fun `start recording switches to video mode first`() = runTest {
    val transport = RecordingTransport(initialMode = CameraProto.SyncCaptureMode.SYNC_MODE_NORMAL_IMAGE)
    val session = session(FakeFinder(FakeHandle), transport)
    session.connect()
    session.startRecording()
    assertEquals(CaptureMode.VIDEO, session.captureMode.value)
    assertTrue(CameraCommands.START_CAPTURE in transport.commands)
}

@Test
fun `mode query timeout keeps session connected`() = runTest {
    val transport = RecordingTransport(failModeQuery = true)
    val session = session(FakeFinder(FakeHandle), transport)
    session.connect()
    assertTrue(session.state.value is CameraState.Connected)
    assertEquals(CaptureMode.UNKNOWN, session.captureMode.value)
}

@Test
fun `mode switch timeout keeps session connected and mode unchanged`() = runTest {
    val transport = RecordingTransport(failModeSwitch = true)
    val session = session(FakeFinder(FakeHandle), transport)
    session.connect()
    assertThrows(CameraProtocolTimeoutException::class.java) {
        session.setCaptureMode(CaptureMode.PHOTO)
    }
    assertTrue(session.state.value is CameraState.Connected)
    assertEquals(CaptureMode.UNKNOWN, session.captureMode.value)
}

@Test
fun `live stream notification prefers visible crop size`() = runTest {
    val transport = RecordingTransport()
    val session = session(FakeFinder(FakeHandle), transport)
    session.connect()
    // encoded 1280x720, visible crop 1920x1080
    transport.emitNotification(
        CameraCommands.NOTIFICATION_UPDATE_LIVE_STREAM_PARAMS,
        bytes("10800a18d005201e320618800f20b808"),
    )
    assertEquals(1920, session.liveStreamParams.value?.width)
    assertEquals(1080, session.liveStreamParams.value?.height)
}
```

`RecordingTransport` must record commands, answer 55 with a `GetSyncCaptureModeResp` (or throw `CameraProtocolTimeoutException` when `failModeQuery`), answer 54/3/4/5/13 with the appropriate empty/file-list bodies, and expose a `MutableSharedFlow<CameraNotification>`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.session.CameraSessionTest`
Expected: FAIL — `CaptureMode`/`liveStreamParams`/`setCaptureMode` missing.

- [ ] **Step 3: Implement the state and command flow**

In `CameraState.kt` add the domain types. In `CameraGateway` add the three members. In `CameraSession`:

```kotlin
private val _captureMode = MutableStateFlow(CaptureMode.UNKNOWN)
override val captureMode = _captureMode.asStateFlow()
private val _liveStreamParams = MutableStateFlow<LiveStreamParams?>(null)
override val liveStreamParams = _liveStreamParams.asStateFlow()

override suspend fun setCaptureMode(mode: CaptureMode) {
    if (mode == CaptureMode.UNKNOWN || _captureMode.value == mode) return
    val protoMode = if (mode == CaptureMode.PHOTO) {
        CameraProto.SyncCaptureMode.SYNC_MODE_NORMAL_IMAGE
    } else {
        CameraProto.SyncCaptureMode.SYNC_MODE_NORMAL_VIDEO
    }
    val request = CameraProto.SetSyncCaptureMode.newBuilder().setCaptureMode(protoMode).build()
    transport().request(
        CameraCommands.SET_SYNC_CAPTURE_MODE,
        request.toByteArray(),
        timeoutMs = 5_000,
        fatalOnTimeout = false,
    )
    _captureMode.value = mode
    delay(250) // let the camera settle; tune on device
}
```

`takePicture()`:

```kotlin
override suspend fun takePicture() {
    if (_captureMode.value != CaptureMode.PHOTO) setCaptureMode(CaptureMode.PHOTO)
    // existing TakePicture request unchanged
}
```

`startRecording()`:

```kotlin
override suspend fun startRecording() {
    if (_captureMode.value != CaptureMode.VIDEO) setCaptureMode(CaptureMode.VIDEO)
    // existing StartCapture request unchanged
}
```

On connect, after the transport starts, launch a notification collector and call a `queryCaptureMode()` that sends command 55 with `fatalOnTimeout = false` and maps 1/2 to PHOTO/VIDEO. Catch `CameraProtocolTimeoutException` and leave mode `UNKNOWN` without changing `state`. On 8234, parse `LiveStreamParamsUpdate` and set width/height from `windowCropInfo.dstWidth/dstHeight` when both > 0, otherwise `resWidth/resHeight`; ignore zero sizes. On 8211, re-query mode. On disconnect, reset both flows.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.session.CameraSessionTest`
Expected: PASS.

- [ ] **Step 5: Update the three existing `CameraGateway` fakes**

`ViewerViewModelTest`, `GalleryViewModelTest`, and `SelectionTest` each contain a private `FakeSession : CameraGateway`. Add:

```kotlin
override val captureMode = MutableStateFlow(CaptureMode.UNKNOWN)
override val liveStreamParams = MutableStateFlow<LiveStreamParams?>(null)
override suspend fun setCaptureMode(mode: CaptureMode) = Unit
```

Run the full unit suite to confirm no regression:

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/acegallery/session app/src/test/java/com/acegallery/session app/src/test/java/com/acegallery/ui
git commit -m "feat: switch camera capture mode from the session"
```

---

### Task 5: Expose mode/params and enforce photo/record mutual exclusion in `LiveViewViewModel`

**Files:**
- Modify: `app/src/main/java/com/acegallery/ui/live/LiveViewViewModel.kt`
- Test: `app/src/test/java/com/acegallery/ui/live/LiveViewViewModelTest.kt`

**Interfaces:**
- Consumes: `CameraGateway.captureMode`, `CameraGateway.liveStreamParams`, `CameraGateway.takePicture()`, `CameraGateway.startRecording()`, `CameraGateway.stopRecording()`.
- Produces: `LiveViewViewModel.UiState` gains `captureMode: CaptureMode`, `liveParams: LiveStreamParams?`, `decodedParams: LiveStreamParams?`, `capturing: Boolean`; `fun onVideoSize(width: Int, height: Int)`.

- [ ] **Step 1: Write the failing tests**

Create `LiveViewViewModelTest` with a `FakeGateway` that implements `CameraGateway`, records calls, and exposes controllable `MutableStateFlow`s. Tests:

```kotlin
@Test
fun `photo is ignored while recording`() = runTest {
    val gateway = FakeGateway()
    val vm = LiveViewViewModel(gateway, CameraHostProvider { "192.168.42.1" })
    vm.start()
    advanceUntilIdle()
    vm.toggleRecording()
    advanceUntilIdle()
    vm.takePhoto()
    advanceUntilIdle()
    assertEquals(0, gateway.takePictureCalls)
}

@Test
fun `record is ignored while capturing`() = runTest {
    val gateway = FakeGateway(blockTakePicture = true)
    val vm = LiveViewViewModel(gateway, CameraHostProvider { "192.168.42.1" })
    vm.start()
    advanceUntilIdle()
    vm.takePhoto()
    advanceUntilIdle()
    vm.toggleRecording()
    advanceUntilIdle()
    assertEquals(0, gateway.startRecordingCalls)
    gateway.releaseTakePicture()
}

@Test
fun `mode and live params are exposed`() = runTest {
    val gateway = FakeGateway()
    val vm = LiveViewViewModel(gateway, CameraHostProvider { "192.168.42.1" })
    gateway.captureMode.value = CaptureMode.VIDEO
    gateway.liveStreamParams.value = LiveStreamParams(1920, 1080, 30)
    advanceUntilIdle()
    assertEquals(CaptureMode.VIDEO, vm.state.value.captureMode)
    assertEquals(1920, vm.state.value.liveParams?.width)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.ui.live.LiveViewViewModelTest`
Expected: FAIL — missing state fields / no mutual exclusion.

- [ ] **Step 3: Implement the state and guards**

In `init` or `start`, collect `session.captureMode` and `session.liveStreamParams` into the state. In `takePhoto`, return early when `state.recording`; set `capturing = true, busy = true` before `session.takePicture()` and reset in `finally`. In `toggleRecording`, return early when `state.capturing`; keep the existing recording toggle. `onVideoSize` stores `decodedParams` only when `liveParams` is null.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.ui.live.LiveViewViewModelTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/acegallery/ui/live/LiveViewViewModel.kt app/src/test/java/com/acegallery/ui/live/LiveViewViewModelTest.kt
git commit -m "feat: expose live capture mode and enforce photo/record exclusion"
```

---

### Task 6: Render the live preview at the actual aspect ratio and enable the buttons correctly

**Files:**
- Create: `app/src/main/java/com/acegallery/ui/live/LivePreviewSize.kt`
- Modify: `app/src/main/java/com/acegallery/ui/live/LiveViewScreen.kt`
- Test: `app/src/test/java/com/acegallery/ui/live/LivePreviewSizeTest.kt`

**Interfaces:**
- Produces: `data class PreviewSize(val width: Float, val height: Float)` and `fun previewSize(maxWidth: Float, maxHeight: Float, aspectRatio: Float): PreviewSize`.
- Consumes: `LiveViewViewModel.UiState` from Task 5.

- [ ] **Step 1: Write the failing test**

```kotlin
class LivePreviewSizeTest {
    @Test fun `wide ratio fills width`() {
        val size = previewSize(1000f, 600f, 16f / 9f)
        assertEquals(1000f, size.width, 0.01f)
        assertEquals(562.5f, size.height, 0.01f)
    }

    @Test fun `tall ratio is limited by height`() {
        val size = previewSize(1000f, 600f, 4f / 3f)
        assertEquals(800f, size.width, 0.01f)
        assertEquals(600f, size.height, 0.01f)
    }

    @Test fun `size never exceeds the available box`() {
        val size = previewSize(1000f, 600f, 9f / 16f)
        assertTrue(size.width <= 1000f && size.height <= 600f)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.ui.live.LivePreviewSizeTest`
Expected: FAIL, `previewSize` unresolved.

- [ ] **Step 3: Implement `previewSize`**

Use `height = min(maxHeight, maxWidth / aspectRatio); width = height * aspectRatio` and return the pair. Guard `aspectRatio <= 0` by falling back to `16f / 9f`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.ui.live.LivePreviewSizeTest`
Expected: PASS.

- [ ] **Step 5: Wire the screen**

In `LiveViewScreen`:

- Collect `state.liveParams ?: state.decodedParams`; ratio = `width / height` or `16f / 9f`.
- Use `BoxWithConstraints`; set the preview box to `previewSize(maxWidth, maxHeight * 0.55f, ratio)` and center it inside the available area. Keep the existing `TextureView`/`AndroidView` inside.
- Add a status chip in the top bar: `PHOTO -> "照片"`, `VIDEO -> "视频"`, else `"未知"`; show `"切换模式…"` when `busy`.
- Photo button `enabled = state.streaming && !state.busy && !state.recording`.
- Record button `enabled = state.streaming && !state.busy && !state.capturing`.

- [ ] **Step 6: Build and manual check**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Install on the Ace 3 Pro and confirm the preview resizes and the buttons disable as specified.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/acegallery/ui/live app/src/test/java/com/acegallery/ui/live
git commit -m "feat: size live preview to the camera stream aspect ratio"
```

---

### Task 7: Fall back to the decoder's actual video size

**Files:**
- Modify: `app/src/main/java/com/acegallery/live/LiveViewDecoder.kt`
- Modify: `app/src/main/java/com/acegallery/ui/live/LiveViewScreen.kt`
- Test: none (Android MediaCodec; verify on device)

**Interfaces:**
- Produces: `LiveViewDecoder.onVideoSize: ((width: Int, height: Int) -> Unit)?`; the screen passes `viewModel::onVideoSize`.
- Consumes: `LiveViewViewModel.onVideoSize` from Task 5.

- [ ] **Step 1: Implement the callback**

After a successful `dequeueOutputBuffer`, read `codec.outputFormat` once and call `onVideoSize` with `MediaFormat.KEY_WIDTH`/`KEY_HEIGHT` when both > 0. Wrap in `runCatching`; never let a format read break decoding.

- [ ] **Step 2: Wire the screen**

Pass `onVideoSize = viewModel::onVideoSize` when constructing the decoder. The UI already prefers `liveParams` over `decodedParams`, so 8234 remains authoritative.

- [ ] **Step 3: Build and manual check**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; preview still renders and resizes when 8234 is absent.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/acegallery/live/LiveViewDecoder.kt app/src/main/java/com/acegallery/ui/live/LiveViewScreen.kt
git commit -m "feat: fall back to decoded live video size"
```

---

### Task 7b: Restart the live stream if packets stop

**Files:**
- Create: `app/src/main/java/com/acegallery/ui/live/LiveStreamWatchdog.kt`
- Modify: `app/src/main/java/com/acegallery/ui/live/LiveViewViewModel.kt`
- Test: `app/src/test/java/com/acegallery/ui/live/LiveStreamWatchdogTest.kt`

**Interfaces:**
- Produces: `fun shouldRestartLive(streaming: Boolean, nowMs: Long, lastPacketMs: Long, alreadyRestarted: Boolean): Boolean`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class LiveStreamWatchdogTest {
    @Test fun `restarts after one second without packets`() {
        assertTrue(shouldRestartLive(streaming = true, nowMs = 2_000, lastPacketMs = 1_000, alreadyRestarted = false))
    }

    @Test fun `does not restart before one second`() {
        assertFalse(shouldRestartLive(streaming = true, nowMs = 1_999, lastPacketMs = 1_000, alreadyRestarted = false))
    }

    @Test fun `does not restart twice`() {
        assertFalse(shouldRestartLive(streaming = true, nowMs = 5_000, lastPacketMs = 1_000, alreadyRestarted = true))
    }

    @Test fun `does not restart when not streaming`() {
        assertFalse(shouldRestartLive(streaming = false, nowMs = 5_000, lastPacketMs = 1_000, alreadyRestarted = false))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests "com.acegallery.ui.live.LiveStreamWatchdogTest"`
Expected: FAIL, unresolved `shouldRestartLive`.

- [ ] **Step 3: Implement the watchdog**

`shouldRestartLive` returns `streaming && !alreadyRestarted && nowMs - lastPacketMs >= 1_000`. In `LiveViewViewModel`, collect `session.livePackets` to update a `lastPacketAt` timestamp; while streaming, check every 250 ms. On the first true result, set `alreadyRestarted = true`, call `session.startLiveStream()` once, and show `"画面中断，正在恢复…"`; if packets resume, reset `alreadyRestarted = false`; if a second interruption occurs without resuming, show `"画面中断，请退出重进"` and stop retrying.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests "com.acegallery.ui.live.LiveStreamWatchdogTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/acegallery/ui/live/LiveStreamWatchdog.kt app/src/main/java/com/acegallery/ui/live/LiveViewViewModel.kt app/src/test/java/com/acegallery/ui/live/LiveStreamWatchdogTest.kt
git commit -m "feat: recover the live stream after a mode switch interruption"
```

---

### Task 8: Conditional 4:3 / 1:1 live stream request (only if Task 9 shows 8234 is always 16:9)

**Files:**
- Modify: `app/src/main/proto/camera.proto` (add `PhotographyOptions` fields 30/31 and `GetPhotographyOptions`/response if not present)
- Modify: `app/src/main/java/com/acegallery/camera/CameraCommands.kt` (`GET_PHOTOGRAPHY_OPTIONS = 10`)
- Modify: `app/src/main/java/com/acegallery/session/CameraSession.kt`
- Test: `app/src/test/java/com/acegallery/export/…` or `session/LiveResolutionTest.kt`

**Interfaces:**
- Produces: `fun liveResolutionFor(aspectRatio: Float): CameraProto.VideoResolution` returning `RES_1920_1440P30` for ~4:3, `RES_1280_1280P30` for ~1:1, and `RES_1920_1080P30` otherwise.
- `CameraSession.startLiveStream()` uses the current mode's photo/video size aspect ratio to pick the resolution; if the camera rejects the request, retry once with `RES_1920_1080P30`.

- [ ] **Step 1: Write the mapping test**

```kotlin
@Test fun `maps 4 by 3 to 1920x1440`() {
    assertEquals(CameraProto.VideoResolution.RES_1920_1440P30, liveResolutionFor(4f / 3f))
}
@Test fun `maps 16 by 9 to 1920x1080`() {
    assertEquals(CameraProto.VideoResolution.RES_1920_1080P30, liveResolutionFor(16f / 9f))
}
@Test fun `maps square to 1280x1280`() {
    assertEquals(CameraProto.VideoResolution.RES_1280_1280P30, liveResolutionFor(1f))
}
```

- [ ] **Step 2: Run it to fail**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests "com.acegallery.session.LiveResolutionTest"`
Expected: FAIL, unresolved.

- [ ] **Step 3: Implement the mapping and query**

Query command 10 with the current function mode and the `PHOTO_SIZE_ID`/`RECORD_RESOLUTION` option types; derive the aspect ratio; pick the live resolution. If the live start request fails, retry once with 16:9. This task is only executed if the device probe proves 8234 is always 16:9.

- [ ] **Step 4: Run the test**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests "com.acegallery.session.LiveResolutionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/proto/camera.proto app/src/main/java/com/acegallery/camera/CameraCommands.kt app/src/main/java/com/acegallery/session app/src/test/java/com/acegallery/session
git commit -m "feat: request a live stream resolution matching the capture aspect ratio"
```

---

### Task 9: On-device acceptance and documentation

**Files:**
- Modify: `docs/acceptance/` (new short acceptance note or append to the existing acceptance doc)

- [ ] **Step 1: Run the full unit suite and build**

```bash
JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Expected: all tests pass, BUILD SUCCESSFUL.

- [ ] **Step 2: Install and verify on the Ace 3 Pro**

```bash
/d/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

Verify all live-view items in §8 of the design: mode switching in both directions, mode persistence, 4:3/16:9 preview, rapid taps, stream recovery, landscape.

- [ ] **Step 3: Record the result**

Write the tested device, camera mode transitions, observed aspect ratios, and any 8234/8211 behavior to `docs/acceptance/2026-11-08-live-view-controls-acceptance.md`.

- [ ] **Step 4: Commit**

```bash
git add docs/acceptance/2026-11-08-live-view-controls-acceptance.md
git commit -m "docs: record live view capture controls acceptance"
```
