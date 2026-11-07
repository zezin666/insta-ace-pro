package com.acegallery.session

import com.acegallery.camera.CameraTransport
import com.acegallery.model.CameraFile
import com.acegallery.network.CameraFileSource
import com.acegallery.network.CameraFileSourceProvider
import com.acegallery.network.CameraNetworkFinder
import com.acegallery.network.CameraNetworkHandle
import com.acegallery.network.HttpCameraFileSource
import com.acegallery.repository.CameraFileRepository
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

interface CameraGateway : CameraFileSourceProvider {
    val state: StateFlow<CameraState>
    suspend fun connect(host: String = DEFAULT_HOST)
    fun disconnect()
    suspend fun listFiles(): List<CameraFile>
    fun repository(): CameraFileRepository
    fun transport(): CameraTransport
    fun httpClient(): OkHttpClient

    companion object {
        const val DEFAULT_HOST = "192.168.42.1"
    }
}

fun interface CameraTransportFactory {
    fun create(socket: Socket): CameraTransport
}

fun interface CameraSocketConnector {
    fun connect(handle: CameraNetworkHandle, host: String, port: Int): Socket
}

fun interface CameraHttpClientProvider {
    fun create(handle: CameraNetworkHandle): OkHttpClient
}

@Singleton
class CameraSession @Inject constructor(
    private val networkFinder: CameraNetworkFinder,
    private val transportFactory: CameraTransportFactory,
    private val socketConnector: CameraSocketConnector,
    private val httpClientFactory: CameraHttpClientProvider,
) : CameraGateway, CameraFileSourceProvider {

    private val mutex = Mutex()
    private val _state = MutableStateFlow<CameraState>(CameraState.Disconnected)
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private var transport: CameraTransport? = null
    private var repository: CameraFileRepository? = null
    private var client: OkHttpClient? = null
    private var fileSource: HttpCameraFileSource? = null
    private var lastHost = CameraGateway.DEFAULT_HOST

    override suspend fun connect(host: String) {
        mutex.withLock {
            if (_state.value is CameraState.Connected && transport?.isAlive() == true) return
            _state.value = CameraState.Connecting
            lastHost = host
            try {
                val handle = networkFinder.find(host, PORT)
                    ?: error(NOT_CONNECTED_MESSAGE)
                val socket = socketConnector.connect(handle, host, PORT)
                val newTransport = transportFactory.create(socket).also { it.start() }
                val newClient = httpClientFactory.create(handle)

                disconnectInternal()
                transport = newTransport
                client = newClient
                repository = CameraFileRepository(newTransport)
                fileSource = HttpCameraFileSource("http://$host", newClient)
                _state.value = CameraState.Connected(host)
            } catch (exc: Exception) {
                disconnectInternal()
                _state.value = CameraState.Error(exc.message ?: "连接相机失败")
            }
        }
    }

    override fun disconnect() {
        disconnectInternal()
        _state.value = CameraState.Disconnected
    }

    override suspend fun listFiles(): List<CameraFile> = repository().listFiles()

    override fun repository(): CameraFileRepository =
        repository ?: throw IllegalStateException("未连接相机")

    override fun transport(): CameraTransport =
        transport ?: throw IllegalStateException("未连接相机")

    override fun httpClient(): OkHttpClient =
        client ?: throw IllegalStateException("未连接相机")

    override suspend fun get(): CameraFileSource {
        if (transport?.isAlive() != true) connect(lastHost)
        return fileSource ?: throw IllegalStateException(
            (_state.value as? CameraState.Error)?.message ?: "未连接相机",
        )
    }

    private fun disconnectInternal() {
        runCatching { transport?.close() }
        transport = null
        repository = null
        client = null
        fileSource = null
    }

    private companion object {
        const val PORT = 6666
        const val NOT_CONNECTED_MESSAGE =
            "未连接相机 WiFi。请先在系统设置里连接相机的 WiFi。"
    }
}
