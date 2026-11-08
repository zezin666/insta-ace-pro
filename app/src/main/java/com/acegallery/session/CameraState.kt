package com.acegallery.session

sealed interface CameraState {
    data object Disconnected : CameraState
    data object Connecting : CameraState
    data class Connected(val host: String) : CameraState
    data class Error(val message: String) : CameraState
}

/** The camera's capture mode as exposed to the UI (normal photo / normal video only). */
enum class CaptureMode { UNKNOWN, PHOTO, VIDEO }

/** Actual live stream size, preferring the camera's visible crop over the encoded size. */
data class LiveStreamParams(val width: Int, val height: Int, val fps: Int)

