package com.acegallery.camera

import com.acegallery.proto.CameraProto
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class FakeCameraServer(
    private val responseCode: Int = 200,
    private val respond: Boolean = true,
) : AutoCloseable {

    private val server = ServerSocket(0)
    private var accepted: Socket? = null
    private var worker: Thread? = null
    private var requestArrived: CountDownLatch? = null
    @Volatile private var responseGate: CountDownLatch? = null

    fun socket(): Socket {
        val client = Socket("127.0.0.1", server.localPort)
        val serverSide = server.accept()
        accepted = serverSide
        requestArrived = CountDownLatch(1)
        worker = thread(isDaemon = true, name = "fake-camera") { serve(serverSide) }
        return client
    }

    /** Holds every response until [releaseResponses], so tests can interleave notifications. */
    fun holdResponses() {
        responseGate = CountDownLatch(1)
    }

    fun awaitRequestReceived(timeoutMs: Long = 5_000) {
        requestArrived?.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun releaseResponses() {
        responseGate?.countDown()
    }

    fun sendNotification(code: Int, body: ByteArray) = sendFrame(code, 0, body)

    private fun sendFrame(code: Int, seq: Int, body: ByteArray) {
        val socket = accepted ?: error("no client")
        synchronized(socket) {
            socket.getOutputStream().write(
                CameraFrame.encode(MessageCodec.encodeHeader(code, seq) + body),
            )
            socket.getOutputStream().flush()
        }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        try {
            while (true) {
                val payload = CameraFrame.read(input) ?: break
                if (payload.size < 12 || payload[0] != 0x04.toByte()) continue
                val header = MessageCodec.parseHeader(payload)
                requestArrived?.countDown()
                responseGate?.await(5, TimeUnit.SECONDS)
                if (!respond) continue
                val body = if (header.command == CameraCommands.GET_FILE_LIST) {
                    CameraProto.GetFileListResp.newBuilder()
                        .addUri("/DCIM/a.mp4")
                        .setTotalCount(1)
                        .build()
                        .toByteArray()
                } else {
                    byteArrayOf()
                }
                val response = MessageCodec.encodeHeader(responseCode, header.seq) + body
                output.write(CameraFrame.encode(response))
                output.flush()
            }
        } catch (_: Exception) {
            // Client closed; the fake server is done.
        }
    }

    override fun close() {
        runCatching { accepted?.close() }
        runCatching { server.close() }
        worker?.join(1_000)
    }
}
