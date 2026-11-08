package com.acegallery.ui.live

import kotlin.math.min

/** Height of the live preview area when the ratio does not need the full width. */
data class PreviewSize(val width: Float, val height: Float)

/**
 * Fits [aspectRatio] inside a [maxWidth] x [maxHeight] box without stretching.
 * A non-positive ratio falls back to 16:9.
 */
fun previewSize(maxWidth: Float, maxHeight: Float, aspectRatio: Float): PreviewSize {
    val ratio = if (aspectRatio > 0f) aspectRatio else 16f / 9f
    val height = min(maxHeight, maxWidth / ratio)
    return PreviewSize(width = height * ratio, height = height)
}
