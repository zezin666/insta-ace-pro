package com.acegallery.network

import android.net.Network
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

data class NetworkCandidate(
    val isWifi: Boolean,
    val reachesCamera: Boolean,
)

object CameraNetworkSelector {
    fun select(candidates: List<NetworkCandidate>): Int? {
        val index = candidates.indexOfFirst { it.isWifi && it.reachesCamera }
        return if (index >= 0) index else null
    }
}

interface CameraNetworkHandle {
    fun bind(socket: Socket)
}

class AndroidNetworkHandle(private val network: Network) : CameraNetworkHandle {
    override fun bind(socket: Socket) {
        network.bindSocket(socket)
    }
}

interface CameraNetworkFinder {
    suspend fun find(host: String, port: Int): CameraNetworkHandle?
}

class BoundSocketFactory(private val handle: CameraNetworkHandle) : SocketFactory() {

    override fun createSocket(): Socket = Socket().also { handle.bind(it) }

    override fun createSocket(host: String?, port: Int): Socket {
        requireNotNull(host) { "host is required" }
        return createSocket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
    }

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket {
        requireNotNull(host) { "host is required" }
        return createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket {
        requireNotNull(host) { "host is required" }
        return createSocket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
    }

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket {
        requireNotNull(address) { "address is required" }
        return createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
