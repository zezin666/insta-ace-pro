package com.acegallery.storage

import android.net.Uri
import com.acegallery.model.CameraFile
import java.io.File

interface MediaStoreWriter {
    fun createPending(file: CameraFile): Uri
    fun copyInto(uri: Uri, source: File)
    fun publish(uri: Uri)
    fun delete(uri: Uri)
    fun findDownloaded(file: CameraFile): Uri?
}
