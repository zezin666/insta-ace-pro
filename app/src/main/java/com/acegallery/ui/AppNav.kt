package com.acegallery.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.acegallery.download.DownloadService
import com.acegallery.model.CameraFile
import com.acegallery.ui.diagnostics.DiagnosticsScreen
import com.acegallery.ui.gallery.GalleryScreen
import com.acegallery.ui.live.LiveViewScreen
import com.acegallery.ui.settings.AboutScreen
import com.acegallery.ui.settings.SettingsScreen
import com.acegallery.ui.viewer.ViewerScreen
import com.acegallery.ui.viewer.ViewerViewModel

@Composable
fun AceGalleryNav() {
    val navController = rememberNavController()
    var viewerFiles by remember { mutableStateOf<List<CameraFile>>(emptyList()) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showBottomBar = route == "gallery" || route == "live" || route == "settings"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = route == "gallery",
                        onClick = { navController.navigateTopLevel("gallery") },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("相册") },
                    )
                    NavigationBarItem(
                        selected = route == "live",
                        onClick = { navController.navigateTopLevel("live") },
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        label = { Text("实时画面") },
                    )
                    NavigationBarItem(
                        selected = route == "settings",
                        onClick = { navController.navigateTopLevel("settings") },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("设置") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "gallery",
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            enterTransition = {
                fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 10 }
            },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = {
                fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { it / 10 }
            },
        ) {
            composable("gallery") {
                GalleryScreen(
                    onOpenViewer = { files, index ->
                        viewerFiles = files
                        navController.navigate("viewer/$index")
                    },
                )
            }
            composable("viewer/{index}") { entry ->
                val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
                val viewModel: ViewerViewModel = hiltViewModel()
                val context = LocalContext.current
                ViewerScreen(
                    files = viewerFiles,
                    initialIndex = index,
                    session = viewModel.session,
                    thumbnailLoader = viewModel.thumbnailLoader,
                    onDownload = { file ->
                        viewModel.download(file)
                        DownloadService.start(context)
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("settings") {
                SettingsScreen(
                    onBack = {},
                    onOpenDiagnostics = { navController.navigate("diagnostics") },
                    onOpenAbout = { navController.navigate("about") },
                )
            }
            composable("about") {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable("live") {
                LiveViewScreen()
            }
            composable("diagnostics") {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo("gallery") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
