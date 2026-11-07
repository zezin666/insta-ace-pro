package com.acegallery.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraNetworkSelectorTest {

    @Test
    fun `selects first wifi that reaches camera`() {
        val candidates = listOf(
            NetworkCandidate(isWifi = true, reachesCamera = false),
            NetworkCandidate(isWifi = false, reachesCamera = true),
            NetworkCandidate(isWifi = true, reachesCamera = true),
        )
        assertEquals(2, CameraNetworkSelector.select(candidates))
    }

    @Test
    fun `returns null when nothing reaches camera`() {
        assertNull(CameraNetworkSelector.select(listOf(NetworkCandidate(true, false))))
    }
}
