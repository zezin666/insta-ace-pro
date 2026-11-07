package com.acegallery.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import com.acegallery.session.CameraGateway
import com.acegallery.thumbnail.Thumbnail
import com.acegallery.thumbnail.ThumbnailLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
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
            Text("没有可查看的文件")
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, files.lastIndex),
    ) { files.size }
    val current = files[pagerState.currentPage]
    var currentSize by remember(current.path) { mutableStateOf(current.sizeBytes) }

    LaunchedEffect(current.path) {
        if (currentSize == null) {
            currentSize = runCatching {
                withContext(Dispatchers.IO) { session.get().size(current.path) }
            }.getOrNull()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回", color = Color.White) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = detailText(current, currentSize),
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { onDownload(current) }) {
                Text("下载", color = Color.White)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val file = files[page]
            when {
                file.kind == CameraFileKind.PHOTO && isViewablePhoto(file) ->
                    PhotoPage(file = file, session = session)

                file.kind == CameraFileKind.PHOTO && isDng(file) ->
                    PreviewPage(file = file, loader = thumbnailLoader)

                file.kind == CameraFileKind.VIDEO && isViewableVideo(file) ->
                    VideoPlayer(file = file, session = session, modifier = Modifier.fillMaxSize())

                else -> UnsupportedPage(file = file, onDownload = { onDownload(file) })
            }
        }
    }
}

@Composable
private fun PhotoPage(file: CameraFile, session: CameraGateway) {
    val context = LocalContext.current
    val cache = remember { PhotoCache(session, context.cacheDir) }
    var localFile by remember(file.path) { mutableStateOf<File?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.path) {
        runCatching { cache.get(file) }
            .onSuccess { localFile = it }
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
        Box(Modifier.fillMaxSize()) {
            ZoomableImage(path)
            TextButton(
                onClick = { share(context, path) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Text("分享", color = Color.White)
            }
        }
    }
}

@Composable
private fun ZoomableImage(file: File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset += pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                )
            },
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
            "DNG 预览（原图请下载）",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

@Composable
private fun UnsupportedPage(file: CameraFile, onDownload: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("该格式仅支持下载", color = Color.White)
            Text(file.name, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onDownload) { Text("下载") }
        }
    }
}

private fun detailText(file: CameraFile, sizeBytes: Long?): String = buildString {
    append(file.name)
    file.capturedAt?.let {
        append(" · ")
        append(it)
    }
    sizeBytes?.let {
        append(" · ")
        append(it)
        append(" B")
    }
}

private fun isViewablePhoto(file: CameraFile): Boolean =
    file.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp")

private fun isViewableVideo(file: CameraFile): Boolean =
    file.name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov")

private fun isDng(file: CameraFile): Boolean =
    file.name.substringAfterLast('.', "").lowercase() == "dng"

private fun Thumbnail.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

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
