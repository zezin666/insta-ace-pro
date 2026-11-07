package com.acegallery.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val Amber = Color(0xFFF5C76B)
private val Teal = Color(0xFF7DD3C7)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF241A00),
    primaryContainer = Color(0xFF3D2E00),
    onPrimaryContainer = Color(0xFFFFE3A3),
    secondary = Teal,
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF0F4A43),
    onSecondaryContainer = Color(0xFF9FF2E6),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFECEEF2),
    surface = Color(0xFF11151A),
    onSurface = Color(0xFFECEEF2),
    surfaceVariant = Color(0xFF1A2028),
    onSurfaceVariant = Color(0xFFA7B0BB),
    outline = Color(0xFF3A434F),
    outlineVariant = Color(0xFF262D36),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE08A),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = Color(0xFF2E6B61),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEEF1F5),
    onSurfaceVariant = Color(0xFF44474C),
    outline = Color(0xFFC4C7CC),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AceGalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = Typography(),
        content = content,
    )
}
