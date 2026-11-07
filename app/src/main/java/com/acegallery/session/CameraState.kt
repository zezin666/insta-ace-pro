package com.acegallery.session

sealed interface CameraState {
    data object Disconnected : CameraState
    data object Connecting : CameraState
    data class Connected(val host: String) : CameraState
    data class Error(val message: String) : CameraState
}
