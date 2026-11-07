package com.acegallery.ui.gallery

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.acegallery.download.DownloadManager
import com.acegallery.model.CameraFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(
    downloads: List<DownloadManager.Item>,
    onDismiss: () -> Unit,
    onRetry: (CameraFile) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text("下载", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (downloads.isNotEmpty()) {
                    Text(
                        text = "${downloads.count { it.state == DownloadManager.State.Done }}/${downloads.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (downloads.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "暂无下载任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(downloads, key = { it.file.path }) { item ->
                        DownloadRow(item = item, onRetry = onRetry)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadManager.Item,
    onRetry: (CameraFile) -> Unit,
) {
    val state = item.state
    val icon = when (state) {
        DownloadManager.State.Queued -> Icons.Filled.DateRange
        is DownloadManager.State.Downloading -> Icons.Filled.KeyboardArrowDown
        DownloadManager.State.Done -> Icons.Filled.CheckCircle
        is DownloadManager.State.Failed -> Icons.Filled.Warning
    }
    val tint = when (state) {
        DownloadManager.State.Done -> MaterialTheme.colorScheme.secondary
        is DownloadManager.State.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stateText(state),
                style = MaterialTheme.typography.bodySmall,
                color = if (state is DownloadManager.State.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (state is DownloadManager.State.Downloading && state.total != null) {
                val progress by animateFloatAsState(
                    targetValue = (state.bytes.toFloat() / state.total).coerceIn(0f, 1f),
                    animationSpec = tween(300),
                    label = "downloadProgress",
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
        if (state is DownloadManager.State.Failed) {
            TextButton(onClick = { onRetry(item.file) }) { Text("重试") }
        } else if (state is DownloadManager.State.Downloading && state.total == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

private fun stateText(state: DownloadManager.State): String = when (state) {
    DownloadManager.State.Queued -> "等待中"
    is DownloadManager.State.Downloading -> {
        val total = state.total
        if (total != null) {
            "下载中 ${formatBytes(state.bytes)} / ${formatBytes(total)}"
        } else {
            "下载中 ${formatBytes(state.bytes)}"
        }
    }

    DownloadManager.State.Done -> "完成"
    is DownloadManager.State.Failed -> "失败：${state.reason}"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
