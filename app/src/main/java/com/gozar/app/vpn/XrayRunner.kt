@file:Suppress("UsePropertyAccessSyntax")

package com.gozar.app.vpn

import android.content.Context
import android.util.Log
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.net.ServerSocket

/**
 * Runs the Xray core as a loopback SOCKS5 proxy.
 *
 * Xray never owns the TUN device here — sing-box does, and forwards to this
 * port. That keeps one packet path for both cores and means Xray's outbound
 * sockets simply need to be outside the tunnel, which the VpnService package
 * exclusion already guarantees.
 */
class XrayRunner(private val context: Context) {

    private val tag = "GozarXray"
    private var controller: CoreController? = null

    val isRunning: Boolean
        get() = runCatching { controller?.getIsRunning() == true }.getOrDefault(false)

    fun version(): String = runCatching { Libv2ray.checkVersionX() }.getOrDefault("unknown")

    /** @return the loopback port the core is listening on. */
    fun start(configJson: String, port: Int) {
        stop()
        Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
        val handler = object : CoreCallbackHandler {
            override fun startup(): Long = 0

            override fun shutdown(): Long {
                Log.i(tag, "core shutdown requested")
                return 0
            }

            override fun onEmitStatus(code: Long, message: String?): Long {
                Log.i(tag, "status $code: $message")
                return 0
            }
        }
        val core = Libv2ray.newCoreController(handler)
        // tunFd is unused: this instance only serves the loopback SOCKS inbound.
        core.startLoop(configJson, 0)
        controller = core
        Log.i(tag, "Xray core listening on 127.0.0.1:$port")
    }

    fun stop() {
        val core = controller ?: return
        controller = null
        runCatching { core.stopLoop() }
            .onFailure { Log.w(tag, "stopLoop failed: ${it.message}") }
    }

    companion object {
        /** Asks the OS for a free loopback port. */
        fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
