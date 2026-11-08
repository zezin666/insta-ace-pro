package com.acegallery.ui.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamWatchdogTest {
    @Test
    fun `restarts after one second without packets`() {
        assertTrue(
            shouldRestartLive(
                streaming = true,
                nowMs = 2_000,
                lastPacketMs = 1_000,
                alreadyRestarted = false,
            ),
        )
    }

    @Test
    fun `does not restart before one second`() {
        assertFalse(
            shouldRestartLive(
                streaming = true,
                nowMs = 1_999,
                lastPacketMs = 1_000,
                alreadyRestarted = false,
            ),
        )
    }

    @Test
    fun `does not restart twice`() {
        assertFalse(
            shouldRestartLive(
                streaming = true,
                nowMs = 5_000,
                lastPacketMs = 1_000,
                alreadyRestarted = true,
            ),
        )
    }

    @Test
    fun `does not restart when not streaming`() {
        assertFalse(
            shouldRestartLive(
                streaming = false,
                nowMs = 5_000,
                lastPacketMs = 1_000,
                alreadyRestarted = false,
            ),
        )
    }
}
