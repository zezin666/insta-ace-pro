package com.acegallery.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.acegallery.model.CameraFile
import com.acegallery.ui.diagnostics.DiagnosticsScreen
import com.acegallery.ui.gallery.GalleryScreen
import com.acegallery.ui.settings.SettingsScreen
import com.acegallery.ui.viewer.ViewerScreen
import com.acegallery.ui.viewer.ViewerViewModel

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
            val viewModel: ViewerViewModel = hiltViewModel()
            ViewerScreen(
                files = viewerFiles,
                initialIndex = index,
                session = viewModel.session,
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenDiagnostics = { navController.navigate("diagnostics") },
            )
        }
        composable("diagnostics") {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }
    }
}
