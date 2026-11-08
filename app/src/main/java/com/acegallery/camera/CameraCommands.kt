package com.acegallery.camera

object CameraCommands {
    const val START_LIVE_STREAM = 1
    const val STOP_LIVE_STREAM = 2
    const val TAKE_PICTURE = 3
    const val START_CAPTURE = 4
    const val STOP_CAPTURE = 5
    const val GET_FILE_EXTRA = 11
    const val DELETE_FILES = 12
    const val GET_FILE_LIST = 13
    const val SET_SYNC_CAPTURE_MODE = 54
    const val GET_SYNC_CAPTURE_MODE = 55
    const val RESPONSE_OK = 200

    const val NOTIFICATION_SYNC_CAPTURE_MODE_UPDATE = 8211
    const val NOTIFICATION_CAM_SUBMODE_CHANGE = 8275
    const val NOTIFICATION_UPDATE_LIVE_STREAM_PARAMS = 8234
}
