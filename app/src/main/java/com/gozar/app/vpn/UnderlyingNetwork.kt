package com.gozar.app.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * Tracks the real network underneath the tunnel.
 *
 * `ConnectivityManager.getActiveNetwork` and `registerDefaultNetworkCallback`
 * both start reporting our own VPN once it is up, which is useless — and
 * actively harmful — for two jobs that must never re-enter the tunnel:
 * resolving DNS for the proxy server's hostname, and telling the core which
 * interface to treat as upstream. Requesting `NOT_VPN` explicitly is the only
 * way to keep hold of the underlying network.
 */
class UnderlyingNetwork(context: Context) {

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var network: Network? = null
        private set

    @Volatile
    var interfaceName: String? = null
        private set

    private var callback: ConnectivityManager.NetworkCallback? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                network = available
                interfaceName = connectivity.getLinkProperties(available)?.interfaceName
                Diagnostics.log("upstream network: ${interfaceName ?: "?"}")
            }

            override fun onLinkPropertiesChanged(
                changed: Network,
                properties: android.net.LinkProperties,
            ) {
                if (changed == network) interfaceName = properties.interfaceName
            }

            override fun onLost(lost: Network) {
                if (lost == network) {
                    network = null
                    interfaceName = null
                    Diagnostics.log("upstream network lost")
                }
            }
        }
        callback = cb

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Delivers exactly the best matching network, which is what "the
            // interface the tunnel runs over" means.
            connectivity.registerBestMatchingNetworkCallback(
                request,
                cb,
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
        } else {
            connectivity.registerNetworkCallback(request, cb)
        }
    }

    fun stop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        network = null
        interfaceName = null
    }
}
