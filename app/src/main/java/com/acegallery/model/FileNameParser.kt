package com.acegallery.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object FileNameParser {

    private val NAME = Regex(
        "^(VID|IMG|LRV|THM|PRO_VID|PRO_LRV|PRO_IMG|LIV)_(\\d{8})_(\\d{6})(?:_(\\d{2}))?_(\\d{3})",
        RegexOption.IGNORE_CASE,
    )
    private val CAMERA_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val VIDEO_EXT = setOf("mp4", "mov", "insv", "insp")
    private val PHOTO_EXT = setOf("jpg", "jpeg", "dng")
    private val HIDDEN_EXT = setOf("lrv", "thm", "list", "log", "txt")

    data class Parsed(
        val kind: CameraFileKind,
        val capturedAt: Instant?,
        val hidden: Boolean,
    )

    fun parse(name: String, zone: ZoneId = ZoneId.systemDefault()): Parsed {
        val extension = name.substringAfterLast('.', "").lowercase()
        val hidden = name.startsWith(".") || extension in HIDDEN_EXT
        if (hidden) return Parsed(CameraFileKind.OTHER, null, true)

        val match = NAME.find(name)
        val prefix = match?.groupValues?.get(1)?.uppercase()
        val kind = when {
            prefix == "VID" || prefix == "PRO_VID" || prefix == "LIV" -> CameraFileKind.VIDEO
            prefix == "IMG" || prefix == "PRO_IMG" -> CameraFileKind.PHOTO
            extension in VIDEO_EXT -> CameraFileKind.VIDEO
            extension in PHOTO_EXT -> CameraFileKind.PHOTO
            else -> CameraFileKind.OTHER
        }

        val capturedAt = match?.let {
            runCatching {
                LocalDateTime.parse(it.groupValues[2] + it.groupValues[3], CAMERA_TIME)
                    .atZone(zone)
                    .toInstant()
            }.getOrNull()
        }

        return Parsed(kind, capturedAt, hidden = false)
    }
}
