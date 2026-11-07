package com.acegallery.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.acegallery.model.CameraFile
import java.io.File

class AndroidMediaStoreWriter(private val context: Context) : MediaStoreWriter {

    private val resolver = context.contentResolver

    override fun createPending(file: CameraFile): Uri {
        val target = MediaStoreTargets.forFile(file)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, target.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collectionUri(target.collection), values)
            ?: error("cannot insert ${file.name}")
    }

    override fun copyInto(uri: Uri, source: File) {
        val output = resolver.openOutputStream(uri) ?: error("cannot open $uri")
        output.use { out ->
            source.inputStream().use { input ->
                input.copyTo(out, 8 * 1024)
            }
        }
    }

    override fun publish(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        resolver.update(uri, values, null, null)
    }

    override fun delete(uri: Uri) {
        resolver.delete(uri, null, null)
    }

    override fun findDownloaded(file: CameraFile): Uri? {
        val target = MediaStoreTargets.forFile(file)
        val collection = collectionUri(target.collection)
        val selection = buildString {
            append("${MediaStore.MediaColumns.DISPLAY_NAME}=?")
            append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
            if (file.sizeBytes != null) append(" AND ${MediaStore.MediaColumns.SIZE}=?")
        }
        val arguments = buildList {
            add(file.name)
            add("${target.relativePath}%")
            if (file.sizeBytes != null) add(file.sizeBytes.toString())
        }.toTypedArray()

        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arguments,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        }
        return null
    }

    private fun collectionUri(collection: MediaStoreCollection): Uri = when (collection) {
        MediaStoreCollection.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaStoreCollection.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaStoreCollection.DOWNLOAD -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }
}
