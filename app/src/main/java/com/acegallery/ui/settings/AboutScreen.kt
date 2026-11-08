package com.acegallery.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.acegallery.BuildConfig
import com.acegallery.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "应用图标",
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF14171D))
                        .padding(6.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Ace 相册", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "com.acegallery",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AboutCard("简介") {
                Text(
                    text = "为 Insta360 Ace Pro 1 打造的轻量 Android 相册：通过相机 Wi-Fi 直连，" +
                        "浏览、下载和预览原片，并支持水印导出、Live 图制作与实时取景控制。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "个人侧载工具，非 Insta360 官方产品；对相机只读，不访问互联网。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AboutCard("基础信息") {
                InfoRow("版本", "${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）")
                InfoRow("包名", "com.acegallery")
                InfoRow("相机地址", "192.168.42.1（可在设置中修改）")
                InfoRow("系统要求", "Android 10 及以上（minSdk 29）")
                InfoRow("网络", "仅连接相机，不发起互联网请求")
            }

            AboutCard("协议研究参考") {
                LinkRow(
                    title = "RigacciOrg/insta360-wifi-api",
                    subtitle = "Python 实现的 Insta360 Wi-Fi 协议，帧格式、命令码与同步/保活参考",
                    url = "https://github.com/RigacciOrg/insta360-wifi-api",
                )
                LinkRow(
                    title = "akonstster/go-ultra-transfer",
                    subtitle = "GO Ultra 协议实测记录，帧格式与命令号参考（型号行为不保证一致）",
                    url = "https://github.com/akonstster/go-ultra-transfer",
                )
            }

            AboutCard("开源依赖") {
                LinkRow("Kotlin / Kotlin Coroutines", "Apache-2.0", "https://kotlinlang.org")
                LinkRow(
                    "Jetpack Compose / Material 3",
                    "Apache-2.0 · 声明式 UI",
                    "https://developer.android.com/jetpack/compose",
                )
                LinkRow(
                    "AndroidX（Core / Lifecycle / Navigation / DataStore / ExifInterface）",
                    "Apache-2.0",
                    "https://developer.android.com/jetpack/androidx",
                )
                LinkRow("Hilt (Dagger)", "Apache-2.0 · 依赖注入", "https://dagger.dev/hilt/")
                LinkRow("OkHttp", "Apache-2.0 · HTTP 客户端", "https://square.github.io/okhttp/")
                LinkRow(
                    "AndroidX Media3（ExoPlayer / Transformer）",
                    "Apache-2.0 · 播放与转码",
                    "https://developer.android.com/media/media3",
                )
                LinkRow(
                    "Protocol Buffers（protobuf-javalite）",
                    "BSD-3-Clause · 相机协议编解码",
                    "https://protobuf.dev",
                )
                LinkRow("Coil", "Apache-2.0 · 图片加载", "https://coil-kt.github.io/coil/")
            }
        }
    }
}

@Composable
private fun AboutCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
