package com.acegallery.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.acegallery.export.LivePhotoExporter
import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import com.acegallery.session.CameraGateway
import com.acegallery.thumbnail.Thumbnail
import com.acegallery.thumbnail.ThumbnailLoader
import java.io.File
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ViewerScreen(
    files: List<CameraFile>,
    initialIndex: Int,
    session: CameraGateway,
    thumbnailLoader: ThumbnailLoader,
    onDownload: (CameraFile) -> Unit,
    onBack: () -> Unit,
) {
    if (files.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有可查看的文件", color = Color.White)
        }
        return
    }

    val context = LocalContext.current
    val view = LocalView.current
    val chromeVisible = true

    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, files.lastIndex),
    ) { files.size }
    val current = files[pagerState.currentPage]
    var currentSize by remember(current.path) { mutableStateOf(current.sizeBytes) }
    var readyFiles by remember { mutableStateOf<Map<String, File>>(emptyMap()) }
    val exporter = remember { LivePhotoExporter(context) }
    val exportScope = rememberCoroutineScope()
    var exportBusy by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(exportMessage) {
        val message = exportMessage
        if (message != null) {
            delay(4_000)
            if (exportMessage == message) exportMessage = null
        }
    }

    val startExport: (CameraFile) -> Unit = { file ->
        if (!exportBusy) {
            exportScope.launch {
                exportBusy = true
                exportMessage = null
                try {
                    val source = withContext(Dispatchers.IO) {
                        PhotoCache(session, context.cacheDir).get(file)
                    }
                    withContext(Dispatchers.IO) { exporter.export(source, file) }
                    exportMessage = "已导出 Live 图到相册"
                } catch (exc: Exception) {
                    exportMessage = exc.message ?: "导出 Live 图失败"
                } finally {
                    exportBusy = false
                }
            }
        }
    }

    LaunchedEffect(current.path) {
        if (currentSize == null) {
            currentSize = runCatching {
                withContext(Dispatchers.IO) { session.get().size(current.path) }
            }.getOrNull()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 12.dp,
        ) { page ->
            val file = files[page]
            val offset = (
                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                ).absoluteValue
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = 1f - (offset * 0.06f).coerceIn(0f, 0.06f)
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - (offset * 0.35f).coerceIn(0f, 0.35f)
                    },
            ) {
                when {
                    file.kind == CameraFileKind.PHOTO && isViewablePhoto(file) ->
                        PhotoPage(
                            file = file,
                            session = session,
                            onFileReady = { ready ->
                                readyFiles = readyFiles + (file.path to ready)
                            },
                        )

                    file.kind == CameraFileKind.PHOTO && isDng(file) ->
                        PreviewPage(file = file, loader = thumbnailLoader)

                    file.kind == CameraFileKind.VIDEO && isViewableVideo(file) ->
                        VideoPlayer(
                            file = file,
                            session = session,
                            modifier = Modifier.fillMaxSize(),
                        )

                    else -> UnsupportedPage(
                        file = file,
                        onDownload = { onDownload(file) },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopChrome(
                file = current,
                page = pagerState.currentPage + 1,
                total = files.size,
                onBack = onBack,
                onDownload = { onDownload(current) },
                onExportLive = if (current.kind == CameraFileKind.VIDEO) {
                    { startExport(current) }
                } else {
                    null
                },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible && current.kind == CameraFileKind.PHOTO,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomChrome(
                file = current,
                sizeBytes = currentSize,
                onShare = readyFiles[current.path]?.let { file -> { share(context, file) } },
            )
        }

        exportMessage?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .background(
                        Color.Black.copy(alpha = 0.78f),
                        RoundedCornerShape(24.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (exportBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("生成 Live 图") },
            text = { Text("正在准备视频并写入系统相册，请稍候…") },
            confirmButton = {},
        )
    }
}

@Composable
private fun TopChrome(
    file: CameraFile,
    page: Int,
    total: Int,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onExportLive: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent),
                ),
            )
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$page / $total",
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (onExportLive != null) {
                TextButton(onClick = onExportLive) {
                    Text(
                        text = "LIVE",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "下载",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun BottomChrome(
    file: CameraFile,
    sizeBytes: Long?,
    onShare: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                ),
            )
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Column {
            Text(
                text = file.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detailText(file, sizeBytes),
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall,
            )
            if (onShare != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onShare,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("分享")
                }
            }
        }
    }
}

@Composable
private fun PhotoPage(
    file: CameraFile,
    session: CameraGateway,
    onFileReady: (File) -> Unit,
) {
    val context = LocalContext.current
    val cache = remember { PhotoCache(session, context.cacheDir) }
    var localFile by remember(file.path) { mutableStateOf<File?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.path) {
        runCatching { cache.get(file) }
            .onSuccess {
                localFile = it
                onFileReady(it)
            }
            .onFailure { error = it.message ?: "加载失败" }
    }

    val path = localFile
    val message = error
    if (path == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (message != null) {
                Text(message, color = Color.White)
            } else {
                CircularProgressIndicator()
            }
        }
    } else {
        ZoomableImage(file = path)
    }
}

@Composable
private fun ZoomableImage(file: File) {
    var targetScale by remember { mutableFloatStateOf(1f) }
    var targetOffset by remember { mutableStateOf(Offset.Zero) }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 1200f),
        label = "photoScale",
    )
    val offset by animateOffsetAsState(
        targetValue = targetOffset,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 1200f),
        label = "photoOffset",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            targetScale = (targetScale * zoom).coerceIn(1f, 5f)
                            targetOffset += pan
                            event.changes.forEach { it.consume() }
                        } else if (targetScale > 1.01f && pressed == 1) {
                            targetOffset += event.calculatePan()
                            event.changes.forEach { it.consume() }
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .then(
                if (targetScale > 1.01f) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                targetScale = 1f
                                targetOffset = Offset.Zero
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

@Composable
private fun PreviewPage(file: CameraFile, loader: ThumbnailLoader) {
    var image by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file.path) {
        image = withContext(Dispatchers.IO) {
            loader.load(file)?.toImageBitmap()
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator()
        }
        Text(
            text = "DNG 预览（原图请下载）",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
        )
    }
}

@Composable
private fun UnsupportedPage(file: CameraFile, onDownload: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "该格式仅支持下载",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = file.name,
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onDownload) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("下载")
            }
        }
    }
}

private fun detailText(file: CameraFile, sizeBytes: Long?): String = buildString {
    file.capturedAt?.let {
        append(it)
    }
    sizeBytes?.let {
        if (isNotEmpty()) append(" · ")
        append(it)
        append(" B")
    }
    if (isEmpty()) append(file.name)
}

private fun isViewablePhoto(file: CameraFile): Boolean =
    file.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp")

private fun isViewableVideo(file: CameraFile): Boolean =
    file.name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov")

private fun isDng(file: CameraFile): Boolean =
    file.name.substringAfterLast('.', "").lowercase() == "dng"

private fun Thumbnail.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (file.extension.lowercase() == "mp4") "video/*" else "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享"))
}
