package com.acegallery.session

import com.acegallery.camera.CameraCommands
import com.acegallery.camera.CameraTransport
import com.acegallery.model.CameraFile
import com.acegallery.network.CameraFileSource
import com.acegallery.network.CameraFileSourceProvider
import com.acegallery.network.CameraNetworkFinder
import com.acegallery.network.CameraNetworkHandle
import com.acegallery.network.HttpCameraFileSource
import com.acegallery.proto.CameraProto
import com.acegallery.repository.CameraFileRepository
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

interface CameraGateway : CameraFileSourceProvider {
    val state: StateFlow<CameraState>
    val livePackets: Flow<ByteArray> get() = emptyFlow()
    suspend fun connect(host: String = DEFAULT_HOST)
    fun disconnect()
    suspend fun listFiles(): List<CameraFile>
    fun repository(): CameraFileRepository
    fun transport(): CameraTransport
    fun httpClient(): OkHttpClient
    suspend fun startLiveStream() = Unit
    suspend fun stopLiveStream() = Unit
    suspend fun takePicture() = Unit
    suspend fun startRecording() = Unit
    suspend fun stopRecording() = Unit

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
    private val scope: CoroutineScope,
) : CameraGateway, CameraFileSourceProvider {

    private val mutex = Mutex()
    private val _state = MutableStateFlow<CameraState>(CameraState.Disconnected)
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _livePackets = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val livePackets: Flow<ByteArray> = _livePackets.asSharedFlow()
    private var liveCollector: Job? = null

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
                withContext(Dispatchers.IO) {
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

                    liveCollector?.cancel()
                    liveCollector = scope.launch {
                        newTransport.livePackets.collect { packet ->
                            _livePackets.emit(packet)
                        }
                    }
                }
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

    override suspend fun startLiveStream() {
        val request = CameraProto.StartLiveStream.newBuilder()
            .setEnableVideo(true)
            .setEnableAudio(false)
            .setAudioSampleRate(48_000)
            .setEnableGyro(false)
            .setResolution(CameraProto.VideoResolution.RES_1920_1080P30)
            .setResolution1(CameraProto.VideoResolution.RES_424_240P15)
            .build()
        transport().request(
            CameraCommands.START_LIVE_STREAM,
            request.toByteArray(),
            timeoutMs = 8_000,
        )
    }

    override suspend fun stopLiveStream() {
        runCatching {
            transport().request(CameraCommands.STOP_LIVE_STREAM, ByteArray(0), timeoutMs = 5_000)
        }
    }

    override suspend fun takePicture() {
        val request = CameraProto.TakePicture.newBuilder()
            .setMode(CameraProto.TakePictureMode.TAKE_PICTURE_NORMAL)
            .build()
        transport().request(
            CameraCommands.TAKE_PICTURE,
            request.toByteArray(),
            timeoutMs = 10_000,
        )
    }

    override suspend fun startRecording() {
        val request = CameraProto.StartCapture.newBuilder()
            .setMode(CameraProto.CaptureMode.CAPTURE_MODE_NORMAL)
            .build()
        transport().request(
            CameraCommands.START_CAPTURE,
            request.toByteArray(),
            timeoutMs = 8_000,
        )
    }

    override suspend fun stopRecording() {
        transport().request(CameraCommands.STOP_CAPTURE, ByteArray(0), timeoutMs = 15_000)
    }

    override suspend fun get(): CameraFileSource {
        if (transport?.isAlive() != true) connect(lastHost)
        return fileSource ?: throw IllegalStateException(
            (_state.value as? CameraState.Error)?.message ?: "未连接相机",
        )
    }

    private fun disconnectInternal() {
        liveCollector?.cancel()
        liveCollector = null
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
