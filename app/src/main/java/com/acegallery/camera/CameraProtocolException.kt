package com.acegallery.camera

import java.io.IOException

class CameraProtocolException(
    val responseCode: Int,
    message: String = "camera responded with $responseCode",
) : IOException(message)
