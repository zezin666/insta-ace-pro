package com.acegallery.camera

import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class CameraProtocolTimeoutException(message: String) : IOException(message)

class CameraProtocolClient(
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val keepAliveMs: Long = 2_000,
) : CameraTransport {

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
    private val nextSeq = AtomicInteger(0)
    private val alive = AtomicBoolean(true)
    private val sendLock = Any()
    private var readerJob: Job? = null
    private var keepAliveJob: Job? = null
    private val _livePackets = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val livePackets: Flow<ByteArray> = _livePackets.asSharedFlow()

    override fun start() {
        send(SYNC)
        send(KEEPALIVE)
        readerJob = scope.launch(Dispatchers.IO) { readLoop() }
        keepAliveJob = scope.launch(Dispatchers.IO) {
            while (isActive && alive.get()) {
                delay(keepAliveMs)
                if (!alive.get()) break
                try {
                    send(KEEPALIVE)
                } catch (_: IOException) {
                    markDead()
                    break
                }
            }
        }
    }

    override fun isAlive(): Boolean = alive.get()

    override fun request(
        command: Int,
        payload: ByteArray,
        timeoutMs: Long,
        fatalOnTimeout: Boolean,
    ): ByteArray {
        if (!alive.get()) throw IOException("camera connection is closed")
        val sequence = nextSeq.getAndUpdate { (it + 1) and 0xFFFFFF }
        val deferred = CompletableDeferred<ByteArray>()
        pending[sequence] = deferred
        try {
            try {
                send(MessageCodec.encodeHeader(command, sequence) + payload)
            } catch (io: IOException) {
                markDead()
                throw io
            }
            return runBlocking {
                withTimeout(timeoutMs) { deferred.await() }
            }
        } catch (timeout: TimeoutCancellationException) {
            markDead()
            throw CameraProtocolTimeoutException("no response to command $command")
        } finally {
            pending.remove(sequence)
        }
    }

    private fun readLoop() {
        try {
            while (alive.get()) {
                val frame = CameraFrame.read(input) ?: break
                when {
                    frame.isEmpty() -> Unit
                    frame[0] == 0x01.toByte() -> {
                        if (frame.size > 12 && frame[3] == 0x20.toByte()) {
                            _livePackets.tryEmit(frame.copyOfRange(12, frame.size))
                        }
                    }
                    frame[0] == 0x04.toByte() -> handleMessage(frame)
                }
            }
        } catch (_: IOException) {
            // Connection dropped; the finally block marks the transport dead.
        } finally {
            alive.set(false)
            failAll(IOException("camera connection closed"))
        }
    }

    private fun handleMessage(frame: ByteArray) {
        if (frame.size < 12) return
        val header = MessageCodec.parseHeader(frame)
        val deferred = pending.remove(header.seq) ?: return
        if (header.responseCode != CameraCommands.RESPONSE_OK) {
            deferred.completeExceptionally(CameraProtocolException(header.responseCode))
        } else {
            deferred.complete(frame.copyOfRange(12, frame.size))
        }
    }

    private fun send(payload: ByteArray) {
        synchronized(sendLock) {
            output.write(CameraFrame.encode(payload))
            output.flush()
        }
    }

    private fun failAll(error: Throwable) {
        val snapshot = pending.values.toList()
        pending.clear()
        snapshot.forEach { it.completeExceptionally(error) }
    }

    override fun close() {
        alive.set(false)
        readerJob?.cancel()
        keepAliveJob?.cancel()
        runCatching { socket.close() }
        failAll(IOException("camera connection closed"))
    }

    private fun markDead() {
        alive.set(false)
        runCatching { socket.close() }
    }

    private companion object {
        val SYNC = byteArrayOf(0x06, 0x00, 0x00) + "syNceNdinS".toByteArray()
        val KEEPALIVE = byteArrayOf(0x05, 0x00, 0x00)
    }
}
