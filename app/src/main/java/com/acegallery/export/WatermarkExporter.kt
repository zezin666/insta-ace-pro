package com.acegallery.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.acegallery.R
import com.acegallery.model.CameraFile
import java.io.ByteArrayOutputStream
import java.io.File

enum class WatermarkPosition { TOP_START, TOP_END, BOTTOM_START, BOTTOM_END, BOTTOM_CENTER }

data class WatermarkOptions(
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_CENTER,
)

/** Outcome of [WatermarkExporter.exportPhoto]; [downscaled] reports the OOM sample fallback. */
data class WatermarkExportResult(
    val uri: Uri,
    val downscaled: Boolean,
)

/**
 * Exports a copy of a photo with the official-style Insta360 / Leica watermark.
 * Unlike "download", this writes a new `_WM.jpg` file and leaves the original untouched.
 */
class WatermarkExporter(private val context: Context) {

    fun exportPhoto(
        source: File,
        file: CameraFile,
        options: WatermarkOptions = WatermarkOptions(),
    ): WatermarkExportResult {
        val rotationDegrees = ExifInterface(source.absolutePath).rotationDegrees
        val (decoded, downscaled) = decode(source)
        val bitmap = rotate(decoded, rotationDegrees)
        try {
            drawWatermark(bitmap, options.position)
            val jpeg = ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "无法编码 JPEG" }
                out.toByteArray()
            }
            val uri = save(jpeg, file.name.substringBeforeLast('.') + "_WM.jpg", source)
            return WatermarkExportResult(uri, downscaled)
        } finally {
            bitmap.recycle()
        }
    }

    /** Renders a preview at the given (already downscaled) size without writing to MediaStore. */
    fun renderPreview(source: Bitmap, options: WatermarkOptions): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("无法读取照片")
        drawWatermark(bitmap, options.position)
        return bitmap
    }

    /**
     * Decodes at full resolution first. On [OutOfMemoryError] retries once with `inSampleSize = 2`
     * and reports the downscale so the caller can warn about the smaller export.
     */
    private fun decode(source: File): Pair<Bitmap, Boolean> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取照片" }

        val full = try {
            decodeMutable(source, sampleSize = 1)
        } catch (_: OutOfMemoryError) {
            null
        }
        return if (full != null) {
            full to false
        } else {
            decodeMutable(source, sampleSize = 2) to true
        }
    }

    private fun decodeMutable(source: File, sampleSize: Int): Bitmap =
        BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inMutable = true
                inSampleSize = sampleSize
            },
        ) ?: error("无法读取照片")

    /** Applies the EXIF rotation, recycling the decoded bitmap once the rotated copy exists. */
    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (exc: Throwable) {
            bitmap.recycle()
            throw exc
        }
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun drawWatermark(bitmap: Bitmap, position: WatermarkPosition) {
        val mainTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val smallTypeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        val measure = PaintTextMeasure(
            Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mainTypeface },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = smallTypeface },
        )
        val layout = WatermarkLayout.compute(bitmap.width, bitmap.height, position, measure)
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.leica_logo)
        try {
            WatermarkRenderer.draw(Canvas(bitmap), layout, logo, mainTypeface, smallTypeface)
        } finally {
            logo?.recycle()
        }
    }

    /**
     * Best-effort EXIF copy from the untouched source into the MediaStore output. The output is a
     * URI rather than a plain file, so it is opened `"rw"` (a seekable descriptor, which
     * [ExifInterface] can rewrite) while it is still pending. If that is unavailable the photo is
     * saved without metadata instead of failing the export.
     */
    private fun copyExif(source: File, destination: Uri) {
        try {
            val sourceExif = ExifInterface(source.absolutePath)
            context.contentResolver.openFileDescriptor(destination, "rw")?.use { descriptor ->
                val outputExif = ExifInterface(descriptor.fileDescriptor)
                outputExif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
                for (tag in COPIED_EXIF_TAGS) {
                    sourceExif.getAttribute(tag)?.let { outputExif.setAttribute(tag, it) }
                }
                outputExif.saveAttributes()
            }
        } catch (_: Exception) {
            // Metadata is best-effort; the watermarked JPEG is already written.
        }
    }

    private fun save(bytes: ByteArray, name: String, exifSource: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Insta360")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法写入系统相册")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法打开输出流")
            copyExif(exifSource, uri)
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (exc: Exception) {
            resolver.delete(uri, null, null)
            throw exc
        }
        return uri
    }

    private companion object {
        val COPIED_EXIF_TAGS = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
        )
    }
}
