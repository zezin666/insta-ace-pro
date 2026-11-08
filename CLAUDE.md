# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

「Ace 相册」(package `com.acegallery`) is a lightweight, sideloaded Android gallery for the **Insta360 Ace Pro 1** camera. It connects to the camera's Wi-Fi AP, lists files over the camera's private TCP protocol, shows thumbnails/previews, streams video, downloads originals to `DCIM/Insta360`, and exports watermarked photos / Motion Photo (Live) images. There is no official SDK: the protocol was reverse-engineered and probed on real hardware.

UI text and commit messages are Chinese. Single module `:app`; Kotlin 2.1, Compose Material 3, Hilt, OkHttp, Media3, protobuf-javalite; minSdk 29 / target+compileSdk 35.

## Commands

The shell's default `JAVA_HOME` is Java 8 (`D:\JDK`), which will not build this project. Always prefix Gradle with `JAVA_HOME=/d/JDK17`:

```bash
# Build debug APK -> app/build/outputs/apk/debug/app-debug.apk
JAVA_HOME=/d/JDK17 ./gradlew :app:assembleDebug

# All JVM unit tests
JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest

# One test class / one package
JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.camera.CameraFrameTest
JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests "com.acegallery.ui.gallery.*"

# Install on a USB-connected device
/d/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (`sdk.dir=D:\Android\Sdk`) is gitignored but required for builds. Gradle 8.11.1 wrapper; no ktlint/detekt is configured.

Read-only protocol probe (run from a computer connected to the camera Wi-Fi):

```bash
cd tools/probe && python -m unittest test_probe.py -v
python tools/probe/probe.py --host 192.168.42.1 --out docs/protocol
```

## Architecture

### Connection and transport split

Everything depends on the camera being reachable at `192.168.42.1`. `AndroidCameraNetworkFinder` scans `ConnectivityManager.allNetworks` for a **Wi-Fi** network that can TCP-connect to port 6666, and returns a `CameraNetworkHandle`. `CameraSession` (singleton `@Inject`, implements `CameraGateway` + `CameraFileSourceProvider`) then binds *every* camera socket to that network via `BoundSocketFactory`/`network.bindSocket`.

Two channels are used together:

- **TCP 6666** — custom framed protobuf: file list (`GET_FILE_LIST=13`), thumbnails (`GET_FILE_EXTRA=11`), live preview/photo/record (`START/STOP_LIVE_STREAM=1/2`, `TAKE_PICTURE=3`, `START/STOP_CAPTURE=4/5`). Frame = `[u32 LE total length incl. 4 bytes][payload]`; messages carry a 12-byte header with a 24-bit sequence. `CameraProtocolClient` sends `syNceNdinS` sync + 2 s keepalives, correlates responses by seq, emits live-stream packets, and marks itself dead on timeout/send failure.
- **HTTP 80** — `GET`/`HEAD`/`Range` for file sizes, resumable downloads, previews, and ExoPlayer streaming. There is no auth and no directory listing (`GET /DCIM/` is 403; `/osc/*` is 400 — only the TCP protocol lists files).

`CameraSession.get()` is the reconnect entry point: download/thumbnail/photo code calls it and it transparently re-`connect`s if the transport died.

### Data flow

`CameraFileRepository.listFiles()` paginates command 13 at 100 files/page, stops on an empty page, and dedupes by normalized path. Hidden `.lrv/.thm/...` files are filtered by `FileNameParser`; names are parsed for kind + capture time (the gallery groups by date). `CameraFile` is the shared model.

`ThumbnailLoader` is an ordered fallback chain with a 32 MB memory LRU (downscaled to 256 px) and a 128 MB disk cache: camera-private NV12 thumbnail (cmd 11) → HTTP header bytes as JPEG → EXIF embedded thumbnail. `Nv12Decoder` converts the raw NV12 body (response starts with a 36-byte, 9×u32 header; real sizes: 704×544 photo, 896×512 video).

`DownloadManager` is a serial queue (`DownloadQueue`) writing resumable `filesDir/downloads/<sha256(path)>.part` files, retrying up to 7 times with 1/2/4/8/16/32 s backoff (covers the 15–30 s Wi-Fi reconnect window), then inserting into MediaStore with `IS_PENDING` and publishing to `DCIM/Insta360`. `DownloadService` is the `dataSync` foreground service that shows progress from `DownloadManager.items`.

`PhotoCache` downloads full photos into `cacheDir/photos` for the viewer; `LiveViewDecoder` splits Annex-B H.264/HEVC NALs from `CameraGateway.livePackets` and feeds MediaCodec on a `TextureView` Surface.

### UI and DI

Single activity → Compose `AceGalleryNav` (`ui/AppNav.kt`) with bottom tabs **相册 / 实时画面 / 设置** and a `viewer/{index}` route. The viewer's file list is held in memory in the NavHost (only the index is in the route) — do not serialize large lists into navigation arguments. Screens use Hilt ViewModels + `StateFlow` (`GalleryViewModel`, `ViewerViewModel`, `LiveViewViewModel`, `SettingsViewModel`, `DiagnosticsViewModel`). Camera host is persisted in DataStore via `SettingsRepository`/`CameraHostProvider`. All Hilt bindings live in `di/`.

### Export vs download

The app deliberately distinguishes them, matching the official app:

- **下载 (download)** — copy the camera original unchanged (the download pipeline above).
- **导出 (export)** — write a new processed copy, leaving the original alone:
  - `WatermarkExporter` renders a `_WM.jpg` with the official Insta360 Ace Pro / CO-ENGINEERED WITH / Leica style in five preset positions (bottom center by default, plus four corners), copies the capture `DateTime` (IFD0) and orientation-normalized EXIF, and retries the whole decode → rotate → draw → compress pipeline at `inSampleSize = 2` on OOM. `PhotoExportEditor` is the full-screen preview/preset editor that replaces the old `PhotoExportSheet` chip watermark.
  - `LivePhotoExporter` transcodes a 3 s clip with Media3 Transformer and builds a single-file Android Motion Photo: `SOI → APP1 XMP → APP1 Exif → JPEG → EOI → MP4`, named `MVIMG_*.jpg`. The XMP carries Google `GCamera:MotionPhoto`, legacy `MicroVideo` fields, and OPPO `OpCamera:*`; the appended 116-byte Exif with UserComment `oplus_8388608` is what ColorOS/realme require. Keep these fields consistent — file length and `VideoLength`/offset must agree.

## Hard constraints and known pitfalls

- **Never send traffic off the camera network.** Every camera request must bind the camera `Network` (TCP socket and OkHttp `SocketFactory`). No internet calls are permitted.
- **Do not re-enable OkHttp connection pooling.** The camera's embedded HTTP server corrupts keep-alive connections (returns file bytes where a status line belongs). `CameraHttpClientFactory` intentionally uses `ConnectionPool(0, ...)`; this fix was essential for stable photos/downloads.
- `usesCleartextTraffic="true"` in the manifest is required (camera serves plain HTTP).
- The probe is read-only: do not send `DELETE_FILES` (12), and the app itself is read-only toward the camera.
- All lengths/offsets must be `Long` — files exceed 4 GB. Downloads stream; never read a whole file into memory.
- Frame decoding must reject bogus lengths and keep the receive loop alive on malformed frames; unknown notifications (codes ≥ 8000) are ignored.
- JVM unit tests must not touch Android classes. Android-specific behavior is kept behind interfaces (`CameraGateway`, `CameraNetworkFinder`, `MediaStoreWriter`, `CameraFileSource`), with fakes/`FakeCameraServer` (real `ServerSocket`) and MockWebServer. ViewModel tests use `MainDispatcherRule`. This is why types like `Thumbnail` (ARGB ints), `PendingMedia`, and `CameraNetworkHandle` exist instead of `Bitmap`/`Uri`/`Network`.
- Uncommitted local notes/tooling live in `.superpowers/` (gitignored); the SDD progress ledger there records decisions and deferred issues.

## Documentation map

- `docs/superpowers/specs/2026-11-07-insta-ace-pro-android-app-design.md` — design, scope, non-goals, protocol details.
- `docs/protocol/ace-pro-1-notes.md` + `report.json` — real device probe results (command layout, thumbnails, HTTP behavior).
- `docs/superpowers/plans/2026-11-07-insta-ace-pro-android-app.md` — task-by-task implementation plan and interface contracts.
- `docs/acceptance/2026-11-07-v1-acceptance.md` — device acceptance results and the bugs found there.
- `docs/research/official-export-watermark.md` — why download ≠ export, and the Live Photo / watermark implementation notes.
