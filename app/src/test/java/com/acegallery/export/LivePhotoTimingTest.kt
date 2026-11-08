package com.acegallery.export

import org.junit.Assert.assertEquals
import org.junit.Test

class LivePhotoTimingTest {
    @Test
    fun `long video uses a three second window`() {
        assertEquals(LiveClip(4_000, 3_000), LivePhotoTiming.clip(10_000, 4_000))
    }

    @Test
    fun `start beyond the last window clamps`() {
        assertEquals(LiveClip(7_000, 3_000), LivePhotoTiming.clip(10_000, 9_000))
    }

    @Test
    fun `short video exports its full length`() {
        assertEquals(LiveClip(0, 2_000), LivePhotoTiming.clip(2_000, 0))
    }

    @Test
    fun `cover is clamped into the clip`() {
        val clip = LiveClip(4_000, 3_000)
        assertEquals(4_000, LivePhotoTiming.clampCover(clip, 1_000))
        assertEquals(7_000, LivePhotoTiming.clampCover(clip, 9_000))
        assertEquals(5_500, LivePhotoTiming.clampCover(clip, 5_500))
    }

    @Test
    fun `default cover is the middle of the clip`() {
        assertEquals(5_500, LivePhotoTiming.defaultCover(LiveClip(4_000, 3_000)))
        assertEquals(1_000, LivePhotoTiming.defaultCover(LiveClip(0, 2_000)))
    }
}
