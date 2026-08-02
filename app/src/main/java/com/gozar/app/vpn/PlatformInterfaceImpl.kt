@file:Suppress("UsePropertyAccessSyntax")

package com.gozar.app.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.gozar.app.data.PerAppMode
import com.gozar.app.data.Settings
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetAddress
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

/**
 * Bridges libbox to Android.
 *
 * libbox drives the tunnel but owns no platform state: it asks this object to
 * create the TUN device, protect its own sockets, enumerate interfaces and
 * report which one is currently the default.
 *
 * Every call into a gomobile-generated type uses explicit getter/setter methods
 * rather than Kotlin property syntax. gomobile derives Java names by lowering
 * only the first character (`GetMTU` becomes `getMTU`, `DNSServer` becomes
 * `getDNSServer`), which does not map onto Kotlin's property-synthesis rules.
 */
class PlatformInterfaceImpl(
    private val service: GozarVpnService,
    private val settingsProvider: () -> Settings,
) : PlatformInterface {

    private val tag = "GozarPlatform"

    private var tunDescriptor: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var reportedProtectFailure = false

    @Volatile
    private var reportedInterface = false

    @Volatile
    private var reportedInterfaceCount = false

    private val connectivity: ConnectivityManager
        get() = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun closeTun() {
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
    }

    // ------------------------------------------------------------------ TUN

    override fun openTun(options: TunOptions): Int {
        // A config reload opens a fresh TUN; the previous descriptor is ours to
        // release, and leaking one per failover attempt would exhaust the fd
        // table on a device that cycles through several dead servers.
        closeTun()
        val settings = settingsProvider()
        val builder = service.Builder()
            .setSession("Gozar")
            .setMtu(options.getMTU())

        val v4Addresses = options.getInet4Address().toList()
        val v6Addresses = options.getInet6Address().toList()
        for (prefix in v4Addresses) builder.addAddress(prefix.address(), prefix.prefix())
        for (prefix in v6Addresses) builder.addAddress(prefix.address(), prefix.prefix())

        if (options.getAutoRoute()) {
            // sing-box hands us the exact prefixes it wants routed. An empty list
            // means "everything", which is the common case.
            val v4Routes = options.getInet4RouteAddress().toList()
            val v6Routes = options.getInet6RouteAddress().toList()
            if (v4Routes.isEmpty() && v6Routes.isEmpty()) {
                builder.addRoute("0.0.0.0", 0)
                if (v6Addresses.isNotEmpty()) builder.addRoute("::", 0)
            } else {
                for (route in v4Routes) builder.addRoute(route.address(), route.prefix())
                for (route in v6Routes) builder.addRoute(route.address(), route.prefix())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val excluded = options.getInet4RouteExcludeAddress().toList() +
                    options.getInet6RouteExcludeAddress().toList()
                for (route in excluded) {
                    runCatching {
                        builder.excludeRoute(
                            IpPrefix(InetAddress.getByName(route.address()), route.prefix()),
                        )
                    }
                }
            }

            runCatching { builder.addDnsServer(options.getDNSServerAddress().getValue()) }
                .onFailure { Log.w(tag, "no hijack DNS address: ${it.message}") }
        }

        applyPackageFilter(builder, settings)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val descriptor = builder.establish()
            ?: error("VPN permission revoked or another VPN is active")
        tunDescriptor = descriptor
        Diagnostics.log(
            "tun up — mtu ${options.getMTU()}, " +
                "${v4Addresses.size} v4 / ${v6Addresses.size} v6 addresses, " +
                "autoRoute=${options.getAutoRoute()}",
        )
        // libbox dup()s this descriptor, so we keep ownership and close it on stop.
        return descriptor.fd
    }

    /**
     * Our own package is always kept outside the tunnel. When the Xray core is
     * chained behind sing-box it dials the remote server from this very process,
     * and without the exclusion those packets would be routed straight back into
     * the TUN.
     */
    private fun applyPackageFilter(builder: VpnService.Builder, settings: Settings) {
        val self = service.packageName
        when (settings.perAppMode) {
            PerAppMode.OFF -> runCatching { builder.addDisallowedApplication(self) }

            PerAppMode.INCLUDE -> {
                var added = false
                for (pkg in settings.perAppList) {
                    if (pkg == self) continue
                    runCatching { builder.addAllowedApplication(pkg) }.onSuccess { added = true }
                }
                // An empty allow-list would tunnel nothing at all; fall back to
                // tunnelling everything but ourselves.
                if (!added) runCatching { builder.addDisallowedApplication(self) }
            }

            PerAppMode.EXCLUDE -> {
                runCatching { builder.addDisallowedApplication(self) }
                for (pkg in settings.perAppList) {
                    if (pkg == self) continue
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
        }
    }

    // -------------------------------------------------------- Socket control

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    /**
     * Called by the core before every outbound connect.
     *
     * A false return from [android.net.VpnService.protect] must never be fatal
     * here. This app excludes its own package from the tunnel, so its sockets
     * bypass the VPN whether or not they are explicitly protected — and protect()
     * legitimately reports false in that situation, and again for any dial that
     * happens before `establish()` has run. Throwing turned a benign result into
     * a failed dial on *every* outbound, which presents as a tunnel that comes
     * up and then carries nothing.
     */
    override fun autoDetectInterfaceControl(fd: Int) {
        val protectedOk = runCatching { service.protect(fd) }.getOrDefault(false)
        // Reported once: this fires per socket and would otherwise drown the log.
        if (!protectedOk && !reportedProtectFailure) {
            reportedProtectFailure = true
            Diagnostics.log("protect() reports false; sockets bypass the VPN by package exclusion")
        }
    }

    // ---------------------------------------------------- Interface monitor

    @SuppressLint("MissingPermission")
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        // A reload starts a fresh monitor; without this the previous callback
        // stays registered and keeps reporting into a dead listener.
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report(network, null)

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = report(network, capabilities)

            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) =
                report(network, null)

            override fun onLost(network: Network) {
                // Index -1 tells libbox there is no usable upstream right now.
                listener.updateDefaultInterface("", -1, false, false)
            }

            private fun report(network: Network, capabilities: NetworkCapabilities?) {
                val name = connectivity.getLinkProperties(network)?.interfaceName
                if (name == null) {
                    Diagnostics.log("default network has no interface name yet")
                    return
                }
                val index = interfaceIndexOf(name)
                if (index <= 0) {
                    // libbox resolves the default interface by index against the
                    // list from getInterfaces(); with no index it has nothing to
                    // bind to and every dial fails immediately.
                    Diagnostics.log("cannot resolve index for interface '$name'")
                    return
                }
                if (!reportedInterface) {
                    reportedInterface = true
                    Diagnostics.log("default interface: $name (index $index)")
                }
                val caps = capabilities ?: connectivity.getNetworkCapabilities(network)
                val expensive =
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                val constrained =
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED) == false
                listener.updateDefaultInterface(name, index, expensive, constrained)
            }
        }
        networkCallback = callback
        // The default callback reports the network the system would pick for our
        // own (tunnel-excluded) traffic, which is exactly the upstream we want.
        connectivity.registerDefaultNetworkCallback(callback)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val dnsByInterface = HashMap<String, List<String>>()
        val meteredInterfaces = HashSet<String>()
        val typeByInterface = HashMap<String, Int>()

        runCatching {
            @Suppress("DEPRECATION")
            for (network in connectivity.allNetworks) {
                val props = connectivity.getLinkProperties(network) ?: continue
                val name = props.interfaceName ?: continue
                dnsByInterface[name] = props.dnsServers.mapNotNull { it.hostAddress }
                val caps = connectivity.getNetworkCapabilities(network)
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false) {
                    meteredInterfaces.add(name)
                }
                typeByInterface[name] = when {
                    caps == null -> Libbox.InterfaceTypeOther
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                        Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                        Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
            }
        }

        val result = ArrayList<BoxNetworkInterface>()
        val interfaces = runCatching { java.net.NetworkInterface.getNetworkInterfaces() }
            .getOrNull() ?: return NetworkInterfaceList(result)

        for (nif in interfaces) {
            val boxInterface = BoxNetworkInterface()
            boxInterface.setName(nif.name)
            boxInterface.setIndex(nif.index)
            boxInterface.setMTU(runCatching { nif.mtu }.getOrDefault(1500))
            // libbox parses these with netip.MustParsePrefix, which panics on bad
            // input — only well-formed CIDR strings may be handed over.
            boxInterface.setAddresses(
                StringList(
                    nif.interfaceAddresses.mapNotNull { entry ->
                        val address = entry.address ?: return@mapNotNull null
                        val host = address.hostAddress?.substringBefore('%')
                            ?: return@mapNotNull null
                        val length = entry.networkPrefixLength.toInt()
                        // libbox parses these with netip.MustParsePrefix, which
                        // panics on a bad prefix — and a Go panic here takes the
                        // whole process down. Validate per address family.
                        val max = if (address is java.net.Inet4Address) 32 else 128
                        if (length < 0 || length > max) null else "$host/$length"
                    },
                ),
            )
            boxInterface.setFlags(linuxFlags(nif))
            boxInterface.setType(typeByInterface[nif.name] ?: Libbox.InterfaceTypeOther)
            boxInterface.setDNSServer(StringList(dnsByInterface[nif.name] ?: emptyList()))
            boxInterface.setMetered(nif.name in meteredInterfaces)
            result.add(boxInterface)
        }
        if (!reportedInterfaceCount || result.isEmpty()) {
            reportedInterfaceCount = true
            Diagnostics.log(
                "interfaces enumerated: ${result.size}" +
                    if (result.isEmpty()) " (none — the core cannot bind any dial)" else "",
            )
        }
        return NetworkInterfaceList(result)
    }

    /**
     * `NetworkInterface.getByName` is the direct route, but it has been
     * unreliable across Android versions, so a full scan is tried as a fallback
     * before giving up.
     */
    private fun interfaceIndexOf(name: String): Int {
        runCatching { java.net.NetworkInterface.getByName(name)?.index }
            .getOrNull()
            ?.takeIf { it > 0 }
            ?.let { return it }
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.firstOrNull { it.name == name }
                ?.index
                ?: -1
        }.getOrDefault(-1)
    }

    /** Re-encodes java.net flags into the Linux IFF_* bits libbox expects. */
    private fun linuxFlags(nif: java.net.NetworkInterface): Int {
        var flags = 0
        runCatching {
            if (nif.isUp) flags = flags or IFF_UP or IFF_RUNNING
            if (nif.isLoopback) flags = flags or IFF_LOOPBACK
            if (nif.isPointToPoint) flags = flags or IFF_POINTOPOINT
            if (nif.supportsMulticast()) flags = flags or IFF_MULTICAST
            if (nif.interfaceAddresses.any { it.broadcast != null }) flags = flags or IFF_BROADCAST
        }
        return flags
    }

    // ------------------------------------------------------------------ Stubs

    private val dnsTransport by lazy { LocalDnsTransport(service.applicationContext) }

    /**
     * Must not be null on Android: libbox only registers sing-box's `local` DNS
     * transport when the platform provides one, and that transport is what
     * resolves the proxy server's own hostname.
     */
    override fun localDNSTransport(): LocalDNSTransport = dnsTransport

    /** procfs socket lookup is blocked from Android 10 onwards. */
    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        // No rule in our generated config matches on process identity, so this
        // should never be reached; failing beats inventing an owner.
        throw UnsupportedOperationException("connection owner lookup is not available")
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun systemCertificates(): StringIterator = StringList(emptyList())

    override fun clearDNSCache() = Unit

    override fun sendNotification(notification: Notification) {
        service.notifications.showCoreNotification(
            notification.getTitle(),
            notification.getBody(),
        )
    }

    private companion object {
        const val IFF_UP = 0x1
        const val IFF_BROADCAST = 0x2
        const val IFF_LOOPBACK = 0x8
        const val IFF_POINTOPOINT = 0x10
        const val IFF_RUNNING = 0x40
        const val IFF_MULTICAST = 0x1000
    }
}
