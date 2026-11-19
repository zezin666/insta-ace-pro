package com.acegallery.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SampleSizeLadderTest {

    @Test
    fun `sample size ladder is 1 to 2 to 4 then exhausted`() {
        val sizes = generateSequence(1) { nextSampleSize(it) }.toList()

        assertEquals(listOf(1, 2, 4), sizes)
        assertNull(nextSampleSize(4))
    }

    @Test
    fun `unexpected sample size ends the ladder`() {
        assertNull(nextSampleSize(0))
        assertNull(nextSampleSize(8))
    }
}