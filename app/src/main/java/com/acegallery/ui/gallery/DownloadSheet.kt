package com.acegallery.ui.gallery

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.acegallery.download.DownloadManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(
    downloads: List<DownloadManager.Item>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text("下载", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (downloads.isEmpty()) {
                Text("暂无下载任务", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(downloads, key = { it.file.path }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.file.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stateText(item.state),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            val state = item.state
                            if (state is DownloadManager.State.Downloading) {
                                val total = state.total
                                if (total != null && total > 0) {
                                    LinearProgressIndicator(
                                        progress = {
                                            (state.bytes.toFloat() / total).coerceIn(0f, 1f)
                                        },
                                        modifier = Modifier.width(80.dp),
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun stateText(state: DownloadManager.State): String = when (state) {
    DownloadManager.State.Queued -> "等待中"
    is DownloadManager.State.Downloading -> "下载中 ${state.bytes}/${state.total ?: "?"}"
    DownloadManager.State.Done -> "完成"
    is DownloadManager.State.Failed -> "失败：${state.reason}"
}
