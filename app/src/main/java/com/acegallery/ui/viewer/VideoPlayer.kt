package com.acegallery.ui.viewer

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.acegallery.model.CameraFile
import com.acegallery.session.CameraGateway
import com.acegallery.session.CameraState

@Composable
fun VideoPlayer(
    file: CameraFile,
    session: CameraGateway,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val host = (session.state.value as? CameraState.Connected)?.host
        ?: CameraGateway.DEFAULT_HOST
    val url = "http://$host${file.path}"
    val client = remember(session.state.value) {
        runCatching { session.httpClient() }.getOrNull()
    }

    if (client == null) {
        Text("相机未连接")
        return
    }

    val player = remember(file.path, url) {
        val dataSourceFactory = OkHttpDataSource.Factory(client)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
        update = { view -> view.player = player },
        modifier = modifier,
    )
}
