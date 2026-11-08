# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

「Ace 相册」(package `com.acegallery`) is a lightweight, sideloaded Android gallery for the **Insta360 Ace Pro 1** camera. It connects to the camera's Wi-Fi AP, lists files over the camera's private TCP protocol, shows thumbnails/previews, streams video, downloads originals to `DCIM/Insta360`, and exports watermarked photos / Motion Photo (Live) images. There is no official SDK: the protocol was reverse-engineered and probed on real hardware.

UI text and commit messages are Chinese. Single module `:app`; Kotlin 2.1, Compose Material 3, Hilt, OkHttp, Media3, protobuf-javalite; minSdk 29 / target+compileSdk 35. Current version `1.3.2` (versionCode 3).

## Commands

The shell's default `JAVA_HOME` is Java 8 (`D:\JDK`), which will not build this project. Always prefix Gradle with `JAVA_HOME=/d/JDK17`:

```bash
# Debug APK -> app/build/outputs/apk/debug/app-debug.apk
JAVA_HOME=/d/JDK17 ./gradlew :app:assembleDebug

# Signed release APK -> app/build/outputs/apk/release/app-release.apk
# (unsigned if the signing keys below are absent)
JAVA_HOME=/d/JDK17 ./gradlew :app:assembleRelease

# All JVM unit tests
JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest

# One test class / one package
JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.export.LivePhotoTimingTest
JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests "com.acegallery.ui.live.*"

# Install on a USB-connected device
/d/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (`sdk.dir=D:\Android\Sdk`) is gitignored but required for builds. Release signing is also read from it (or same-named env vars): `acegallery.release.store`, `.storePassword`, `.keyAlias`, `.keyPassword`. Gradle 8.11.1 wrapper; no ktlint/detekt is configured.

Read-only protocol probe (run from a computer connected to the camera Wi-Fi):

```bash
cd tools/probe && python -m unittest test_probe.py -v
python tools/probe/probe.py --host 192.168.42.1 --out docs/protocol
```

## Architecture

### Connection and transport split

Everything depends on the camera being reachable at `192.168.42.1`. `AndroidCameraNetworkFinder` scans `ConnectivityManager.allNetworks` for a **Wi-Fi** network that can TCP-connect to port 6666, and returns a `CameraNetworkHandle`. `CameraSession` (singleton `@Inject`, implements `CameraGateway` + `CameraFileSourceProvider`) then binds *every* camera socket to that network via `BoundSocketFactory`/`network.bindSocket`.

Two channels are used together:

- **TCP 6666** — custom framed protobuf: file list (`GET_FILE_LIST=13`), thumbnails (`GET_FILE_EXTRA=11`), live preview/photo/record (`START/STOP_LIVE_STREAM=1/2`, `TAKE_PICTURE=3`, `START/STOP_CAPTURE=4/5`), mode query (`GET_SYNC_CAPTURE_MODE=55`, `SET_SYNC_CAPTURE_MODE=54`). Frame = `[u32 LE total length incl. 4 bytes][payload]`; messages carry a 12-byte header with a 24-bit sequence. Bytes 3–4 are the command in requests and the status (200/400/500) in responses. `CameraProtocolClient` sends `syNceNdinS` sync + 2 s keepalives, correlates responses by seq, emits live-stream packets, and exposes unsolicited frames (codes ≥ 8000) on `notifications`.
- **HTTP 80** — `GET`/`HEAD`/`Range` for file sizes, resumable downloads, previews, and ExoPlayer streaming. There is no auth and no directory listing (`GET /DCIM/` is 403; `/osc/*` is 400 — only the TCP protocol lists files).

`CameraSession.get()` is the reconnect entry point: download/thumbnail/photo code calls it and it transparently re-`connect`s if the transport died.

### Live view

`LiveViewScreen` owns a `TextureView`; `LiveViewDecoder` splits Annex-B H.264/HEVC NALs from `CameraGateway.livePackets` and feeds MediaCodec. `LiveViewViewModel` starts/stops the stream, forwards the decoder's output size via `onVideoSize`, and runs a watchdog.

- **Preview ratio precedence (v1.3.2):** decoder output size → 8234 notification's `res_width/res_height` → 16:9. Use `state.decodedParams ?: state.liveParams`. **Do not use 8234's `window_crop_info`**: on the Ace Pro it reports the still capture window (e.g. 8064×6048 for a 4:3 photo) while the stream is 16:9, so using it stretches every frame.
- **Capture uses the camera's current mode.** 拍照/录制 send commands 3/4 directly; they do **not** switch modes. Commands 54/55 are queried on connect and on 8211, but firmware `v1.1.0` answers both with **400** (verified in-app and by raw TCP probe), so mode switching is non-functional and must not gate capture. Recording and photo capture are not mutually exclusive; both buttons are enabled while `streaming && !busy`.
- **Watchdog:** if no packet arrives for 1 s while streaming, restart the stream once; if no packets within a further 2 s, show 「画面中断，请退出重进」 and stop retrying. Packets resuming clears the recovery message.

### Data flow

`CameraFileRepository.listFiles()` paginates command 13 at 100 files/page, stops on an empty page, and dedupes by normalized path. Hidden `.lrv/.thm/...` files are filtered by `FileNameParser`; names are parsed for kind + capture time (the gallery groups by date). `CameraFile` is the shared model.

`ThumbnailLoader` is an ordered fallback chain with a 32 MB memory LRU (downscaled to 256 px) and a 128 MB disk cache: camera-private NV12 thumbnail (cmd 11) → HTTP header bytes as JPEG → EXIF embedded thumbnail. `Nv12Decoder` converts the raw NV12 body (response starts with a 36-byte, 9×u32 header; real sizes: 704×544 photo, 896×512 video).

`DownloadManager` is a serial queue (`DownloadQueue`) writing resumable `filesDir/downloads/<sha256(path)>.part` files, retrying up to 7 times with 1/2/4/8/16/32 s backoff (covers the 15–30 s Wi-Fi reconnect window), then inserting into MediaStore with `IS_PENDING` and publishing to `DCIM/Insta360`. `DownloadService` is the `dataSync` foreground service that shows progress from `DownloadManager.items`.

`PhotoCache` downloads full photos into `cacheDir/photos` for the viewer.

### UI and DI

Single activity → Compose `AceGalleryNav` (`ui/AppNav.kt`) with bottom tabs **相册 / 实时画面 / 设置**, plus `viewer/{index}`, `diagnostics`, and `about` routes. The viewer's file list is held in memory in the NavHost (only the index is in the route) — do not serialize large lists into navigation arguments. Screens use Hilt ViewModels + `StateFlow` (`GalleryViewModel`, `ViewerViewModel`, `LiveViewViewModel`, `SettingsViewModel`, `DiagnosticsViewModel`). Camera host is persisted in DataStore via `SettingsRepository`/`CameraHostProvider`. All Hilt bindings live in `di/`; blocking camera work takes an injected `@IoDispatcher CoroutineDispatcher` (bound to `Dispatchers.IO`) so ViewModel tests stay deterministic.

### Export vs download

The app deliberately distinguishes them, matching the official app:

- **下载 (download)** — copy the camera original unchanged (the download pipeline above).
- **导出 (export)** — write a new processed copy, leaving the original alone:
  - `WatermarkExporter` renders a `_WM.jpg` with the official Insta360 Ace Pro / CO-ENGINEERED WITH / Leica style in five preset positions (bottom center by default, plus four corners), copies the capture `DateTime` (IFD0) and orientation-normalized EXIF, and retries the whole decode → rotate → draw → compress pipeline at `inSampleSize = 2` on OOM. `PhotoExportEditor` is the full-screen preview/preset editor.
  - `LivePhotoExporter` transcodes a 3 s clip with Media3 Transformer and builds a single-file Android Motion Photo: `SOI → APP1 Exif → APP1 XMP → JPEG → EOI → MP4`, named `MVIMG_*.jpg`. Clip/cover math lives in `LivePhotoTiming` (3 s window, short videos export in full, cover clamped into the clip). The XMP carries `GCamera` + `OpCamera` + `Container` only (no `MiCamera`/`MicroVideo`); the Exif UserComment must be **type 7** with `ASCII\0\0\0oplus_10485792` (the old `oplus_8388608` is not recognised by ColorOS 16). Keep file length and `VideoLength`/container offsets consistent — they must equal the appended MP4 byte count.

## Hard constraints and known pitfalls

- **Never send traffic off the camera network.** Every camera request must bind the camera `Network` (TCP socket and OkHttp `SocketFactory`). No internet calls are permitted.
- **Do not re-enable OkHttp connection pooling.** The camera's embedded HTTP server corrupts keep-alive connections (returns file bytes where a status line belongs). `CameraHttpClientFactory` intentionally uses `ConnectionPool(0, ...)`; this fix was essential for stable photos/downloads.
- `usesCleartextTraffic="true"` in the manifest is required (camera serves plain HTTP).
- The probe is read-only: do not send `DELETE_FILES` (12), and the app itself is read-only toward the camera.
- **Commands 54/55 return 400 on the Ace Pro 1 (firmware v1.1.0).** Do not make capture depend on a successful mode switch; send 3/4 directly against the camera's current mode.
- **Notifications (codes ≥ 8000) are never responses.** They share the seq field with requests; `CameraProtocolClient` routes by code before touching pending sequences, so an 8234 frame cannot satisfy a 54/55 request. Don't regress this ordering.
- All lengths/offsets must be `Long` — files exceed 4 GB. Downloads stream; never read a whole file into memory.
- Frame decoding must reject bogus lengths and keep the receive loop alive on malformed frames; unknown notifications are ignored by `CameraSession`.
- JVM unit tests must not touch Android classes. Android-specific behavior is kept behind interfaces (`CameraGateway`, `CameraNetworkFinder`, `MediaStoreWriter`, `CameraFileSource`), with fakes/`FakeCameraServer` (real `ServerSocket`) and MockWebServer. ViewModel tests use `MainDispatcherRule`; `FakeCameraServer` can hold responses so notification/response ordering is deterministic. This is why types like `Thumbnail` (ARGB ints), `PendingMedia`, and `CameraNetworkHandle` exist instead of `Bitmap`/`Uri`/`Network`.
- Release signing keys live only in gitignored `local.properties`/env vars. Without them `assembleRelease` produces an unsigned APK.
- Uncommitted local notes/tooling live in `.superpowers/` (gitignored); the SDD progress ledger there records decisions and deferred issues.

## Documentation map

- `README.md` — user-facing overview, build/signing, usage, version history, known limitations.
- `docs/superpowers/specs/2026-11-07-insta-ace-pro-android-app-design.md` — v1 design, scope, non-goals, protocol details.
- `docs/superpowers/specs/2026-11-08-live-controls-livephoto-watermark-design.md` — live controls, Live Photo and watermark design (note: §4's mode-switch plan was superseded by v1.3.2 direct capture).
- `docs/superpowers/plans/` — task-by-task implementation plans (v1, watermark export, live photo editor, live view controls).
- `docs/acceptance/` — real-device acceptance results (v1, watermark export, live photo editor, live view controls).
- `docs/protocol/ace-pro-1-notes.md` + `report.json` — real device probe results (command layout, thumbnails, HTTP behavior).
- `docs/research/official-export-watermark.md` — why download ≠ export, and the Live Photo / watermark implementation notes.
