package com.acegallery.export

/** The fixed-width video window used as the motion part of an exported live photo. */
data class LiveClip(val startMs: Long, val durationMs: Long) {
    val endMs: Long get() = startMs + durationMs
}

/** Pure clip/cover math shared by the exporter and the export editor UI. */
object LivePhotoTiming {
    const val MAX_CLIP_MS = 3_000L

    /** Clamps the start into the video and trims the clip to what is left. */
    fun clip(videoDurationMs: Long, startMs: Long): LiveClip {
        val duration = videoDurationMs.coerceAtLeast(0L)
        val maxStart = (duration - MAX_CLIP_MS).coerceAtLeast(0L)
        val start = startMs.coerceIn(0L, maxStart)
        return LiveClip(start, minOf(MAX_CLIP_MS, duration - start).coerceAtLeast(0L))
    }

    /** Keeps the cover inside the clip; an empty clip has no frame but reports its start. */
    fun clampCover(clip: LiveClip, coverMs: Long): Long =
        if (clip.durationMs <= 0L) clip.startMs else coverMs.coerceIn(clip.startMs, clip.endMs)

    /** The midpoint of the clip, clamped to its end. */
    fun defaultCover(clip: LiveClip): Long =
        (clip.startMs + clip.durationMs / 2).coerceAtMost(clip.endMs)
}
