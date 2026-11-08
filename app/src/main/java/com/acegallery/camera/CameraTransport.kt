package com.acegallery.camera

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class CameraNotification(val code: Int, val body: ByteArray)

interface CameraTransport {
    fun start() {}
    fun isAlive(): Boolean = true

    /** Raw video packets from the live preview stream (12-byte stream header already removed). */
    val livePackets: Flow<ByteArray> get() = emptyFlow()

    /** Unsolicited camera messages (codes >= 8000) that carry no pending request sequence. */
    val notifications: Flow<CameraNotification> get() = emptyFlow()

    fun request(
        command: Int,
        payload: ByteArray,
        timeoutMs: Long = 5_000,
        fatalOnTimeout: Boolean = true,
    ): ByteArray

    fun close()
}
