package com.acegallery.ui.viewer

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.acegallery.export.LivePhotoExporter
import com.acegallery.export.LivePhotoTiming
import com.acegallery.model.CameraFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen editor for the motion part and cover of an exported live photo.
 *
 * The clip is always at most 3 seconds ([LivePhotoTiming.MAX_CLIP_MS]); the first
 * slider moves its start, the second picks the cover frame inside it. Short videos
 * export their full remaining length.
 */
@Composable
fun LivePhotoExportDialog(
    source: File,
    file: CameraFile,
    exporter: LivePhotoExporter,
    onDismiss: () -> Unit,
    onExported: () -> Unit,
) {
    var durationMs by remember { mutableLongStateOf(0L) }
    var clipStartMs by remember { mutableLongStateOf(0L) }
    var coverMs by remember { mutableLongStateOf(0L) }
    var rotation by remember { mutableIntStateOf(0) }
    var metadataReady by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var previewFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(source) {
        val metadata = withContext(Dispatchers.IO) {
            MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(source.absolutePath)
                    val duration = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    val degrees = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                    duration to degrees
                } finally {
                    retriever.release()
                }
            }
        }
        durationMs = metadata.first
        rotation = metadata.second
        clipStartMs = 0L
        coverMs = LivePhotoTiming.defaultCover(LivePhotoTiming.clip(metadata.first, 0L))
        metadataReady = true
    }

    val clip = LivePhotoTiming.clip(durationMs, clipStartMs)
    val cover = LivePhotoTiming.clampCover(clip, coverMs)
    val maxStart = (durationMs - LivePhotoTiming.MAX_CLIP_MS).coerceAtLeast(0L)
    val coverRange = clip.endMs - clip.startMs
    val shortVideo = durationMs in 1L..(LivePhotoTiming.MAX_CLIP_MS - 1)

    LaunchedEffect(cover, source, rotation, durationMs) {
        if (durationMs <= 0L) return@LaunchedEffect
        delay(120)
        preview = withContext(Dispatchers.IO) {
            MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(source.absolutePath)
                    val raw = retriever.getFrameAtTime(
                        cover * 1_000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ) ?: retriever.getFrameAtTime(
                        clip.startMs * 1_000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                    raw?.let { frame ->
                        val oriented = if (rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            Bitmap.createBitmap(
                                frame, 0, 0, frame.width, frame.height, matrix, true,
                            )
                        } else {
                            frame
                        }
                        val width = 960
                        if (oriented.width > width) {
                            Bitmap.createScaledBitmap(
                                oriented,
                                width,
                                (width * oriented.height / oriented.width).coerceAtLeast(1),
                                true,
                            )
                        } else {
                            oriented
                        }
                    }
                } finally {
                    retriever.release()
                }
            }
        }
        previewFailed = preview == null
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text("导出 Live 图", style = MaterialTheme.typography.titleLarge)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = preview
                    when {
                        bitmap != null -> Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "封面预览",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        metadataReady && durationMs <= 0L -> Text(
                            text = "无法读取视频时长",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        previewFailed -> Text(
                            text = "无法读取封面帧",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> CircularProgressIndicator()
                    }
                }

                Text(
                    text = "片段 ${clip.startMs / 1000}–${clip.endMs / 1000} 秒 · " +
                        "封面 ${cover / 1000} 秒",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (shortVideo) {
                    Text(
                        text = "视频不足 3 秒，将导出全长",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = "片段起点",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Slider(
                    value = clip.startMs.toFloat(),
                    onValueChange = { clipStartMs = it.toLong().coerceIn(0L, maxStart) },
                    valueRange = 0f..maxStart.coerceAtLeast(1L).toFloat(),
                    enabled = maxStart > 0L && !busy,
                )

                Text("封面位置", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = cover.toFloat(),
                    onValueChange = {
                        coverMs = it.toLong().coerceIn(clip.startMs, clip.endMs)
                    },
                    valueRange = clip.startMs.toFloat()..(clip.startMs + coverRange.coerceAtLeast(1L)).toFloat(),
                    enabled = coverRange > 0L && !busy,
                )

                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    Button(
                        enabled = !busy && durationMs > 0L && clip.durationMs > 0L,
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        exporter.export(source, file, durationMs, clip.startMs, cover)
                                    }
                                }.onSuccess {
                                    onExported()
                                }.onFailure {
                                    error = it.message ?: "导出失败"
                                    busy = false
                                }
                            }
                        },
                    ) { Text(if (busy) "导出中…" else "导出") }
                }
            }
        }
    }
}
