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

/**
 * Portrait keeps the preview to 55% of the space above the controls; landscape uses
 * all of it, otherwise the available height shrinks the preview to a thumbnail.
 */
fun previewHeightBudget(maxWidth: Float, maxHeight: Float): Float =
    if (maxWidth > maxHeight) maxHeight else maxHeight * PORTRAIT_HEIGHT_FRACTION

private const val PORTRAIT_HEIGHT_FRACTION = 0.55f
