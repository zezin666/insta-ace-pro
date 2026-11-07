package com.acegallery.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidCameraNetworkFinder(context: Context) : CameraNetworkFinder {

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    override suspend fun find(host: String, port: Int): CameraNetworkHandle? = withContext(Dispatchers.IO) {
        val found = mutableListOf<Pair<Network, NetworkCandidate>>()
        for (network in manager.allNetworks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val reaches = isWifi && canReach(network, host, port)
            found += network to NetworkCandidate(isWifi, reaches)
        }
        CameraNetworkSelector.select(found.map { it.second })
            ?.let { AndroidNetworkHandle(found[it].first) }
    }

    private fun canReach(network: Network, host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(host, port), 800)
        }
        true
    }.getOrDefault(false)
}
