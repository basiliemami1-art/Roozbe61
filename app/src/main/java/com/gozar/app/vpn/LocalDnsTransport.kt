package com.gozar.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Answers sing-box's `local` DNS queries using Android's resolver.
 *
 * This is not optional on Android. libbox only registers the `local` transport
 * when the platform supplies one; without it sing-box falls back to Go's own
 * resolver, which reads `/etc/resolv.conf` — a file Android does not have. The
 * `local` server is what resolves the *proxy server's own hostname*, so leaving
 * it unimplemented means every domain-based config fails to dial while the
 * tunnel still reports itself as up.
 *
 * Queries deliberately go out over the underlying network rather than the
 * tunnel: the app excludes itself from its own VPN, so this cannot loop.
 */
class LocalDnsTransport(private val context: Context) : LocalDNSTransport {

    private val tag = "GozarDns"

    /** false: we answer with addresses, not raw DNS wire messages. */
    override fun raw(): Boolean = false

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val host = domain.trimEnd('.')
        if (host.isEmpty()) {
            ctx.errorCode(RCODE_NXDOMAIN)
            return
        }
        try {
            val resolved = resolve(host)
            val matching = when (network) {
                "ip4" -> resolved.filterIsInstance<Inet4Address>()
                "ip6" -> resolved.filterIsInstance<Inet6Address>()
                else -> resolved.toList()
            }
            val addresses = matching.mapNotNull { it.hostAddress?.substringBefore('%') }
            if (addresses.isEmpty()) {
                ctx.errorCode(RCODE_NXDOMAIN)
            } else {
                // The Go side splits this on newlines.
                ctx.success(addresses.joinToString("\n"))
            }
        } catch (error: Throwable) {
            Log.d(tag, "lookup $host/$network failed: ${error.message}")
            ctx.errorCode(RCODE_NXDOMAIN)
        }
    }

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        // Unreachable while raw() is false, but must not fail silently.
        ctx.errorCode(RCODE_SERVFAIL)
    }

    private fun resolve(host: String): Array<InetAddress> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        // Resolving through the Network object pins the query to that network's
        // DNS servers instead of whatever the process default happens to be.
        return network?.let { runCatching { it.getAllByName(host) }.getOrNull() }
            ?: InetAddress.getAllByName(host)
    }

    private companion object {
        const val RCODE_SERVFAIL = 2
        const val RCODE_NXDOMAIN = 3
    }
}
