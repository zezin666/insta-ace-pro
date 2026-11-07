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

/**
 * Exports a copy of a photo with an Insta360-style watermark. Unlike "download"
 * (which copies the original file), this writes a new `_WM.jpg` file and leaves
 * the original untouched.
 */
class WatermarkExporter(private val context: Context) {

    fun exportPhoto(source: File, file: CameraFile): Uri {
        val original = BitmapFactory.decodeFile(source.absolutePath)
            ?: error("无法读取照片")
        val bitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        try {
            drawWatermark(bitmap)
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

    private fun drawWatermark(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val textSize = (bitmap.width * 0.028f).coerceIn(28f, 96f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val text = "Insta360 Ace Pro"
        val textWidth = paint.measureText(text)
        val padH = textSize * 0.65f
        val padV = textSize * 0.42f
        val margin = (bitmap.width * 0.028f).coerceAtLeast(16f)
        val left = bitmap.width - margin - textWidth - padH * 2
        val top = bitmap.height - margin - textSize - padV * 2
        val rect = RectF(left, top, bitmap.width - margin, bitmap.height - margin)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(120, 0, 0, 0)
        }
        canvas.drawRoundRect(rect, textSize * 0.55f, textSize * 0.55f, background)
        canvas.drawText(text, left + padH, top + padV + textSize * 0.8f, paint)
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
