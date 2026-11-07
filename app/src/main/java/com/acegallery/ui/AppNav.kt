package com.acegallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.acegallery.model.CameraFile
import com.acegallery.ui.gallery.GalleryScreen

@Composable
fun AceGalleryNav() {
    val navController = rememberNavController()
    var viewerFiles by remember { mutableStateOf<List<CameraFile>>(emptyList()) }

    NavHost(navController = navController, startDestination = "gallery") {
        composable("gallery") {
            GalleryScreen(
                onOpenViewer = { files, index ->
                    viewerFiles = files
                    navController.navigate("viewer/$index")
                },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable("viewer/{index}") { entry ->
            val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
            PlaceholderViewer(
                files = viewerFiles,
                index = index,
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            PlaceholderSettings(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun PlaceholderViewer(files: List<CameraFile>, index: Int, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(files.getOrNull(index)?.name ?: "查看器")
        Spacer(Modifier.height(12.dp))
        Button(onClick = onBack) { Text("返回") }
    }
}

@Composable
private fun PlaceholderSettings(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("设置")
        Spacer(Modifier.height(12.dp))
        Button(onClick = onBack) { Text("返回") }
    }
}
