package com.acegallery.ui.live

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acegallery.live.LiveViewDecoder
import com.acegallery.session.CaptureMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveViewScreen(
    onBack: (() -> Unit)? = null,
    viewModel: LiveViewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val textureView = remember { TextureView(context) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var flash by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }

    DisposableEffect(textureView) {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                surface?.release()
                surface = Surface(texture)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
        if (textureView.isAvailable && surface == null) {
            textureView.surfaceTexture?.let { surface = Surface(it) }
        }
        onDispose {
            surface?.release()
            surface = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    LaunchedEffect(surface) {
        val target = surface ?: return@LaunchedEffect
        val decoder = LiveViewDecoder(target, onVideoSize = viewModel::onVideoSize)
        try {
            coroutineScope {
                val collectJob = launch(Dispatchers.IO) {
                    viewModel.packets.collect { packet -> decoder.feed(packet) }
                }
                viewModel.start()
                collectJob.join()
            }
        } finally {
            decoder.release()
        }
    }

    LaunchedEffect(flash) {
        if (flash) {
            delay(160)
            flash = false
        }
    }

    LaunchedEffect(state.recording) {
        recordSeconds = 0
        if (state.recording) {
            while (true) {
                delay(1_000)
                recordSeconds++
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实时画面") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    ModeChip(mode = state.captureMode, busy = state.busy)
                    Spacer(Modifier.width(8.dp))
                    StatusDot(
                        recording = state.recording,
                        streaming = state.streaming,
                        busy = state.busy,
                        seconds = recordSeconds,
                    )
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val params = state.liveParams ?: state.decodedParams
                val ratio = if (params != null && params.width > 0 && params.height > 0) {
                    params.width.toFloat() / params.height
                } else {
                    16f / 9f
                }
                val size = previewSize(maxWidth.value, maxHeight.value * 0.55f, ratio)
                Box(
                    modifier = Modifier
                        .width(size.width.dp)
                        .height(size.height.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black),
                ) {
                AndroidView(
                    factory = { textureView },
                    modifier = Modifier.fillMaxSize(),
                )
                if (!state.streaming) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (state.busy) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                text = state.message ?: "等待相机画面…",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (state.recording) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .background(
                                Color.Black.copy(alpha = 0.55f),
                                RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.Red),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "REC ${formatDuration(recordSeconds)}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (flash) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.75f)),
                    )
                }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleControl(
                    label = "拍照",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurface,
                    enabled = state.streaming && !state.busy && !state.recording,
                    onClick = {
                        viewModel.takePhoto()
                        flash = true
                    },
                )
                Spacer(Modifier.width(36.dp))
                CircleControl(
                    label = if (state.recording) "停止" else "录制",
                    container = if (state.recording) {
                        MaterialTheme.colorScheme.error
                    } else {
                        Color(0xFFD32F2F)
                    },
                    content = Color.White,
                    enabled = state.streaming && !state.busy && !state.capturing,
                    onClick = viewModel::toggleRecording,
                )
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            Text(
                text = "拍照/录制会自动把相机切换到照片/视频模式。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ModeChip(mode: CaptureMode, busy: Boolean) {
    val text = when {
        busy -> "切换模式…"
        mode == CaptureMode.PHOTO -> "照片"
        mode == CaptureMode.VIDEO -> "视频"
        else -> "未知"
    }
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusDot(
    recording: Boolean,
    streaming: Boolean,
    busy: Boolean,
    seconds: Int,
) {
    val text = when {
        recording -> "录制中 ${formatDuration(seconds)}"
        streaming -> "预览中"
        busy -> "连接中…"
        else -> "未连接"
    }
    val color = when {
        recording -> Color(0xFFE53935)
        streaming -> MaterialTheme.colorScheme.secondary
        busy -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun CircleControl(
    label: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun formatDuration(seconds: Int): String =
    "%02d:%02d".format(seconds / 60, seconds % 60)
