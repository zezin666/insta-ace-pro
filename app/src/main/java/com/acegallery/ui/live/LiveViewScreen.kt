package com.acegallery.ui.live

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveViewScreen(
    onBack: () -> Unit,
    viewModel: LiveViewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val surfaceView = remember {
        SurfaceView(context).apply {
            holder.setKeepScreenOn(true)
        }
    }
    var surface by remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(surfaceView) {
        val holder = surfaceView.holder
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                surface = h.surface
            }

            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
                surface = h.surface
            }

            override fun surfaceDestroyed(h: SurfaceHolder) {
                surface = null
            }
        }
        holder.addCallback(callback)
        onDispose { holder.removeCallback(callback) }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    LaunchedEffect(surface) {
        val target = surface ?: return@LaunchedEffect
        val decoder = LiveViewDecoder(target)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实时画面") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val status = when {
                        state.recording -> "录制中"
                        state.streaming -> "预览中"
                        state.busy -> "连接中…"
                        else -> "未连接"
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { surfaceView },
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
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color.Red),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "REC",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FilledTonalButton(
                    onClick = viewModel::takePhoto,
                    enabled = state.streaming && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("拍照")
                }
                Button(
                    onClick = viewModel::toggleRecording,
                    enabled = state.streaming && !state.busy,
                    modifier = Modifier.weight(1f),
                    colors = if (state.recording) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text(if (state.recording) "停止录制" else "开始录制")
                }
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
                text = "拍照和录制使用相机当前模式；录制时仍可实时预览。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}
