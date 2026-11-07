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
 * Layout: SOI -> APP1 XMP -> APP1 Exif (OPPO marker) -> JPEG data -> EOI -> MP4.
 * The XMP carries both Google MotionPhoto container fields and OPPO OpCamera fields;
 * the 116-byte Exif with `oplus_8388608` is what ColorOS needs to show it as a live photo.
 */
class LivePhotoExporter(private val context: Context) {

    suspend fun export(video: File, file: CameraFile): Uri {
        val clip = createClip(video, File(context.cacheDir, "live").apply { mkdirs() })
        try {
            val cover = createCover(video)
            val bytes = buildMotionPhoto(cover.first, cover.second, clip)
            val base = file.name.substringBeforeLast('.')
            val photoBase = if (base.startsWith("VID_")) {
                "IMG_" + base.removePrefix("VID_")
            } else {
                base
            }
            return save(bytes, "${photoBase}_MP.jpg")
        } finally {
            clip.delete()
        }
    }

    private fun createCover(video: File): Pair<Bitmap, ByteArray> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            var frame = retriever.getFrameAtTime(1_500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
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

    private suspend fun createClip(video: File, dir: File): File {
        val output = File(dir, "${video.nameWithoutExtension}_live.mp4")
        output.delete()
        return runCatching { transcodeClip(video, output) }
            .getOrElse { error ->
                android.util.Log.w("LivePhoto", "Transformer failed; remuxing", error)
                output.delete()
                remuxClip(video, output)
            }
    }

    private suspend fun transcodeClip(video: File, output: File): File =
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
                            .setEndPositionMs(3_000)
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

    private fun remuxClip(video: File, output: File): File {
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
            muxer.start()
            val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val time = extractor.sampleTime
                if (time > CLIP_US) break
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

    private fun buildMotionPhoto(cover: Bitmap, jpeg: ByteArray, clip: File): ByteArray {
        val videoLength = clip.length()
        require(videoLength > 0) { "视频片段为空" }
        val xmp = buildXmp(videoLength)
        val jpegWithSegments = insertSegments(jpeg, xmp, cover.width, cover.height)
        val out = ByteArrayOutputStream(jpegWithSegments.size + videoLength.toInt())
        out.write(jpegWithSegments)
        clip.inputStream().use { it.copyTo(out) }
        return out.toByteArray()
    }

    private fun insertSegments(
        jpeg: ByteArray,
        xmp: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        var offset = 2
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
            val app0Length = ((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)
            offset = 4 + app0Length
        }
        require(offset in 2 until jpeg.size) { "invalid JPEG" }

        val xmpPayload = XMP_IDENTIFIER + xmp
        val xmpLength = xmpPayload.size + 2
        val xmpSegment = byteArrayOf(
            0xFF.toByte(),
            0xE1.toByte(),
            (xmpLength shr 8).toByte(),
            xmpLength.toByte(),
        ) + xmpPayload

        val exif = oppoExif(width, height)
        return jpeg.copyOfRange(0, offset) + xmpSegment + exif + jpeg.copyOfRange(offset, jpeg.size)
    }

    private fun buildXmp(videoLength: Long): ByteArray {
        val xml = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                xmlns:OpCamera="http://ns.oplus.com/photos/1.0/camera/"
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                GCamera:MotionPhoto="1"
                GCamera:MotionPhotoVersion="1"
                GCamera:MotionPhotoPresentationTimestampUs="1500000"
                GCamera:MicroVideo="1"
                GCamera:MicroVideoVersion="1"
                GCamera:MicroVideoOffset="$videoLength"
                GCamera:MicroVideoPresentationTimestampUs="1500000"
                OpCamera:MotionPhotoPrimaryPresentationTimestampUs="1500000"
                OpCamera:MotionPhotoOwner="oplus"
                OpCamera:OLivePhotoVersion="2"
                OpCamera:VideoLength="$videoLength">
               <Container:Directory>
                <rdf:Seq>
                 <rdf:li rdf:parseType="Resource">
                  <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
                 </rdf:li>
                 <rdf:li rdf:parseType="Resource">
                  <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength"/>
                 </rdf:li>
                </rdf:Seq>
               </Container:Directory>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        return xml.toByteArray(Charsets.UTF_8)
    }

    private fun oppoExif(width: Int, height: Int): ByteArray {
        val bytes = OPPO_EXIF_HEX.hexToBytes()
        if (width > 0) {
            bytes[28] = (width ushr 24).toByte()
            bytes[29] = (width ushr 16).toByte()
            bytes[30] = (width ushr 8).toByte()
            bytes[31] = width.toByte()
        }
        if (height > 0) {
            bytes[40] = (height ushr 24).toByte()
            bytes[41] = (height ushr 16).toByte()
            bytes[42] = (height ushr 8).toByte()
            bytes[43] = height.toByte()
        }
        return bytes
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

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val CLIP_US = 3_000_000L
        val XMP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)

        // 116-byte Exif with UserComment = "oplus_8388608"; ColorOS/realme require it.
        // Width at [28..31], height at [40..43] (big-endian TIFF).
        const val OPPO_EXIF_HEX =
            "FFE100724578696600004D4D002A0000000800040100000400000001000005A001010004000000010000043C" +
                "87690004000000010000003E011200030000000100000000000000000002928600020000000E0000005C" +
                "920800040000000100000000000000006F706C75735F3833383836303800"
    }
}
