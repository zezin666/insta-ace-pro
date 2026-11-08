# Official-Style Watermark Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current chip-style watermark with the approved official-style Insta360 Ace Pro + CO-ENGINEERED WITH + Leica mark, offer five preset positions, and expose a dedicated photo export editor separate from download.

**Architecture:** `WatermarkLayout` computes all geometry from the image size and a text-measure function (pure JVM, unit-tested); `WatermarkRenderer` draws the layout with Android Canvas (including the knocked-out “Ace”); `WatermarkExporter` handles decoding, orientation, memory fallback, and MediaStore output; `PhotoExportEditor` shows the preview and the five position presets.

**Tech Stack:** Kotlin 2.1, Android Canvas/Bitmap, Jetpack Compose, Hilt, JUnit4.

**Spec:** `docs/superpowers/specs/2026-11-08-live-controls-livephoto-watermark-design.md`

**Approved mockups:** `docs/superpowers/specs/assets/2026-11-08-watermark-mockup-crop.png`, `...-watermark-mockup.png`, official reference `...-official-watermark-reference.png`.

## Global Constraints

- Positions: `BOTTOM_CENTER` (default), `TOP_START`, `TOP_END`, `BOTTOM_START`, `BOTTOM_END`.
- Style: white `Insta360` + rounded-rectangle `Ace Pro` group (18px-radius at 92px height); `Ace` is a white-filled rounded rectangle with knocked-out letters; `CO-ENGINEERED WITH` is one character width left of the Leica logo; Leica logo diameter is ~1.26× the group height; no background chip/border.
- Base reference is 4032×3024; scale = `imageWidth / 4032f`, clamped to `[0.35f, 2.5f]`.
- Output is a new `_WM.jpg` in `DCIM/Insta360`; the original is never modified.
- JPEG quality 95; decode orientation is applied; preserve at least orientation/time/device EXIF where feasible.
- Full-resolution export first; on `OutOfMemoryError`, retry with `inSampleSize = 2` and tell the user.
- JVM unit tests only; `JAVA_HOME=/d/JDK17` for every Gradle command.
- The Leica asset is for the user's personal sideloaded app only; do not add network fetches for it.

## Review Focus

- Portrait and landscape photos: the watermark stays inside the image, looks proportional, and no preset clips an edge.
- A 48MP photo: export does not crash; either full resolution or a clearly reported downscale.
- A rotated (EXIF orientation 6/8) photo: output is upright, not sideways.
- The `Ace` knockout reveals the photo, not black/transparent garbage.
- The five presets: bottom center is horizontally centered; the Leica mark never touches the rounded rectangle; the small text keeps one character width to the logo.

---

### Task 1: Add the Leica logo asset

**Files:**
- Create: `app/src/main/res/drawable-nodpi/leica_logo.png`
- Modify: `docs/research/official-export-watermark.md`

**Interfaces:**
- Produces: `R.drawable.leica_logo` as a transparent PNG (recommend 512×512) with a circular alpha mask.

- [ ] **Step 1: Create the asset**

Use the cropped official reference (`docs/superpowers/specs/assets/2026-11-08-official-watermark-reference.png`) or the existing `build/ace-spike/leica-logo-transparent.png`. Apply a circular alpha mask so the corners are transparent; upscale with a high-quality filter to 512×512. Place it at `app/src/main/res/drawable-nodpi/leica_logo.png`.

- [ ] **Step 2: Document the source**

Add a short note to `docs/research/official-export-watermark.md`: the asset is derived from the user-provided official watermark reference; it is used only in this personal sideloaded app.

- [ ] **Step 3: Build**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `R.drawable.leica_logo` resolves.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/drawable-nodpi/leica_logo.png docs/research/official-export-watermark.md
git commit -m "feat: add transparent Leica watermark mark"
```

---

### Task 2: Pure watermark layout math

**Files:**
- Create: `app/src/main/java/com/acegallery/export/WatermarkLayout.kt`
- Test: `app/src/test/java/com/acegallery/export/WatermarkLayoutTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class WatermarkPosition { TOP_START, TOP_END, BOTTOM_START, BOTTOM_END, BOTTOM_CENTER }

interface TextMeasure {
    fun mainWidth(text: String, textSize: Float): Float
    fun smallWidth(text: String, textSize: Float): Float
    fun smallCharWidth(char: Char, textSize: Float): Float
}

data class WatermarkRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

data class WatermarkLayout(
    val mainTextSize: Float,
    val smallTextSize: Float,
    val letterSpacing: Float,
    val cornerRadius: Float,
    val group: WatermarkRect,          // outline and white-fill area
    val instaX: Float,
    val mainCenterY: Float,
    val aceFill: WatermarkRect,
    val proX: Float,
    val smallTextLeft: Float,
    val smallCenterY: Float,
    val logo: WatermarkRect,
    val overallLeft: Float,
    val overallTop: Float,
    val overallRight: Float,
    val overallBottom: Float,
)

object WatermarkLayout {
    const val BASE_WIDTH = 4032f
    fun compute(
        imageWidth: Int,
        imageHeight: Int,
        position: WatermarkPosition,
        measure: TextMeasure,
    ): WatermarkLayout
}
```

- [ ] **Step 1: Write the failing tests**

Use a deterministic fake measure:

```kotlin
private val measure = object : TextMeasure {
    override fun mainWidth(text: String, textSize: Float) = text.length * textSize * 0.55f
    override fun smallWidth(text: String, textSize: Float) = text.length * textSize * 0.52f
    override fun smallCharWidth(char: Char, textSize: Float) = textSize * 0.60f
}

@Test fun `bottom center is horizontally centered`() {
    val layout = WatermarkLayout.compute(4032, 3024, WatermarkPosition.BOTTOM_CENTER, measure)
    assertEquals(2016f, (layout.overallLeft + layout.overallRight) / 2f, 1f)
}

@Test fun `all presets stay inside the image`() {
    val positions = WatermarkPosition.values()
    for (position in positions) {
        val layout = WatermarkLayout.compute(4032, 3024, position, measure)
        assertTrue(layout.overallLeft >= 0f)
        assertTrue(layout.overallTop >= 0f)
        assertTrue(layout.overallRight <= 4032f)
        assertTrue(layout.overallBottom <= 3024f)
    }
}

@Test fun `logo is longer than the rounded rectangle`() {
    val layout = WatermarkLayout.compute(4032, 3024, WatermarkPosition.BOTTOM_CENTER, measure)
    assertTrue(layout.logo.height > layout.group.height)
}

@Test fun `logo is separated from the group`() {
    val layout = WatermarkLayout.compute(4032, 3024, WatermarkPosition.BOTTOM_CENTER, measure)
    assertTrue(layout.logo.left >= layout.group.right + 20f)
}

@Test fun `small text keeps one character gap from the logo`() {
    val layout = WatermarkLayout.compute(4032, 3024, WatermarkPosition.BOTTOM_CENTER, measure)
    val textWidth = measure.smallWidth("CO-ENGINEERED WITH", layout.smallTextSize) +
        layout.letterSpacing * ("CO-ENGINEERED WITH".length - 1)
    assertEquals(layout.logo.left, layout.smallTextLeft + textWidth + measure.smallCharWidth('C', layout.smallTextSize), 1f)
}

@Test fun `portrait image stays inside`() {
    val layout = WatermarkLayout.compute(3024, 4032, WatermarkPosition.BOTTOM_CENTER, measure)
    assertTrue(layout.overallLeft >= 0f && layout.overallRight <= 3024f)
    assertTrue(layout.overallTop >= 0f && layout.overallBottom <= 4032f)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.export.WatermarkLayoutTest`
Expected: FAIL, unresolved `WatermarkLayout`.

- [ ] **Step 3: Implement the layout**

Use these base values (all multiplied by `scale`):

- `scale = (imageWidth / 4032f).coerceIn(0.35f, 2.5f)`
- `mainTextSize = 100f * scale`, `smallTextSize = 34f * scale`, `letterSpacing = 4f * scale`
- `groupHeight = 92f * scale`, `cornerRadius = 18f * scale`
- `pillPad = 14f * scale`, `gapPillPro = 14f * scale`, `groupPadRight = 26f * scale`
- `groupGap = 30f * scale`
- `instaWidth = measure.mainWidth("Insta360", mainTextSize)`
- `aceWidth = measure.mainWidth("Ace", mainTextSize)`, `proWidth = measure.mainWidth("Pro", mainTextSize)`
- `pillWidth = aceWidth + 2 * pillPad`
- `groupWidth = pillWidth + gapPillPro + proWidth + groupPadRight`
- `groupX = instaWidth + groupGap`, `groupCenter = groupX + groupWidth / 2`
- `logoSize = 116f * scale`, `logoGap = 26f * scale`
- `logoLeft = groupX + groupWidth + logoGap`
- `smallText = "CO-ENGINEERED WITH"`; `smallWidth = measure.smallWidth(smallText, smallTextSize) + letterSpacing * (smallText.length - 1)`
- `smallTextLeft = logoLeft - measure.smallCharWidth('C', smallTextSize) - smallWidth`
- `overallLeft = min(0f, smallTextLeft)`, `overallRight = max(groupX + groupWidth, logoLeft + logoSize)`
- Vertical: `edgeMargin = 85f * scale`; for bottom presets `overallBottom = imageHeight - edgeMargin`, `logoBottom = overallBottom`, `logoCenterY = logoBottom - logoSize / 2`, `groupTop = logoCenterY - 126f * scale`; for top presets `groupTop = edgeMargin`, `logoCenterY = groupTop + 126f * scale`, `logoBottom = logoCenterY + logoSize / 2`.
- Horizontal: bottom center `shiftX = (imageWidth - (overallRight - overallLeft)) / 2 - overallLeft`; left presets `shiftX = edgeMargin - overallLeft`; right presets `shiftX = imageWidth - edgeMargin - overallRight`.
- Apply `shiftX`/`groupTop` to all x/y fields; set `group = WatermarkRect(shiftedGroupX, groupTop, shiftedGroupX + groupWidth, groupTop + groupHeight)`, `aceFill = WatermarkRect(group.left, group.top, group.left + pillWidth, group.bottom)`, `proX = aceFill.right + gapPillPro`, `logo = WatermarkRect(shiftedLogoLeft, logoCenterY - logoSize / 2, shiftedLogoLeft + logoSize, logoCenterY + logoSize / 2)`. `mainCenterY = group.centerY`; `smallCenterY = logoCenterY`.
- `overallTop = groupTop`, `overallBottom = logo.bottom` for bottom presets; for top presets `overallTop = groupTop`, `overallBottom = logo.bottom` as well (the logo is below the group in both cases). `overallLeft`/`overallRight` are the shifted values.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest --tests com.acegallery.export.WatermarkLayoutTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/acegallery/export/WatermarkLayout.kt app/src/test/java/com/acegallery/export/WatermarkLayoutTest.kt
git commit -m "feat: add pure official-style watermark layout"
```

---

### Task 3: Draw the layout with Android Canvas

**Files:**
- Create: `app/src/main/java/com/acegallery/export/WatermarkRenderer.kt`
- Test: none (Canvas rendering; verify visually on device)

**Interfaces:**
- Produces:

```kotlin
class PaintTextMeasure(
    private val mainPaint: Paint,
    private val smallPaint: Paint,
) : TextMeasure
object WatermarkRenderer {
    fun draw(
        canvas: Canvas,
        layout: WatermarkLayout,
        logo: Bitmap?,
        mainTypeface: Typeface,
        smallTypeface: Typeface,
    )
}
```

- [ ] **Step 1: Implement `PaintTextMeasure`**

`mainWidth` sets `mainPaint.textSize = textSize` and measures; `smallWidth` does the same with `smallPaint`; `smallCharWidth` measures `char.toString()` with `smallPaint`. The caller configures the typefaces (main bold condensed, small normal condensed) and this class only changes `textSize`.

- [ ] **Step 2: Implement `draw`**

`WatermarkLayout` is pure Kotlin and must not import Android classes. Inside the renderer, convert `WatermarkRect` to `android.graphics.RectF` with a small local helper.

- Main text paint: white, `ANTI_ALIAS`, `mainTypeface`, `textSize = layout.mainTextSize`, text align left. Draw `Insta360` at `(layout.instaX, baseline)` where `baseline` centers the text vertically at `layout.mainCenterY` using `Paint.FontMetrics`.
- `Ace Pro` group:
  - Save a layer; draw the white filled rounded rect at `layout.aceFill` with `layout.cornerRadius`.
  - Draw `Ace` with `PorterDuff.Mode.CLEAR` at `aceFill.left + pillPad`, same baseline, so the photo shows through.
  - Restore; draw the white outline rounded rect at `layout.group` with stroke width `3f * scale` (derive scale from `layout.mainTextSize / 100f`).
  - Draw white `Pro` at `(layout.proX, baseline)`.
- Small text: white, `smallTypeface`, `textSize = layout.smallTextSize`, letter spacing via `Paint.letterSpacing` if available or per-character advance. Center vertically at `layout.smallCenterY` using font metrics; start at `layout.smallTextLeft`.
- Logo: if `logo != null`, draw it into `layout.logo` with `Paint.FILTER_BITMAP_FLAG` and anti-alias.
- Use `canvas.save()`/`restore()` around the knockout layer; never `CLEAR` on the main canvas.

- [ ] **Step 3: Build**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/acegallery/export/WatermarkRenderer.kt
git commit -m "feat: render the official-style watermark"
```

---

### Task 4: Rework `WatermarkExporter` for positions, orientation, and memory

**Files:**
- Modify: `app/src/main/java/com/acegallery/export/WatermarkExporter.kt`

**Interfaces:**
- Produces:

```kotlin
data class WatermarkOptions(val position: WatermarkPosition = WatermarkPosition.BOTTOM_CENTER)

class WatermarkExporter(context: Context) {
    fun exportPhoto(source: File, file: CameraFile, options: WatermarkOptions = WatermarkOptions()): Uri
    fun renderPreview(source: Bitmap, options: WatermarkOptions): Bitmap
}
```

- Consumes: `WatermarkLayout`, `WatermarkRenderer`, `PaintTextMeasure`, `R.drawable.leica_logo`.

- [ ] **Step 1: Decode with orientation and memory fallback**

- Read bounds with `inJustDecodeBounds`; read `ExifInterface(source).rotationDegrees`.
- Decode with `inMutable = true`; if `OutOfMemoryError`, decode with `inSampleSize = 2`, remember `downscaled = true`, and expose it via the returned save/notification path (e.g., append a message or a callback).
- Rotate the bitmap by `rotationDegrees` if non-zero; reuse `Bitmap.createBitmap` and recycle the old one.

- [ ] **Step 2: Draw with the new layout**

- `val mainTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)`
- `val smallTypeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)`
- `val measure = PaintTextMeasure(Paint().apply { typeface = mainTypeface }, Paint().apply { typeface = smallTypeface })`
- `val layout = WatermarkLayout.compute(bitmap.width, bitmap.height, options.position, measure)`
- `val logo = BitmapFactory.decodeResource(context.resources, R.drawable.leica_logo)`
- `WatermarkRenderer.draw(Canvas(bitmap), layout, logo, mainTypeface, smallTypeface)`
- Compress JPEG 95 and save via the existing MediaStore pending-write path with the `_WM.jpg` name.
- `renderPreview(source, options)` copies the preview bitmap (or draws on a mutable copy), computes the layout for the preview size, draws, and returns it.

- [ ] **Step 3: Preserve minimal EXIF**

After writing, copy at least `TAG_ORIENTATION = 1`, `TAG_DATETIME_ORIGINAL`, `TAG_MAKE`, `TAG_MODEL` from the source via `ExifInterface` when the output file is accessible. If the output is a MediaStore URI, use `contentResolver.openFileDescriptor`; if that is not feasible, document the limitation in code and the acceptance note.

- [ ] **Step 4: Build**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/acegallery/export/WatermarkExporter.kt
git commit -m "feat: export watermarked photos with the official style"
```

---

### Task 5: Add the dedicated photo export editor

**Files:**
- Create: `app/src/main/java/com/acegallery/ui/viewer/PhotoExportEditor.kt`
- Modify: `app/src/main/java/com/acegallery/ui/viewer/ViewerScreen.kt`
- Delete/replace: `app/src/main/java/com/acegallery/ui/viewer/PhotoExportSheet.kt`

**Interfaces:**
- Produces: `@Composable fun PhotoExportEditor(source: File, file: CameraFile, exporter: WatermarkExporter, onDismiss: () -> Unit, onExported: () -> Unit)`.
- Consumes: `WatermarkOptions`, `WatermarkPosition`, `WatermarkExporter.renderPreview/exportPhoto`.

- [ ] **Step 1: Build the editor UI**

- Full-screen or large modal sheet with:
  - Preview image from `exporter.renderPreview(decodedScaled(source, 1200), options)`, recomputed when `options.position` changes.
  - Five preset chips: 底部居中 / 左上 / 右上 / 左下 / 右下 mapped to the enum.
  - Export button; busy/error state; success calls `onExported`.
- Keep the existing “导出” label and the separate download action in `ViewerScreen`.

- [ ] **Step 2: Wire `ViewerScreen`**

Replace `PhotoExportSheet` usage with `PhotoExportEditor`. Keep the bottom “导出” button visible for viewable photos (jpg/jpeg/png/webp). Do not change the download or share actions.

- [ ] **Step 3: Build**

Run: `JAVA_HOME=/d/JDK17 ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/acegallery/ui/viewer/PhotoExportEditor.kt app/src/main/java/com/acegallery/ui/viewer/ViewerScreen.kt app/src/main/java/com/acegallery/ui/viewer/PhotoExportSheet.kt
git commit -m "feat: add dedicated watermarked photo export editor"
```

---

### Task 6: On-device acceptance and documentation

**Files:**
- Modify: `docs/acceptance/2026-11-08-watermark-export-acceptance.md` (new)

- [ ] **Step 1: Run the full suite and build**

```bash
JAVA_HOME=/d/JDK17 ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Expected: all tests pass, BUILD SUCCESSFUL.

- [ ] **Step 2: Install and verify on the Ace 3 Pro**

```bash
/d/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

Export one photo per preset. Compare against `docs/superpowers/specs/assets/2026-11-08-watermark-mockup-crop.png`. Verify:
- Bottom center is horizontally centered.
- Logo never touches the rounded rectangle.
- Small text keeps one character width to the logo.
- All five presets stay inside the image.
- Portrait and landscape both look proportional.
- Rotated photo exports upright.
- Original file is unchanged; new `_WM.jpg` appears in `DCIM/Insta360`.

- [ ] **Step 3: Test a large photo**

Export the largest available photo (e.g., 48MP) and confirm no crash; if the OOM fallback triggers, confirm the user-visible downscale message.

- [ ] **Step 4: Record and commit**

```bash
git add docs/acceptance/2026-11-08-watermark-export-acceptance.md
git commit -m "docs: record watermark export acceptance"
```
