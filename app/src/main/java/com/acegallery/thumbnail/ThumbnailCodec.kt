package com.acegallery.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

interface ThumbnailCodec {
    fun encode(thumb: Thumbnail): ByteArray
    fun decode(data: ByteArray): Thumbnail?
}

class BitmapPngCodec : ThumbnailCodec {

    override fun encode(thumb: Thumbnail): ByteArray {
        require(thumb.width > 0 && thumb.height > 0) { "invalid dimensions" }
        require(thumb.argb.size.toLong() == thumb.width.toLong() * thumb.height) {
            "invalid pixel count"
        }
        val bitmap = Bitmap.createBitmap(
            thumb.argb,
            thumb.width,
            thumb.height,
            Bitmap.Config.ARGB_8888,
        )
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "PNG encode failed"
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun decode(data: ByteArray): Thumbnail? {
        if (data.isEmpty()) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            Thumbnail(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }
}