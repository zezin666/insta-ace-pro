package com.acegallery.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import com.acegallery.model.CameraFile
import java.io.ByteArrayOutputStream
import java.io.File

enum class WatermarkPosition { TOP_START, TOP_END, BOTTOM_START, BOTTOM_END }

data class WatermarkOptions(
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_END,
)

/**
 * Exports a copy of a photo with an Insta360-style watermark (infinity mark + text).
 * Unlike "download", this writes a new `_WM.jpg` file and leaves the original untouched.
 */
class WatermarkExporter(private val context: Context) {

    fun exportPhoto(
        source: File,
        file: CameraFile,
        options: WatermarkOptions = WatermarkOptions(),
    ): Uri {
        val original = BitmapFactory.decodeFile(source.absolutePath)
            ?: error("无法读取照片")
        val bitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        try {
            drawWatermark(bitmap, options)
            val jpeg = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.toByteArray()
            }
            return save(jpeg, file.name.substringBeforeLast('.') + "_WM.jpg")
        } finally {
            bitmap.recycle()
            original.recycle()
        }
    }

    /** Renders a preview at the given (already downscaled) size without writing to MediaStore. */
    fun renderPreview(source: Bitmap, options: WatermarkOptions): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        drawWatermark(bitmap, options)
        return bitmap
    }

    private fun drawWatermark(bitmap: Bitmap, options: WatermarkOptions) {
        val canvas = Canvas(bitmap)
        val textSize = (bitmap.width * 0.026f).coerceIn(20f, 120f)
        val text = "Insta360 Ace Pro"
        val icon = "∞"

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(245, 199, 107)
            this.textSize = textSize * 1.35f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val iconWidth = iconPaint.measureText(icon)
        val gap = textSize * 0.45f
        val textWidth = textPaint.measureText(text)
        val padH = textSize * 0.65f
        val padV = textSize * 0.42f
        val chipWidth = padH * 2 + iconWidth + gap + textWidth
        val chipHeight = textSize * 1.35f + padV * 2
        val margin = (bitmap.width * 0.028f).coerceAtLeast(16f)

        val left = when (options.position) {
            WatermarkPosition.TOP_START, WatermarkPosition.BOTTOM_START -> margin
            WatermarkPosition.TOP_END, WatermarkPosition.BOTTOM_END -> bitmap.width - margin - chipWidth
        }
        val top = when (options.position) {
            WatermarkPosition.TOP_START, WatermarkPosition.TOP_END -> margin
            WatermarkPosition.BOTTOM_START, WatermarkPosition.BOTTOM_END -> bitmap.height - margin - chipHeight
        }

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 0, 0)
        }
        val rect = RectF(left, top, left + chipWidth, top + chipHeight)
        canvas.drawRoundRect(rect, chipHeight * 0.4f, chipHeight * 0.4f, background)

        val baseline = top + chipHeight / 2f - (iconPaint.descent() + iconPaint.ascent()) / 2f
        canvas.drawText(icon, left + padH, baseline, iconPaint)
        canvas.drawText(
            text,
            left + padH + iconWidth + gap,
            top + chipHeight / 2f - (textPaint.descent() + textPaint.ascent()) / 2f,
            textPaint,
        )
    }

    private fun save(bytes: ByteArray, name: String): Uri {
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
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (exc: Exception) {
            resolver.delete(uri, null, null)
            throw exc
        }
        return uri
    }
}
