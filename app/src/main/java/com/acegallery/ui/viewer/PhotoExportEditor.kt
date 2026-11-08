package com.acegallery.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.acegallery.export.WatermarkExporter
import com.acegallery.export.WatermarkOptions
import com.acegallery.export.WatermarkPosition
import com.acegallery.model.CameraFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen watermarked-photo editor: EXIF-oriented preview, five position presets, and an
 * export action that writes a new `_WM.jpg` while leaving the original untouched.
 */
@Composable
fun PhotoExportEditor(
    source: File,
    file: CameraFile,
    exporter: WatermarkExporter,
    onDismiss: () -> Unit,
    onExported: () -> Unit,
) {
    var options by remember { mutableStateOf(WatermarkOptions()) }
    var base by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = true) { if (!busy) onDismiss() }

    LaunchedEffect(source) {
        try {
            base = withContext(Dispatchers.IO) {
                decodeOrientedScaled(source, PREVIEW_MAX_SIZE)
            }
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Throwable) {
            previewError = exc.readableMessage("无法读取照片")
        }
    }

    LaunchedEffect(base, options) {
        val decoded = base ?: return@LaunchedEffect
        try {
            preview = withContext(Dispatchers.IO) { exporter.renderPreview(decoded, options) }
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Throwable) {
            previewError = exc.readableMessage("无法生成预览")
        }
    }

    // Compose disposes these effects after the new composition is applied, so a bitmap that has
    // just been replaced (or whose editor is being dismissed) is recycled only once it is no
    // longer referenced by the Image. Base is only read by renderPreview, which copies it.
    val currentPreview = preview
    DisposableEffect(currentPreview) {
        onDispose { currentPreview?.recycle() }
    }
    val currentBase = base
    DisposableEffect(currentBase) {
        onDispose { currentBase?.recycle() }
    }

    Box(Modifier.fillMaxSize()) {
        // Swallow touches so pager gestures underneath the full-screen editor cannot fire.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                },
        )
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, enabled = !busy) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                }
                Text(
                    text = "导出水印照片",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val image = preview
                when {
                    image != null -> Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = "水印预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    previewError != null -> Text(
                        text = previewError.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> CircularProgressIndicator(color = Color.White)
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text("水印位置", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    PositionPresets(
                        selected = options.position,
                        enabled = !busy,
                        onSelect = { options = WatermarkOptions(it) },
                    )
                    notice?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    exportError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("取消") }
                        Button(
                            onClick = {
                                busy = true
                                exportError = null
                                notice = null
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            exporter.exportPhoto(source, file, options)
                                        }
                                        if (result.downscaled) {
                                            notice = DOWNSCALED_MESSAGE
                                            delay(DOWNSCALED_MESSAGE_MS)
                                        }
                                        onExported()
                                    } catch (exc: CancellationException) {
                                        throw exc
                                    } catch (exc: Throwable) {
                                        exportError = exc.readableMessage("导出失败，请重试")
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy && base != null,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (busy) "导出中…" else "导出") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PositionPresets(
    selected: WatermarkPosition,
    enabled: Boolean,
    onSelect: (WatermarkPosition) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PRESET_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, position) ->
                    FilterChip(
                        selected = selected == position,
                        onClick = { onSelect(position) },
                        label = { Text(label) },
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

/** Decodes a bounded-size preview with the EXIF rotation applied, matching the exported file. */
private fun decodeOrientedScaled(file: File, maxSize: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取照片" }

    var sample = 1
    while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
        sample *= 2
    }
    val decoded = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: error("无法读取照片")

    val degrees = try {
        ExifInterface(file.absolutePath).rotationDegrees
    } catch (_: Exception) {
        0
    }
    if (degrees == 0) return decoded

    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = try {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    } catch (exc: Throwable) {
        decoded.recycle()
        throw exc
    }
    if (rotated !== decoded) decoded.recycle()
    return rotated
}

private fun Throwable.readableMessage(fallback: String): String =
    message?.takeIf { it.isNotBlank() } ?: fallback

private const val PREVIEW_MAX_SIZE = 1200
private const val DOWNSCALED_MESSAGE = "图片较大，已降低导出分辨率"
private const val DOWNSCALED_MESSAGE_MS = 1_500L

private val POSITION_PRESETS = listOf(
    "底部居中" to WatermarkPosition.BOTTOM_CENTER,
    "左上" to WatermarkPosition.TOP_START,
    "右上" to WatermarkPosition.TOP_END,
    "左下" to WatermarkPosition.BOTTOM_START,
    "右下" to WatermarkPosition.BOTTOM_END,
)

private val PRESET_ROWS = POSITION_PRESETS.chunked(3)
