package com.acegallery.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acegallery.download.DownloadManager
import com.acegallery.download.DownloadService
import com.acegallery.model.CameraFile
import com.acegallery.session.CameraState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    onOpenViewer: (List<CameraFile>, Int) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit = {},
    onOpenLive: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloadedPaths.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDownloads by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refresh() }

    val uiKey = when {
        state.error != null -> "error"
        state.isLoading && state.sections.isEmpty() -> "loading"
        state.sections.isEmpty() -> "empty"
        else -> "grid"
    }
    val activeDownloads = downloads.count {
        it.state is DownloadManager.State.Queued ||
            it.state is DownloadManager.State.Downloading
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = if (state.selectionMode) {
                                    "已选 ${state.selected.size}"
                                } else {
                                    "Ace 相册"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                            )
                            ConnectionStatus(state.connection)
                        }
                    },
                    actions = {
                        if (state.selectionMode) {
                            TextButton(onClick = viewModel::clearSelection) { Text("取消") }
                        } else {
                            IconButton(onClick = onOpenLive) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "实时画面")
                            }
                            IconButton(onClick = viewModel::refresh) {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                            }
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Filled.Settings, contentDescription = "设置")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
                FilterRow(state.filter, viewModel::setFilter)
                Spacer(Modifier.height(4.dp))
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                FloatingActionButton(
                    onClick = { showDownloads = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    BadgedBox(
                        badge = {
                            if (activeDownloads > 0) {
                                Badge { Text(activeDownloads.toString()) }
                            }
                        },
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下载")
                    }
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = state.selectionMode,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                viewModel.downloadSelected()
                                DownloadService.start(context)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("下载所选")
                        }
                        OutlinedButton(onClick = viewModel::clearSelection) { Text("取消") }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                ),
        ) {
            Crossfade(
                targetState = uiKey,
                animationSpec = tween(220),
                label = "galleryState",
            ) { key ->
                when (key) {
                    "error" -> ErrorState(
                        message = state.error ?: "加载失败",
                        onRetry = viewModel::refresh,
                    )

                    "loading" -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    "empty" -> EmptyState(
                        connected = state.connection is CameraState.Connected,
                        onRefresh = viewModel::refresh,
                    )

                    else -> PullToRefreshBox(
                        isRefreshing = state.isLoading,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        GalleryGrid(
                            state = state,
                            downloaded = downloaded,
                            onOpenViewer = onOpenViewer,
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }

    if (showDownloads) {
        DownloadSheet(
            downloads = downloads,
            onDismiss = { showDownloads = false },
            onRetry = { file ->
                viewModel.enqueue(listOf(file))
                DownloadService.start(context)
            },
        )
    }
}

@Composable
private fun ConnectionStatus(state: CameraState) {
    val text: String
    val color: Color
    when (state) {
        CameraState.Disconnected -> {
            text = "未连接相机 WiFi"
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }

        CameraState.Connecting -> {
            text = "正在连接相机…"
            color = MaterialTheme.colorScheme.secondary
        }

        is CameraState.Connected -> {
            text = "已连接 ${state.host}"
            color = MaterialTheme.colorScheme.secondary
        }

        is CameraState.Error -> {
            text = state.message
            color = MaterialTheme.colorScheme.error
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FilterRow(selected: FileFilter, onSelect: (FileFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FileFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filterLabel(filter)) },
                leadingIcon = if (selected == filter) {
                    {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun GalleryGrid(
    state: GalleryUiState,
    downloaded: Set<String>,
    onOpenViewer: (List<CameraFile>, Int) -> Unit,
    viewModel: GalleryViewModel,
) {
    val flat = state.sections.flatMap { it.files }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.sections.forEach { section ->
            item(
                key = "date-${section.date}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = section.date?.toString() ?: "未知日期",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${section.files.size} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(section.files, key = { it.path }) { file ->
                ThumbnailTile(
                    file = file,
                    loader = viewModel.thumbnails,
                    selected = file.path in state.selected,
                    downloaded = file.path in downloaded,
                    onClick = {
                        if (state.selectionMode) {
                            viewModel.toggleSelection(file.path)
                        } else {
                            val index = flat.indexOf(file)
                            if (index >= 0) onOpenViewer(flat, index)
                        }
                    },
                    onLongClick = { viewModel.toggleSelection(file.path) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(connected: Boolean, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (connected) "相机里还没有照片或视频" else "连接相机 WiFi 后开始浏览",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (connected) {
                "拍摄一些素材再回来"
            } else {
                "SSID 以 Insta360 开头，默认密码 88888888"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("重新检测")
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("重试")
        }
    }
}

private fun filterLabel(filter: FileFilter): String = when (filter) {
    FileFilter.ALL -> "全部"
    FileFilter.PHOTOS -> "照片"
    FileFilter.VIDEOS -> "视频"
}
