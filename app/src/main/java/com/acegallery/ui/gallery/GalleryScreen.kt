package com.acegallery.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acegallery.model.CameraFile
import com.acegallery.session.CameraState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    onOpenViewer: (List<CameraFile>, Int) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ace 相册", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = viewModel::refresh) { Text("刷新") }
                    TextButton(onClick = onOpenSettings) { Text("设置") }
                }
                Text(
                    text = connectionText(state.connection),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FileFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text(filterLabel(filter)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val error = state.error
            when {
                error != null -> ErrorState(error, viewModel::refresh)

                state.isLoading && state.sections.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.sections.isEmpty() -> EmptyState(
                    connected = state.connection is CameraState.Connected,
                    onRefresh = viewModel::refresh,
                )

                else -> PullToRefreshBox(
                    isRefreshing = state.isLoading,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val flat = state.sections.flatMap { it.files }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.sections.forEach { section ->
                            item(
                                key = "date-${section.date}",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Text(
                                    text = section.date?.toString() ?: "未知日期",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                            items(section.files, key = { it.path }) { file ->
                                ThumbnailTile(
                                    file = file,
                                    loader = viewModel.thumbnails,
                                    selected = file.path in state.selected,
                                    downloaded = false,
                                    onClick = {
                                        val index = flat.indexOf(file)
                                        if (index >= 0) onOpenViewer(flat, index)
                                    },
                                    onLongClick = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(connected: Boolean, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (connected) {
                "相机里还没有照片或视频"
            } else {
                "请先在系统设置里连接相机 WiFi\n" +
                    "SSID 以 Insta360 开头，默认密码 88888888"
            },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRefresh) { Text("重新检测") }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

private fun connectionText(state: CameraState): String = when (state) {
    CameraState.Disconnected -> "未连接相机 WiFi"
    CameraState.Connecting -> "正在连接相机…"
    is CameraState.Connected -> "已连接 ${state.host}"
    is CameraState.Error -> state.message
}

private fun filterLabel(filter: FileFilter): String = when (filter) {
    FileFilter.ALL -> "全部"
    FileFilter.PHOTOS -> "照片"
    FileFilter.VIDEOS -> "视频"
}
