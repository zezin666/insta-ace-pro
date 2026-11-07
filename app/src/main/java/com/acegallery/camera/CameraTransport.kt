package com.acegallery.camera

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface CameraTransport {
    fun start() {}
    fun isAlive(): Boolean = true

    /** Raw video packets from the live preview stream (12-byte stream header already removed). */
    val livePackets: Flow<ByteArray> get() = emptyFlow()

    fun request(command: Int, payload: ByteArray, timeoutMs: Long = 5_000): ByteArray
    fun close()
}
