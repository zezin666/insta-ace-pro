package com.acegallery.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.acegallery.model.CameraFile
import com.acegallery.session.CameraGateway
import com.acegallery.thumbnail.Thumbnail
import com.acegallery.thumbnail.ThumbnailLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PhotoPage(
    file: CameraFile,
    session: CameraGateway,
    thumbnailLoader: ThumbnailLoader,
    onFileReady: (File) -> Unit,
    onToggleChrome: () -> Unit,
) {
    val context = LocalContext.current
    val cache = remember { PhotoCache(session, context.cacheDir) }
    var localFile by remember(file.path) { mutableStateOf<File?>(null) }
    var thumbnail by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.path) {
        thumbnail = withContext(Dispatchers.IO) {
            thumbnailLoader.load(file)?.toImageBitmap()
        }
    }

    LaunchedEffect(file.path) {
        runCatching {
            withContext(Dispatchers.IO) { cache.get(file) }
        }.onSuccess {
            localFile = it
            onFileReady(it)
        }.onFailure {
            error = it.message ?: "加载失败"
        }
    }

    Crossfade(
        targetState = localFile,
        animationSpec = tween(220),
        label = "photoContent",
    ) { ready ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (ready != null) {
                ZoomableImage(file = ready, onToggleChrome = onToggleChrome)
            } else {
                val placeholder = thumbnail
                when {
                    placeholder != null -> Image(
                        bitmap = placeholder,
                        contentDescription = file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )

                    error == null -> CircularProgressIndicator()
                }
                error?.let {
                    Text(
                        text = it,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PreviewPage(file: CameraFile, loader: ThumbnailLoader) {
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
internal fun UnsupportedPage(file: CameraFile, onDownload: () -> Unit) {
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

internal fun detailText(file: CameraFile, sizeBytes: Long?): String = buildString {
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

internal fun isViewablePhoto(file: CameraFile): Boolean =
    file.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp")

internal fun isViewableVideo(file: CameraFile): Boolean =
    file.name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov")

internal fun isDng(file: CameraFile): Boolean =
    file.name.substringAfterLast('.', "").lowercase() == "dng"

private fun Thumbnail.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun share(context: Context, file: File) {
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
