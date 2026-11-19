package com.acegallery.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acegallery.export.LivePhotoExporter
import com.acegallery.export.WatermarkExporter
import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import com.acegallery.session.CameraGateway
import com.acegallery.thumbnail.ThumbnailLoader
import java.io.File
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    val viewModel: ViewerViewModel = hiltViewModel()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    var chromeVisible by rememberSaveable { mutableStateOf(true) }

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
    val watermarkExporter = remember { WatermarkExporter(context) }

    LaunchedEffect(exportState.message) {
        val message = exportState.message
        if (message != null) {
            delay(4_000)
            viewModel.clearExportMessage(message)
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
            val photoPage = file.kind == CameraFileKind.PHOTO && isViewablePhoto(file)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = 1f - (offset * 0.06f).coerceIn(0f, 0.06f)
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - (offset * 0.35f).coerceIn(0f, 0.35f)
                    }
                    .then(
                        if (photoPage) {
                            Modifier
                        } else {
                            Modifier.pointerInput(file.path) {
                                detectTapGestures(
                                    onTap = { chromeVisible = !chromeVisible },
                                )
                            }
                        },
                    ),
            ) {
                when {
                    photoPage ->
                        PhotoPage(
                            file = file,
                            session = session,
                            thumbnailLoader = thumbnailLoader,
                            onFileReady = { ready ->
                                readyFiles = readyFiles + (file.path to ready)
                            },
                            onToggleChrome = { chromeVisible = !chromeVisible },
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
                    { viewModel.prepareExport(current, live = true) }
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
                onWatermark = if (isViewablePhoto(current)) {
                    { viewModel.prepareExport(current, live = false) }
                } else {
                    null
                },
            )
        }

        exportState.message?.let { message ->
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

        val expSrc = exportState.exportSource
        val expF = exportState.exportFile
        if (expSrc != null && expF != null) {
            PhotoExportEditor(
                source = expSrc,
                file = expF,
                exporter = watermarkExporter,
                onDismiss = viewModel::dismissPhotoExport,
                onExported = viewModel::onPhotoExported,
            )
        }
    }

    val liveSrc = exportState.liveSource
    val liveF = exportState.liveFile
    if (liveSrc != null && liveF != null) {
        LivePhotoExportDialog(
            source = liveSrc,
            file = liveF,
            exporter = exporter,
            onDismiss = viewModel::dismissLiveExport,
            onExported = viewModel::onLiveExported,
        )
    }

    if (exportState.busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在导出") },
            text = { Text("正在准备文件并写入系统相册，请稍候…") },
            confirmButton = {},
        )
    }
}
