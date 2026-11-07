package com.acegallery.repository

import com.acegallery.camera.CameraCommands
import com.acegallery.camera.CameraTransport
import com.acegallery.model.CameraFile
import com.acegallery.model.CameraPath
import com.acegallery.model.FileNameParser
import com.acegallery.proto.CameraProto
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CameraFileRepository(
    private val transport: CameraTransport,
    private val pageSize: Int = 100,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun listFiles(): List<CameraFile> = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<String, CameraFile>()
        var start = 0
        repeat(MAX_PAGES) {
            val request = CameraProto.GetFileList.newBuilder()
                .setMediaType(CameraProto.MediaType.VIDEO_AND_PHOTO)
                .setStart(start)
                .setLimit(pageSize)
                .build()
            val body = transport.request(CameraCommands.GET_FILE_LIST, request.toByteArray())
            val response = CameraProto.GetFileListResp.parseFrom(body)
            if (response.uriCount == 0) return@withContext files.values.toList()

            for (uri in response.uriList) {
                val path = CameraPath.pathOf(uri)
                val name = CameraPath.fileName(path)
                val parsed = FileNameParser.parse(name, zone)
                if (parsed.hidden) continue
                files.putIfAbsent(
                    path,
                    CameraFile(
                        uri = uri,
                        path = path,
                        name = name,
                        kind = parsed.kind,
                        capturedAt = parsed.capturedAt,
                    ),
                )
            }

            start += response.uriCount
        }
        files.values.toList()
    }

    private companion object {
        const val MAX_PAGES = 100
    }
}
