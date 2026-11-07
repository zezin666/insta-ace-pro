package com.acegallery.camera

interface CameraTransport {
    fun start() {}
    fun isAlive(): Boolean = true
    fun request(command: Int, payload: ByteArray, timeoutMs: Long = 5_000): ByteArray
    fun close()
}
