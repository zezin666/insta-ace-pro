package com.acegallery.ui.gallery

import com.acegallery.model.CameraFile
import com.acegallery.model.CameraFileKind
import java.time.LocalDate
import java.time.ZoneId

enum class FileFilter { ALL, PHOTOS, VIDEOS }

data class GallerySection(
    val date: LocalDate?,
    val files: List<CameraFile>,
)

object GalleryGrouping {

    fun group(
        files: List<CameraFile>,
        filter: FileFilter,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<GallerySection> {
        val filtered = files.filter { file ->
            when (filter) {
                FileFilter.ALL -> true
                FileFilter.PHOTOS -> file.kind == CameraFileKind.PHOTO
                FileFilter.VIDEOS -> file.kind == CameraFileKind.VIDEO
            }
        }.sortedWith(
            compareByDescending<CameraFile> { it.capturedAt }.thenByDescending { it.name },
        )

        val groups = filtered.groupBy { file ->
            file.capturedAt?.atZone(zone)?.toLocalDate()
        }
        val dated = groups.entries
            .filter { it.key != null }
            .sortedByDescending { it.key }
            .map { GallerySection(it.key, it.value) }
        val undated = groups[null]?.let { listOf(GallerySection(null, it)) } ?: emptyList()
        return dated + undated
    }
}
