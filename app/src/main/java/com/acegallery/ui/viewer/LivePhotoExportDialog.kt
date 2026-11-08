package com.acegallery.ui.viewer

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.acegallery.export.LivePhotoExporter
import com.acegallery.model.CameraFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LivePhotoExportDialog(
    source: File,
    file: CameraFile,
    exporter: LivePhotoExporter,
    onDismiss: () -> Unit,
    onExported: () -> Unit,
) {
    var durationMs by remember { mutableLongStateOf(0L) }
    var startMs by remember { mutableLongStateOf(0L) }
    var coverMs by remember { mutableLongStateOf(0L) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(source) {
        durationMs = withContext(Dispatchers.IO) {
            MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(source.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                } finally {
                    retriever.release()
                }
            }
        }
        startMs = 0L
        coverMs = 1_500L.coerceAtMost(maxOf(0L, durationMs - 1))
    }

    LaunchedEffect(coverMs, source) {
        delay(120)
        preview = withContext(Dispatchers.IO) {
            MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(source.absolutePath)
                    retriever.getFrameAtTime(
                        coverMs * 1_000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )?.let { frame ->
                        val width = 480
                        Bitmap.createScaledBitmap(
                            frame,
                            width,
                            (width * frame.height / frame.width).coerceAtLeast(1),
                            true,
                        )
                    }
                } finally {
                    retriever.release()
                }
            }
        }
    }

    val maxStart = maxOf(0L, durationMs - 3_000)
    val maxCover = minOf(durationMs, startMs + 3_000).coerceAtLeast(startMs)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("导出 Live 图") },
        text = {
            Column {
                preview?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    )
                }
                Text(
                    text = "片段 ${startMs / 1000}–${(startMs + 3_000) / 1000} 秒 · 封面 ${coverMs / 1000} 秒",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "片段起点",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Slider(
                    value = startMs.toFloat(),
                    onValueChange = {
                        startMs = it.toLong().coerceIn(0L, maxStart)
                        coverMs = coverMs.coerceIn(startMs, minOf(durationMs, startMs + 3_000))
                    },
                    valueRange = 0f..maxStart.toFloat().coerceAtLeast(1f),
                )
                Text("封面位置", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = coverMs.toFloat(),
                    onValueChange = { coverMs = it.toLong().coerceIn(startMs, maxCover) },
                    valueRange = startMs.toFloat()..maxCover.toFloat().coerceAtLeast(startMs + 1f),
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && durationMs > 0,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                exporter.export(source, file, startMs, coverMs)
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
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}
