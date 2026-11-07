package com.acegallery.network

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import okhttp3.OkHttpClient
import okhttp3.Request

class HttpCameraFileSource(
    private val baseUrl: String,
    private val client: OkHttpClient,
) : CameraFileSource {

    override fun size(path: String): Long? {
        val request = Request.Builder().url(url(path)).head().build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    response.header("Content-Length")?.toLongOrNull()
                }
            }
        }.getOrNull()
    }

    override fun download(
        path: String,
        dest: File,
        offset: Long,
        onProgress: (Long) -> Unit,
    ): Long {
        val builder = Request.Builder().url(url(path))
        if (offset > 0) builder.header("Range", "bytes=$offset-")

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 416) {
                val total = totalFromContentRange(response.header("Content-Range"))
                if (total != null && dest.length() >= total) return dest.length()
                throw IOException("range not satisfiable for ${url(path)}")
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${url(path)}")
            }

            val append = response.code == 206 && offset > 0
            if (offset > 0 && !append) {
                // The server ignored Range; start this file again from zero.
                dest.delete()
            }

            val body = response.body ?: throw IOException("empty body for ${url(path)}")
            var written = if (append) dest.length() else 0L
            var lastReported = written
            var lastReportedAt = System.currentTimeMillis()
            FileOutputStream(dest, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (written - lastReported >= PROGRESS_BYTES ||
                            now - lastReportedAt >= PROGRESS_MILLIS
                        ) {
                            onProgress(written)
                            lastReported = written
                            lastReportedAt = now
                        }
                    }
                }
            }
            onProgress(written)
            return written
        }
    }

    override fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val request = Request.Builder()
            .url(url(path))
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 200 && offset == 0L) {
                val body = response.body ?: return ByteArray(0)
                return readAtMost(body.byteStream(), length)
            }
            if (response.code != 206) {
                throw IOException("HTTP ${response.code} for ${url(path)}")
            }
            val body = response.body ?: return ByteArray(0)
            return readAtMost(body.byteStream(), length)
        }
    }

    private fun readAtMost(input: InputStream, length: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun url(path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private fun totalFromContentRange(value: String?): Long? =
        value?.substringAfterLast('/')?.toLongOrNull()

    private companion object {
        const val PROGRESS_BYTES = 512 * 1024
        const val PROGRESS_MILLIS = 500L
    }
}
