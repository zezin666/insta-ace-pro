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
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.acegallery.R
import com.acegallery.model.CameraFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

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
        val sourceExif = openSourceExif(source)
        val rotationDegrees = readRotationDegrees(sourceExif)
        val dateTakenMillis = captureTimeMillis(sourceExif)
        val (jpeg, downscaled) = try {
            renderJpeg(source, rotationDegrees, options, sampleSize = 1) to false
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "Full-resolution export ran out of memory; retrying at inSampleSize=2", oom)
            renderJpeg(source, rotationDegrees, options, sampleSize = 2) to true
        }
        val uri = save(
            bytes = jpeg,
            name = file.name.substringBeforeLast('.') + "_WM.jpg",
            source = source,
            sourceExif = sourceExif,
            dateTakenMillis = dateTakenMillis,
        )
        return WatermarkExportResult(uri, downscaled)
    }

    /** Renders a preview at the given (already downscaled) size without writing to MediaStore. */
    fun renderPreview(source: Bitmap, options: WatermarkOptions): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("无法读取照片")
        drawWatermark(bitmap, options.position)
        return bitmap
    }

    private fun openSourceExif(source: File): ExifInterface = try {
        ExifInterface(source.absolutePath)
    } catch (exc: Exception) {
        throw IOException("导出失败，无法读取照片信息", exc)
    }

    private fun readRotationDegrees(exif: ExifInterface): Int = try {
        exif.rotationDegrees
    } catch (exc: Exception) {
        throw IOException("导出失败，无法读取照片信息", exc)
    }

    /**
     * Decodes, rotates, draws, and compresses in one attempt so an [OutOfMemoryError] anywhere in
     * the pipeline is caught by [exportPhoto] and retried at `inSampleSize = 2`.
     */
    private fun renderJpeg(
        source: File,
        rotationDegrees: Int,
        options: WatermarkOptions,
        sampleSize: Int,
    ): ByteArray {
        var decoded: Bitmap? = null
        var rendered: Bitmap? = null
        try {
            decoded = decodeMutable(source, sampleSize)
            rendered = rotate(decoded, rotationDegrees)
            drawWatermark(rendered, options.position)
            return ByteArrayOutputStream().use { out ->
                check(rendered.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                    "导出失败，无法编码 JPEG"
                }
                out.toByteArray()
            }
        } finally {
            // `rotate` may return the decoded bitmap itself, so only recycle distinct objects.
            if (rendered !== decoded) rendered?.recycle()
            decoded?.recycle()
        }
    }

    private fun decodeMutable(source: File, sampleSize: Int): Bitmap =
        BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inMutable = true
                inSampleSize = sampleSize
            },
        ) ?: error("导出失败，无法读取照片")

    /** Applies the EXIF rotation; ownership of both bitmaps stays with [renderJpeg]. */
    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
    private fun copyExif(source: File, sourceExif: ExifInterface, destination: Uri) {
        try {
            context.contentResolver.openFileDescriptor(destination, "rw")?.use { descriptor ->
                val outputExif = ExifInterface(descriptor.fileDescriptor)
                outputExif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
                for (tag in COPIED_EXIF_TAGS) {
                    sourceExif.getAttribute(tag)?.let { outputExif.setAttribute(tag, it) }
                }
                outputExif.saveAttributes()
            }
        } catch (exc: Exception) {
            // Metadata is best-effort; the watermarked JPEG is already written.
            Log.w(TAG, "Failed to copy EXIF from ${source.name} to $destination", exc)
        }
    }

    /** Reads the capture time from IFD0 `DateTime` (real Ace Pro files) with an IFD1 fallback. */
    private fun captureTimeMillis(exif: ExifInterface): Long? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: return null
        return try {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
        } catch (exc: Exception) {
            Log.w(TAG, "Could not parse capture time \"$raw\"", exc)
            null
        }
    }

    private fun save(
        bytes: ByteArray,
        name: String,
        source: File,
        sourceExif: ExifInterface,
        dateTakenMillis: Long?,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Insta360")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            dateTakenMillis?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("导出失败，无法写入系统相册")
        var published = false
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("导出失败，无法打开输出流")
            copyExif(source, sourceExif, uri)
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            published = true
        } finally {
            // An Error (e.g. OOM) must not leave an IS_PENDING=1 orphan visible to the gallery.
            if (!published) {
                try {
                    resolver.delete(uri, null, null)
                } catch (cleanup: Throwable) {
                    Log.w(TAG, "Failed to delete pending MediaStore row $uri", cleanup)
                }
            }
        }
        return uri
    }

    private companion object {
        const val TAG = "WatermarkExporter"

        val COPIED_EXIF_TAGS = listOf(
            // Real Ace Pro files carry the capture time in IFD0 DateTime only.
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
        )
    }
}
