package com.acegallery.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.acegallery.model.CameraFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Turns a camera video into one Android Motion Photo JPEG that is recognized by
 * MIUI/HyperOS, ColorOS/realme and Google Photos.
 *
 * Layout: SOI -> APP1 Exif -> APP1 XMP -> JPEG data -> EOI -> MP4.
 * The XMP carries only Google `GCamera` + OPPO `OpCamera` + `Container` fields;
 * the 124-byte Exif with type-7 UserComment `ASCII\0\0\0oplus_10485792` is what
 * ColorOS 16 needs to show it as a live photo. Byte assembly lives in
 * [MotionPhotoContainer].
 */
class LivePhotoExporter(private val context: Context) {

    suspend fun export(
        video: File,
        file: CameraFile,
        durationMs: Long,
        startMs: Long,
        coverMs: Long,
    ): Uri {
        val window = LivePhotoTiming.clip(durationMs, startMs)
        require(window.durationMs > 0) { "视频时长无效" }
        val coverMsClamped = LivePhotoTiming.clampCover(window, coverMs)
        val clip = createClip(video, File(context.cacheDir, "live").apply { mkdirs() }, window)
        try {
            val cover = createCover(video, coverMsClamped * 1_000, window.startMs * 1_000)
            val presentationUs = (coverMsClamped - window.startMs) * 1_000
            val bytes = buildMotionPhoto(cover.first, cover.second, clip, presentationUs)
            val base = file.name.substringBeforeLast('.')
            val photoBase = if (base.startsWith("VID_")) {
                "MVIMG_" + base.removePrefix("VID_")
            } else if (base.startsWith("MVIMG_")) {
                base
            } else {
                "MVIMG_$base"
            }
            return save(bytes, "$photoBase.jpg")
        } finally {
            clip.delete()
        }
    }

    private fun createCover(video: File, coverUs: Long, clipStartUs: Long): Pair<Bitmap, ByteArray> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            var frame = retriever.getFrameAtTime(coverUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(clipStartUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: error("无法读取视频封面")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                frame = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
            }
            val longSide = maxOf(frame.width, frame.height)
            if (longSide > 1920) {
                val scale = 1920f / longSide
                frame = Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            }
            val jpeg = ByteArrayOutputStream().use { out ->
                frame.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.toByteArray()
            }
            return frame to jpeg
        } finally {
            retriever.release()
        }
    }

    private suspend fun createClip(video: File, dir: File, clip: LiveClip): File {
        val output = File(dir, "${video.nameWithoutExtension}_live.mp4")
        output.delete()
        return runCatching { transcodeClip(video, output, clip) }
            .getOrElse { error ->
                android.util.Log.w("LivePhoto", "Transformer failed; remuxing", error)
                output.delete()
                remuxClip(video, output, clip)
            }
    }

    private suspend fun transcodeClip(video: File, output: File, clip: LiveClip): File =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .build()
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exception: ExportException,
                    ) {
                        continuation.resumeWithException(exception)
                    }
                })
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(video))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.startMs)
                            .setEndPositionMs(clip.endMs)
                            .build(),
                    )
                    .build()
                transformer.start(
                    EditedMediaItem.Builder(mediaItem).build(),
                    output.absolutePath,
                )
            }
            require(output.exists() && output.length() > 0) { "转码片段为空" }
            output
        }

    private fun remuxClip(video: File, output: File, clip: LiveClip): File {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(video.absolutePath)
            val trackMap = mutableMapOf<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    trackMap[i] = muxer.addTrack(format)
                }
            }
            if (trackMap.keys.none { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }) {
                error("视频里没有可用的视频轨")
            }
            if (clip.startMs > 0) {
                extractor.seekTo(clip.startMs * 1_000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            val endUs = clip.endMs * 1_000
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val time = extractor.sampleTime
                if (time > endUs) break
                val outTrack = trackMap[extractor.sampleTrackIndex]
                if (outTrack == null) {
                    extractor.advance()
                    continue
                }
                buffer.position(0)
                buffer.limit(size)
                info.set(0, size, time, extractor.sampleFlags)
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            return output
        } catch (exc: Exception) {
            output.delete()
            throw exc
        } finally {
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
    }

    private fun buildMotionPhoto(
        cover: Bitmap,
        jpeg: ByteArray,
        clip: File,
        presentationUs: Long,
    ): ByteArray {
        val videoLength = clip.length()
        require(videoLength > 0) { "视频片段为空" }
        val jpegWithSegments = MotionPhotoContainer.insertSegments(
            jpeg = jpeg,
            exif = MotionPhotoContainer.oppoExif(cover.width, cover.height),
            xmp = MotionPhotoContainer.buildXmp(videoLength, presentationUs),
        )
        return MotionPhotoContainer.assemble(jpegWithSegments, clip.readBytes())
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
