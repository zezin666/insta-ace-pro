package com.acegallery.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatermarkLayoutTest {

    private val measure = object : TextMeasure {
        override fun mainWidth(text: String, textSize: Float) = text.length * textSize * 0.55f
        override fun smallWidth(text: String, textSize: Float) = text.length * textSize * 0.52f
        override fun smallCharWidth(char: Char, textSize: Float) = textSize * 0.60f
    }

    @Test
    fun `bottom center is horizontally centered`() {
        val layout = WatermarkLayout.compute(4032, 3024, WatermarkPosition.BOTTOM_CENTER, measure)
        assertEquals(2016f, (layout.overallLeft + layout.overallRight) / 2f, 1f)
    }

    @Test
    fun `all presets stay inside the image`() {
        val positions = WatermarkPosition.entries
        for (position in positions) {
            val layout = WatermarkLayout.compute(4032, 3024, position, measure)
            assertTrue(layout.overallLeft >= 0f)
            assertTrue(layout.overallTop >= 0f)
            assertTrue(layout.overallRight <= 4032f)
            assertTrue(layout.overallBottom <= 3024f)
        }
    }

    @Test
    fun `logo is longer than the rounded rectangle`() {
        val layout = WatermarkLayout.compute(4032, 3024, WatermarkPosition.BOTTOM_CENTER, measure)
        assertTrue(layout.logo.height > layout.group.height)
    }

    @Test
    fun `logo is separated from the group`() {
        val layout = WatermarkLayout.compute(4032, 3024, WatermarkPosition.BOTTOM_CENTER, measure)
        assertTrue(layout.logo.left >= layout.group.right + 20f)
    }

    @Test
    fun `small text keeps one character gap from the logo`() {
        val layout = WatermarkLayout.compute(4032, 3024, WatermarkPosition.BOTTOM_CENTER, measure)
        val textWidth = measure.smallWidth("CO-ENGINEERED WITH", layout.smallTextSize) +
            layout.letterSpacing * ("CO-ENGINEERED WITH".length - 1)
        assertEquals(
            layout.logo.left,
            layout.smallTextLeft + textWidth + measure.smallCharWidth('C', layout.smallTextSize),
            1f,
        )
    }

    @Test
    fun `portrait image stays inside`() {
        val layout = WatermarkLayout.compute(3024, 4032, WatermarkPosition.BOTTOM_CENTER, measure)
        assertTrue(layout.overallLeft >= 0f && layout.overallRight <= 3024f)
        assertTrue(layout.overallTop >= 0f && layout.overallBottom <= 4032f)
    }
}
