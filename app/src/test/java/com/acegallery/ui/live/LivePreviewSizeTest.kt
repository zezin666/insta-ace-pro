package com.acegallery.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePreviewSizeTest {
    @Test
    fun `wide ratio fills width`() {
        val size = previewSize(1000f, 600f, 16f / 9f)
        assertEquals(1000f, size.width, 0.01f)
        assertEquals(562.5f, size.height, 0.01f)
    }

    @Test
    fun `tall ratio is limited by height`() {
        val size = previewSize(1000f, 600f, 4f / 3f)
        assertEquals(800f, size.width, 0.01f)
        assertEquals(600f, size.height, 0.01f)
    }

    @Test
    fun `size never exceeds the available box`() {
        val size = previewSize(1000f, 600f, 9f / 16f)
        assertTrue(size.width <= 1000f && size.height <= 600f)
    }

    @Test
    fun `portrait budget keeps 55 percent of the height`() {
        assertEquals(330f, previewHeightBudget(400f, 600f), 0.01f)
    }

    @Test
    fun `landscape budget uses the full height above the controls`() {
        assertEquals(600f, previewHeightBudget(1000f, 600f), 0.01f)
    }
}
