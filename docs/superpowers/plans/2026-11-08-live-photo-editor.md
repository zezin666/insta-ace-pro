# Live Photo Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user pick an arbitrary 3-second video segment and a custom cover frame, and export a single-file Motion Photo that ColorOS 16 and WeChat recognize as a live photo.

**Architecture:** Extract pure clip/cover math into `LivePhotoTiming`; keep `LivePhotoExporter` responsible for Media3 transcoding and MediaStore output; extract the Exif/XMP byte assembly into `MotionPhotoContainer` so the already-verified format has a JVM regression test. `LivePhotoExportDialog` becomes the segment/cover editor.

**Tech Stack:** Kotlin 2.1, Jetpack Compose, Media3 Transformer, MediaMetadataRetriever, protobuf-javalite (unrelated here), JUnit4.

**Spec:** `docs/superpowers/specs/2026-11-08-live-controls-livephoto-watermark-design.md`

**Starting state:** The working tree already contains the verified format fix (Exif UserComment type 7 + `ASCII\0\0\0oplus_10485792`, XMP without MiCamera/MicroVideo, Exif APP1 before XMP APP1). Build on it; do not revert it.

## Global Constraints

- Clip length is fixed at 3 seconds; if the video is shorter, export the whole remaining video.
- The cover frame must be inside `[clipStart, clipEnd]`.
- Output structure: `SOI → APP1 Exif → APP1 XMP → JPEG → EOI → MP4`; Exif UserComment is type 7, value `ASCII\0\0\0oplus_10485792`; XMP contains only `GCamera` + `OpCamera` + `Container`; `VideoLength` and the `Container` MotionPhoto item length equal the appended MP4 byte count.
- Keep the audio track when the source has audio.
- Output name `MVIMG_*.jpg` in `DCIM/Insta360`; the source video is untouched.
- JVM unit tests only; `JAVA_HOME=/d/JDK17` for every Gradle command.
- No internet requests; camera networking unchanged.

## Review Focus

- Video shorter than 3 seconds: no negative/zero clip, full remaining video is exported, cover stays valid.
- Cover exactly at the clip end or before the clip start: clamped to a valid frame.
- Rotated video: the still cover and the transcoded clip have the same orientation.
- Transformer failure or an empty clip: remux fallback or a readable error, never an empty/corrupt file.
- Format regression: an exported file is still recognized by ColorOS 16 and WeChat after the editor changes.

---

### Task 1: Add pure clip/cover timing

**Files:**
- Create: `app/src/main/java/com/acegallery/export/LivePhotoTiming.kt`
- Test: `app/src/test/java/com/acegallery/export/LivePhotoTimingTest.kt`

**Interfaces:**
- Produces:

```kotlin
data class LiveClip(val startMs: Long, val durationMs: Long) {
    val endMs: Long get() = startMs + durationMs
}

object LivePhotoTiming {
    const val MAX_CLIP_MS = 3_000L
    fun clip(videoDurationMs: Long, startMs: Long): LiveClip
    fun clampCover(clip: LiveClip, coverMs: Long): Long
    fun defaultCover(clip: LiveClip): Long
}
```

- [ ] **Step 1: Write the failing tests**

```kotlin
class LivePhotoTimingTest {
    @Test fun `long video uses a three second window`() {
        assertEquals(LiveClip(4_000, 3_000), LivePhotoTiming.clip(10_000, 4_000))
    }

    @Test fun `start beyond the last window clamps`() {
        assertEquals(LiveClip(7_000, 3_000), LivePhotoTiming.clip(10_000, 9_000))
    }

    @Test fun `short video exports its full length`() {
        assertEquals(LiveClip(0, 2_000), LivePhotoTiming.clip(2_000, 0))
    }

    @Test fun `cover is clamped into the clip`() {
        val clip = LiveClip(4_000, 3_000)
        assertEquals(4_000, LivePhotoTiming.clampCover(clip, 1_000))
        assertEquals(7_000, LivePhotoTiming.clampCover(clip, 9_000))
        assertEquals(5_500, LivePhotoTiming.clampCover(clip, 5_500))
    }

    @Test fun `default cover is the middle of the clip`() {
        assertEquals(5_500, LivePhotoTiming.defaultCover(LiveClip(4_000, 3_000)))
        assertEquals(1_000, LivePhotoTiming.defaultCover(LiveClip(0, 2_000)))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.export.LivePhotoTimingTest`
Expected: FAIL, unresolved `LiveClip`/`LivePhotoTiming`.

- [ ] **Step 3: Implement the timing helper**

`clip` clamps `startMs` to `[0, max(0, duration - 3000)]` and returns `min(3000, duration - start)`. `clampCover` returns `startMs` when the clip is empty, else `coverMs.coerceIn(startMs, endMs)`. `defaultCover` is the midpoint, clamped to `endMs`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.export.LivePhotoTimingTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/acegallery/export/LivePhotoTiming.kt app/src/test/java/com/acegallery/export/LivePhotoTimingTest.kt
git commit -m "feat: add live photo clip and cover timing"
```

---

### Task 2: Make the exporter use a clamped clip duration

**Files:**
- Modify: `app/src/main/java/com/acegallery/export/LivePhotoExporter.kt`
- Test: covered by Task 1's pure helper; Android path verified on device

**Interfaces:**
- Produces: `suspend fun export(video: File, file: CameraFile, durationMs: Long, startMs: Long, coverMs: Long): Uri`.
- Consumes: `LiveClip`, `LivePhotoTiming` from Task 1.

- [ ] **Step 1: Change the signature and compute the clip**

Replace the current `export(video, file, startMs, coverMs)` with the five-parameter version. Compute:

```kotlin
val clip = LivePhotoTiming.clip(durationMs, startMs)
require(clip.durationMs > 0) { "视频时长无效" }
val cover = LivePhotoTiming.clampCover(clip, coverMs)
val presentationUs = (cover - clip.startMs) * 1_000
```

- [ ] **Step 2: Parameterize the clip creation**

Pass `LiveClip` into `createClip`/`transcodeClip`/`remuxClip`. Use `clip.startMs` for `setStartPositionMs` and `seekTo`, and `clip.endMs` for `setEndPositionMs` and the remux `endUs`. Remove the hard-coded `CLIP_US` constant.

- [ ] **Step 3: Build**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Fix the call site in `LivePhotoExportDialog` temporarily if needed; Task 4 replaces the dialog.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/acegallery/export/LivePhotoExporter.kt app/src/main/java/com/acegallery/ui/viewer/LivePhotoExportDialog.kt
git commit -m "feat: export a clamped three second live photo clip"
```

---

### Task 3: Extract and regression-test the Motion Photo container

**Files:**
- Create: `app/src/main/java/com/acegallery/export/MotionPhotoContainer.kt`
- Modify: `app/src/main/java/com/acegallery/export/LivePhotoExporter.kt`
- Test: `app/src/test/java/com/acegallery/export/MotionPhotoContainerTest.kt`

**Interfaces:**
- Produces:

```kotlin
object MotionPhotoContainer {
    fun buildXmp(videoLength: Long, presentationUs: Long): ByteArray
    fun oppoExif(width: Int, height: Int): ByteArray
    fun insertSegments(jpeg: ByteArray, exif: ByteArray, xmp: ByteArray): ByteArray
    fun assemble(jpegWithSegments: ByteArray, video: ByteArray): ByteArray
}
```

- [ ] **Step 1: Write the failing tests**

```kotlin
class MotionPhotoContainerTest {
    @Test fun `xmp is oplus only and has no xiaomi legacy fields`() {
        val xmp = MotionPhotoContainer.buildXmp(videoLength = 1_000, presentationUs = 0)
            .toString(Charsets.UTF_8)
        assertTrue(xmp.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(xmp.contains("OpCamera:MotionPhotoOwner=\"oplus\""))
        assertTrue(xmp.contains("OpCamera:VideoLength=\"1000\""))
        assertFalse(xmp.contains("MiCamera"))
        assertFalse(xmp.contains("MicroVideo"))
    }

    @Test fun `exif user comment is type seven oplus marker`() {
        val exif = MotionPhotoContainer.oppoExif(1920, 1080)
        assertEquals(124, exif.size)
        assertEquals(0xE1, exif[1].toInt() and 0xFF)
        // UserComment tag 0x9286, type 7, count 22, value starts at offset 92 of TIFF
        val tiff = exif.copyOfRange(10, exif.size)
        assertEquals(0x9286, ((tiff[64].toInt() and 0xFF) shl 8) or (tiff[65].toInt() and 0xFF))
        assertEquals(7, ((tiff[66].toInt() and 0xFF) shl 8) or (tiff[67].toInt() and 0xFF))
        val value = tiff.copyOfRange(92, 114).toString(Charsets.US_ASCII)
        assertEquals("ASCII\u0000\u0000\u0000oplus_10485792", value)
    }

    @Test fun `inserts exif before xmp after app0`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x04, 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte())
        val exif = MotionPhotoContainer.oppoExif(1, 1)
        val xmp = MotionPhotoContainer.buildXmp(5, 0)
        val out = MotionPhotoContainer.insertSegments(jpeg, exif, xmp)
        assertArrayEquals(exif, out.copyOfRange(8, 8 + exif.size))
        assertArrayEquals(xmp, out.copyOfRange(8 + exif.size, 8 + exif.size + xmp.size))
    }

    @Test fun `assemble appends the video`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val video = byteArrayOf(1, 2, 3)
        assertArrayEquals(jpeg + video, MotionPhotoContainer.assemble(jpeg, video))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.export.MotionPhotoContainerTest`
Expected: FAIL, unresolved `MotionPhotoContainer`.

- [ ] **Step 3: Move the bytes builders into `MotionPhotoContainer`**

Copy the current `buildXmp`, `oppoExif` and `OPPO_EXIF_HEX` from `LivePhotoExporter`; move `insertSegments`; add `assemble`. `insertSegments` must find the end of APP0 (`4 + length field`) and insert `exif + xmp` there. Keep the exact Exif bytes and XMP strings from the working tree. `LivePhotoExporter` calls these functions.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.export.MotionPhotoContainerTest`
Expected: PASS (4 tests). If the Exif offset assertion is brittle, replace it with a helper that parses the Exif IFD; the required behavior is type 7 and the exact marker string.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/acegallery/export/MotionPhotoContainer.kt app/src/main/java/com/acegallery/export/LivePhotoExporter.kt app/src/test/java/com/acegallery/export/MotionPhotoContainerTest.kt
git commit -m "refactor: isolate and regression-test the motion photo container"
```

---

### Task 4: Replace the dialog with a segment/cover editor

**Files:**
- Modify: `app/src/main/java/com/acegallery/ui/viewer/LivePhotoExportDialog.kt`

**Interfaces:**
- Consumes: `LivePhotoTiming`, `LivePhotoExporter.export(video, file, durationMs, startMs, coverMs)`.
- Produces: the composable signature remains `LivePhotoExportDialog(source, file, exporter, onDismiss, onExported)`.

- [ ] **Step 1: Add the state and sliders**

Use `remember` for `durationMs`, `clipStartMs`, `coverMs`, `preview`, `busy`, `error`. On `source` change read duration with `MediaMetadataRetriever.METADATA_KEY_DURATION`, then:

```kotlin
val clip = LivePhotoTiming.clip(durationMs, clipStartMs)
val maxStart = (durationMs - LivePhotoTiming.MAX_CLIP_MS).coerceAtLeast(0)
clipStartMs = clipStartMs.coerceIn(0, maxStart)
coverMs = LivePhotoTiming.clampCover(clip, coverMs)
```

- [ ] **Step 2: Build the editor UI**

- Keep the existing cover preview image.
- First Slider: `片段起点`, range `0f..maxStart.toFloat()`, enabled when `maxStart > 0`.
- Second Slider: `封面位置`, range `clip.startMs..clip.endMs`; if the range is zero, disable it.
- Label: `片段 ${clip.startMs/1000}–${clip.endMs/1000} 秒 · 封面 ${coverMs/1000} 秒`.
- If `durationMs in 1..2999`, show `视频不足 3 秒，将导出全长`.
- Debounce the preview frame extraction by 120 ms via `LaunchedEffect(coverMs)` + `delay(120)`.
- Export button calls `exporter.export(source, file, durationMs, clip.startMs, coverMs)`. Disable when `durationMs <= 0` or `clip.durationMs <= 0` or `busy`.

- [ ] **Step 3: Build**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/acegallery/ui/viewer/LivePhotoExportDialog.kt
git commit -m "feat: add live photo segment and cover editor"
```

---

### Task 5: On-device acceptance and documentation

**Files:**
- Modify: `docs/acceptance/2026-11-08-live-photo-editor-acceptance.md` (new)

- [ ] **Step 1: Run the full suite and build**

```bash
JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Expected: all tests pass, BUILD SUCCESSFUL.

- [ ] **Step 2: Install and verify on the Ace 3 Pro**

```bash
/d/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

Export one live photo with a non-default start and cover. Verify: ColorOS gallery dynamic playback, WeChat live toggle + preview + send, audio present, file name `MVIMG_*.jpg`, source untouched.

- [ ] **Step 3: Verify short-video handling**

Use a video shorter than 3 seconds if available; confirm the exported file uses the full length and the UI shows the hint.

- [ ] **Step 4: Record and commit**

```bash
git add docs/acceptance/2026-11-08-live-photo-editor-acceptance.md
git commit -m "docs: record live photo editor acceptance"
```
