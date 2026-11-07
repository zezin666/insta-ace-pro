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

    override fun createPending(file: CameraFile): PendingMedia {
        val target = MediaStoreTargets.forFile(file)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, target.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collectionUri(target.collection), values)
            ?: error("cannot insert ${file.name}")
        return PendingMedia(ContentUris.parseId(uri), target.collection)
    }

    override fun copyInto(pending: PendingMedia, source: File) {
        val output = resolver.openOutputStream(uriOf(pending))
            ?: error("cannot open ${uriOf(pending)}")
        output.use { out ->
            source.inputStream().use { input ->
                input.copyTo(out, 8 * 1024)
            }
        }
    }

    override fun publish(pending: PendingMedia) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        resolver.update(uriOf(pending), values, null, null)
    }

    override fun delete(pending: PendingMedia) {
        resolver.delete(uriOf(pending), null, null)
    }

    override fun isDownloaded(file: CameraFile): Boolean {
        val target = MediaStoreTargets.forFile(file)
        val selection = buildString {
            append("${MediaStore.MediaColumns.DISPLAY_NAME}=?")
            append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
            append(" AND ${MediaStore.MediaColumns.IS_PENDING}=0")
            if (file.sizeBytes != null) append(" AND ${MediaStore.MediaColumns.SIZE}=?")
        }
        val arguments = buildList {
            add(file.name)
            add("${target.relativePath}%")
            if (file.sizeBytes != null) add(file.sizeBytes.toString())
        }.toTypedArray()

        return resolver.query(
            collectionUri(target.collection),
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arguments,
            null,
        )?.use { cursor -> cursor.moveToFirst() } ?: false
    }

    private fun uriOf(pending: PendingMedia): Uri =
        ContentUris.withAppendedId(collectionUri(pending.collection), pending.rowId)

    private fun collectionUri(collection: MediaStoreCollection): Uri = when (collection) {
        MediaStoreCollection.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaStoreCollection.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaStoreCollection.DOWNLOAD -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }
}
