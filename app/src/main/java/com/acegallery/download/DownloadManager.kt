package com.acegallery.download

import com.acegallery.model.CameraFile
import com.acegallery.network.CameraFileSourceProvider
import com.acegallery.storage.MediaStoreWriter
import com.acegallery.storage.PendingMedia
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun interface FreeSpaceChecker {
    fun freeBytes(): Long
}

interface DownloadQueue {
    val items: StateFlow<List<DownloadManager.Item>>
    fun enqueue(files: List<CameraFile>)
    fun cancelAll()
    suspend fun isDownloaded(file: CameraFile): Boolean
}

fun tempNameFor(path: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
    return digest.joinToString("") { "%02x".format(it) } + ".part"
}

class DownloadManager(
    private val sourceProvider: CameraFileSourceProvider,
    private val writer: MediaStoreWriter,
    private val tempDir: File,
    private val scope: CoroutineScope,
    private val freeSpace: FreeSpaceChecker,
    private val retryCount: Int = 3,
) : DownloadQueue {

    sealed interface State {
        data object Queued : State
        data class Downloading(val bytes: Long, val total: Long?) : State
        data object Done : State
        data class Failed(val reason: String) : State
    }

    data class Item(val file: CameraFile, val state: State)

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    override val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val queue = Channel<CameraFile>(Channel.UNLIMITED)
    private val lock = Any()
    private var worker: Job? = null

    override fun enqueue(files: List<CameraFile>) {
        synchronized(lock) {
            val current = _items.value.associateBy { it.file.path }.toMutableMap()
            for (file in files) {
                val existing = current[file.path]
                if (existing == null || existing.state is State.Failed) {
                    current[file.path] = Item(file, State.Queued)
                    queue.trySend(file)
                }
            }
            _items.value = current.values.toList()
        }
        ensureWorker()
    }

    private fun ensureWorker() {
        synchronized(lock) {
            if (worker?.isActive == true) return
            worker = scope.launch { work() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun work() {
        while (true) {
            val file = queue.tryReceive().getOrNull()
            if (file == null) {
                synchronized(lock) {
                    if (queue.isEmpty) {
                        worker = null
                        return
                    }
                }
                continue
            }
            process(file)
        }
    }

    private suspend fun process(file: CameraFile) {
        val temp = File(tempDir, tempNameFor(file.path))
        tempDir.mkdirs()
        var attempt = 0
        var pending: PendingMedia? = null

        while (true) {
            try {
                val source = sourceProvider.get()
                val total = source.size(file.path)
                if (writer.isDownloaded(file.copy(sizeBytes = total))) {
                    update(file, State.Done)
                    temp.delete()
                    return
                }
                if (total != null && freeSpace.freeBytes() < total) {
                    update(file, State.Failed("存储空间不足"))
                    return
                }

                val offset = if (temp.exists()) temp.length() else 0L
                update(file, State.Downloading(offset, total))
                source.download(file.path, temp, offset) { written ->
                    update(file, State.Downloading(written, total))
                }

                val created = writer.createPending(file.copy(sizeBytes = total))
                pending = created
                writer.copyInto(created, temp)
                writer.publish(created)
                pending = null
                temp.delete()
                update(file, State.Done)
                return
            } catch (exc: Exception) {
                pending?.let {
                    runCatching { writer.delete(it) }
                    pending = null
                }
                attempt++
                if (attempt >= retryCount) {
                    update(file, State.Failed(reasonFor(exc)))
                    return
                }
                delay(1_000L shl (attempt - 1))
            }
        }
    }

    override suspend fun isDownloaded(file: CameraFile): Boolean = writer.isDownloaded(file)

    override fun cancelAll() {
        while (queue.tryReceive().isSuccess) Unit
        synchronized(lock) {
            _items.value = _items.value.map { item ->
                if (item.state is State.Queued || item.state is State.Downloading) {
                    item.copy(state = State.Failed("已取消"))
                } else {
                    item
                }
            }
        }
    }

    private fun update(file: CameraFile, state: State) {
        synchronized(lock) {
            _items.value = _items.value.map { item ->
                if (item.file.path == file.path) item.copy(state = state) else item
            }
        }
    }

    private fun reasonFor(exc: Exception): String {
        val message = exc.message.orEmpty()
        return when {
            message.contains("存储") -> "存储空间不足"
            message.contains("HTTP ") -> "相机返回错误（HTTP ${message.substringAfter("HTTP ").takeWhile { it.isDigit() }}）"
            exc is IOException -> "相机连接断开"
            message.isNotBlank() -> message
            else -> "下载失败"
        }
    }
}
