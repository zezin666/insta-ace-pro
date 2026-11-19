package com.acegallery.ui.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File

@Composable
internal fun ZoomableImage(
    file: File,
    onToggleChrome: () -> Unit,
) {
    val context = LocalContext.current
    var viewport by remember(file) { mutableStateOf(IntSize.Zero) }
    var targetScale by remember(file.path) { mutableFloatStateOf(1f) }
    var targetOffset by remember(file.path) { mutableStateOf(Offset.Zero) }
    val currentOnToggleChrome by rememberUpdatedState(onToggleChrome)
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 1200f),
        label = "photoScale",
    )
    val offset by animateOffsetAsState(
        targetValue = targetOffset,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 1200f),
        label = "photoOffset",
    )
    val request = remember(file, viewport) {
        if (viewport.width <= 0 || viewport.height <= 0) {
            null
        } else {
            ImageRequest.Builder(context)
                .data(file)
                .size(viewport.width, viewport.height)
                .build()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(file.path) {
                detectTapGestures(
                    onTap = { currentOnToggleChrome() },
                    onDoubleTap = {
                        if (targetScale > 1.01f) {
                            targetScale = 1f
                        } else {
                            targetScale = 2.5f
                        }
                        targetOffset = Offset.Zero
                    },
                )
            }
            .pointerInput(file.path) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            targetScale = (targetScale * zoom).coerceIn(1f, 5f)
                            if (targetScale <= 1.01f) {
                                targetOffset = Offset.Zero
                            } else {
                                targetOffset += pan
                            }
                            event.changes.forEach { it.consume() }
                        } else if (targetScale > 1.01f && pressed == 1) {
                            targetOffset += event.calculatePan()
                            event.changes.forEach { it.consume() }
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        request?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}
