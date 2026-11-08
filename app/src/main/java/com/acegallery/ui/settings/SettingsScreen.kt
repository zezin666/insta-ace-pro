package com.acegallery.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val savedHost by viewModel.cameraHost.collectAsStateWithLifecycle(initialValue = "")
    var host by remember(savedHost) { mutableStateOf(savedHost) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(
                icon = Icons.Filled.Settings,
                title = "相机",
            ) {
                Text(
                    text = "相机默认地址为 192.168.42.1；如修改过网络配置可在此覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("相机 IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = { viewModel.save(host) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存")
                }
            }

            SectionCard(
                icon = Icons.Filled.Info,
                title = "诊断",
            ) {
                Text(
                    text = "检查相机连接、文件列表、HTTP HEAD 和缩略图。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("打开诊断")
                }
            }

            SectionCard(
                icon = Icons.Filled.Warning,
                title = "后台下载",
            ) {
                Text(
                    text = "ColorOS / MIUI 可能限制后台下载。建议允许本应用忽略电池优化。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        )
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("电池优化设置")
                }
            }

            SectionCard(
                icon = Icons.Filled.Info,
                title = "关于",
            ) {
                Text(
                    text = "查看版本信息、应用基础信息与参考的开源项目。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = onOpenAbout,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("打开关于")
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}
