package com.acegallery.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.acegallery.camera.CameraCommands
import com.acegallery.camera.CameraTransport
import com.acegallery.model.CameraFile
import com.acegallery.network.CameraFileSourceProvider
import com.acegallery.proto.CameraProto
import java.io.ByteArrayInputStream

data class Thumbnail(
    val width: Int,
    val height: Int,
    val argb: IntArray,
)

fun Thumbnail.byteSize(): Long = width.toLong() * height * 4

fun Thumbnail.scaledTo(maxDimension: Int): Thumbnail {
    val scale = maxDimension.toFloat() / maxOf(width, height)
    if (scale >= 1f) return this
    val newWidth = (width * scale).toInt().coerceAtLeast(1)
    val newHeight = (height * scale).toInt().coerceAtLeast(1)
    val pixels = IntArray(newWidth * newHeight)
    for (y in 0 until newHeight) {
        val sourceY = (y / scale).toInt().coerceIn(0, height - 1)
        for (x in 0 until newWidth) {
            val sourceX = (x / scale).toInt().coerceIn(0, width - 1)
            pixels[y * newWidth + x] = argb[sourceY * width + sourceX]
        }
    }
    return Thumbnail(newWidth, newHeight, pixels)
}

interface ThumbnailSource {
    suspend fun load(file: CameraFile): Thumbnail?
}

class ProtocolThumbnailSource(
    private val transportProvider: () -> CameraTransport?,
) : ThumbnailSource {

    override suspend fun load(file: CameraFile): Thumbnail? = runCatching {
        val transport = transportProvider() ?: return@runCatching null
        val request = CameraProto.GetFileExtra.newBuilder()
            .setUri(file.uri)
            .setExtraType(EXTRA_THUMBNAIL)
            .build()
        val body = transport.request(
            CameraCommands.GET_FILE_EXTRA,
            request.toByteArray(),
            timeoutMs = 8_000,
        )
        Nv12ThumbnailParser.parse(body)
    }.getOrNull()

    private companion object {
        const val EXTRA_THUMBNAIL = 2
    }
}

class HttpThumbnailSource(
    private val sourceProvider: CameraFileSourceProvider,
) : ThumbnailSource {

    override suspend fun load(file: CameraFile): Thumbnail? = runCatching {
        val source = sourceProvider.get()
        val bytes = source.readRange(file.path, 0, HEADER_BYTES)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.toThumbnail()
    }.getOrNull()

    private companion object {
        const val HEADER_BYTES = 64 * 1024
    }
}

class ExifThumbnailSource(
    private val sourceProvider: CameraFileSourceProvider,
) : ThumbnailSource {

    override suspend fun load(file: CameraFile): Thumbnail? = runCatching {
        val source = sourceProvider.get()
        val bytes = source.readRange(file.path, 0, HEADER_BYTES)
        ExifInterface(ByteArrayInputStream(bytes)).thumbnailBitmap?.toThumbnail()
    }.getOrNull()

    private companion object {
        const val HEADER_BYTES = 64 * 1024
    }
}

private fun Bitmap.toThumbnail(): Thumbnail {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return Thumbnail(width, height, pixels)
}
