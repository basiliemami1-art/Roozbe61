package com.gozar.app.core

import com.gozar.app.data.Settings
import com.gozar.app.model.Protocol
import com.gozar.app.model.ProxyConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generates a sing-box configuration for a single selected server.
 *
 * Targets the sing-box 1.12+ schema: typed DNS servers (`type`/`server`), route
 * rule *actions* rather than the removed `block`/`dns` outbounds, `address`
 * arrays on the TUN inbound, WireGuard as an endpoint, and an explicit
 * `default_domain_resolver`.
 */
object SingBoxConfig {

    const val PROXY_TAG = "proxy"
    const val DIRECT_TAG = "direct"
    const val DNS_LOCAL_TAG = "dns-local"
    const val DNS_REMOTE_TAG = "dns-remote"

    class UnsupportedConfigException(message: String) : Exception(message)

    /**
     * @param localProxyPort a loopback mixed inbound. The app is excluded from
     *   its own tunnel, so this is the only way it can measure end-to-end delay
     *   through the proxy it just brought up.
     */
    fun build(
        proxy: ProxyConfig,
        settings: Settings,
        localProxyPort: Int? = null,
    ): String {
        val root = JSONObject()

        root.put("log", JSONObject().put("level", "warn").put("timestamp", false))
        root.put("dns", buildDns(settings))

        val inbounds = JSONArray().put(buildTun(settings))
        if (localProxyPort != null) {
            inbounds.put(
                JSONObject()
                    .put("type", "mixed")
                    .put("tag", "local-in")
                    .put("listen", "127.0.0.1")
                    .put("listen_port", localProxyPort),
            )
        }
        root.put("inbounds", inbounds)

        val outbounds = JSONArray()
        val endpoints = JSONArray()

        if (proxy.protocol == Protocol.WIREGUARD) {
            endpoints.put(buildWireGuardEndpoint(proxy))
        } else {
            outbounds.put(buildOutbound(proxy))
        }

        outbounds.put(JSONObject().put("type", "direct").put("tag", DIRECT_TAG))
        root.put("outbounds", outbounds)
        if (endpoints.length() > 0) root.put("endpoints", endpoints)

        root.put("route", buildRoute(settings))
        root.put(
            "experimental",
            JSONObject().put(
                "cache_file",
                JSONObject().put("enabled", true).put("path", "cache.db"),
            ),
        )
        return root.toString(2)
    }

    // ------------------------------------------------------------------ DNS

    private fun buildDns(settings: Settings): JSONObject {
        val servers = JSONArray()
            .put(dnsServer(DNS_REMOTE_TAG, settings.remoteDns, PROXY_TAG))
            .put(JSONObject().put("type", "local").put("tag", DNS_LOCAL_TAG))

        val rules = JSONArray()
        if (settings.bypassIran) {
            rules.put(
                JSONObject()
                    .put("domain_suffix", JSONArray(RoutingLists.iranDomainSuffixes))
                    .put("server", DNS_LOCAL_TAG),
            )
        }
        if (settings.blockAds) {
            rules.put(
                JSONObject()
                    .put("domain_suffix", JSONArray(RoutingLists.adDomainSuffixes))
                    .put("action", "reject"),
            )
        }

        return JSONObject()
            .put("servers", servers)
            .put("rules", rules)
            .put("final", DNS_REMOTE_TAG)
    }

    /** Accepts `https://host/path`, `tls://host`, `quic://host`, `host` or `host:port`. */
    private fun dnsServer(tag: String, spec: String, detour: String): JSONObject {
        val value = spec.trim()
        val server = JSONObject().put("tag", tag).put("detour", detour)
        return when {
            value.startsWith("https://") -> {
                val withoutScheme = value.removePrefix("https://")
                val host = withoutScheme.substringBefore('/')
                val path = "/" + withoutScheme.substringAfter('/', "dns-query")
                val (hostname, port) = splitHostPort(host, 443)
                server.put("type", "https").put("server", hostname)
                    .put("server_port", port).put("path", path)
            }

            value.startsWith("h3://") -> {
                val withoutScheme = value.removePrefix("h3://")
                val host = withoutScheme.substringBefore('/')
                val path = "/" + withoutScheme.substringAfter('/', "dns-query")
                val (hostname, port) = splitHostPort(host, 443)
                server.put("type", "h3").put("server", hostname)
                    .put("server_port", port).put("path", path)
            }

            value.startsWith("tls://") -> {
                val (hostname, port) = splitHostPort(value.removePrefix("tls://"), 853)
                server.put("type", "tls").put("server", hostname).put("server_port", port)
            }

            value.startsWith("quic://") -> {
                val (hostname, port) = splitHostPort(value.removePrefix("quic://"), 853)
                server.put("type", "quic").put("server", hostname).put("server_port", port)
            }

            value.startsWith("tcp://") -> {
                val (hostname, port) = splitHostPort(value.removePrefix("tcp://"), 53)
                server.put("type", "tcp").put("server", hostname).put("server_port", port)
            }

            else -> {
                val (hostname, port) = splitHostPort(value.removePrefix("udp://"), 53)
                server.put("type", "udp").put("server", hostname).put("server_port", port)
            }
        }
    }

    private fun splitHostPort(value: String, defaultPort: Int): Pair<String, Int> {
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close > 0) {
                val host = value.substring(1, close)
                val port = value.substring(close + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                return host to port
            }
        }
        val colon = value.lastIndexOf(':')
        if (colon > 0) {
            val port = value.substring(colon + 1).toIntOrNull()
            if (port != null) return value.substring(0, colon) to port
        }
        return value to defaultPort
    }

    // ------------------------------------------------------------------ TUN

    private fun buildTun(settings: Settings): JSONObject {
        val addresses = JSONArray().put("172.19.0.1/30")
        if (settings.ipv6) addresses.put("fdfe:dcba:9876::1/126")
        return JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("interface_name", "tun0")
            .put("address", addresses)
            .put("mtu", settings.mtu)
            .put("auto_route", true)
            .put("strict_route", false)
            .put("stack", settings.tunStack)
    }

    // ---------------------------------------------------------------- Route

    private fun buildRoute(settings: Settings): JSONObject {
        val rules = JSONArray()
        rules.put(JSONObject().put("action", "sniff"))
        rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))

        // Our own process is kept out of the tunnel by excluding the package on
        // VpnService.Builder rather than with a `package_name` route rule: rule
        // matching would force a connection-owner lookup on every single
        // connection, and the OS-level exclusion is both free and stricter.

        if (settings.bypassLan) {
            rules.put(JSONObject().put("ip_is_private", true).put("outbound", DIRECT_TAG))
        }
        if (settings.blockAds) {
            rules.put(
                JSONObject()
                    .put("domain_suffix", JSONArray(RoutingLists.adDomainSuffixes))
                    .put("action", "reject"),
            )
        }
        if (settings.bypassIran) {
            // Domain-based only. An IP-range list for Iran cannot be maintained
            // accurately here, and a wrong entry silently sends real traffic
            // outside the tunnel — the failure it causes looks exactly like a
            // dead server, which makes it expensive to diagnose.
            rules.put(
                JSONObject()
                    .put("domain_suffix", JSONArray(RoutingLists.iranDomainSuffixes))
                    .put("outbound", DIRECT_TAG),
            )
        }

        val route = JSONObject()
            .put("rules", rules)
            .put("final", PROXY_TAG)
            // Note: override_android_vpn is deliberately not set. Enabling it
            // lets sing-box accept an Android VPN interface as upstream, which
            // can bind the core's own sockets to our tun and loop the tunnel
            // back into itself.
            .put("auto_detect_interface", true)

        val resolver = JSONObject().put("server", DNS_LOCAL_TAG)
        if (!settings.ipv6) resolver.put("strategy", "ipv4_only")
        route.put("default_domain_resolver", resolver)

        // Per-app filtering is applied directly on VpnService.Builder in
        // PlatformInterfaceImpl — sing-box never needs to know about it, and
        // unknown keys here would fail config validation.
        return route
    }

    // ------------------------------------------------------------ Outbounds

    fun buildOutbound(proxy: ProxyConfig, tag: String = PROXY_TAG): JSONObject {
        val out = JSONObject().put("tag", tag)
            .put("server", proxy.server)
            .put("server_port", proxy.port)

        when (proxy.protocol) {
            Protocol.VMESS -> {
                out.put("type", "vmess")
                    .put("uuid", proxy.uuid ?: throw UnsupportedConfigException("vmess without uuid"))
                    .put("security", proxy.encryption?.takeIf { it != "none" } ?: "auto")
                    .put("alter_id", proxy.alterId)
                applyTls(out, proxy)
                applyTransport(out, proxy)
            }

            Protocol.VLESS -> {
                out.put("type", "vless")
                    .put("uuid", proxy.uuid ?: throw UnsupportedConfigException("vless without uuid"))
                    .put("packet_encoding", "xudp")
                // `flow` is only meaningful over raw TCP with TLS.
                val flow = proxy.flow
                if (!flow.isNullOrBlank() && proxy.network == "tcp" && proxy.isTls) {
                    out.put("flow", flow)
                }
                applyTls(out, proxy)
                applyTransport(out, proxy)
            }

            Protocol.TROJAN -> {
                out.put("type", "trojan")
                    .put("password", proxy.password ?: throw UnsupportedConfigException("trojan without password"))
                applyTls(out, proxy)
                applyTransport(out, proxy)
            }

            Protocol.SHADOWSOCKS -> {
                out.put("type", "shadowsocks")
                    .put("method", proxy.method ?: throw UnsupportedConfigException("ss without method"))
                    .put("password", proxy.password.orEmpty())
            }

            Protocol.HYSTERIA2 -> {
                out.put("type", "hysteria2")
                    .put("password", proxy.password.orEmpty())
                proxy.upMbps?.let { out.put("up_mbps", it) }
                proxy.downMbps?.let { out.put("down_mbps", it) }
                if (!proxy.obfs.isNullOrBlank()) {
                    out.put(
                        "obfs",
                        JSONObject()
                            .put("type", proxy.obfs)
                            .put("password", proxy.obfsPassword.orEmpty()),
                    )
                }
                applyTls(out, proxy, forceEnabled = true)
            }

            Protocol.TUIC -> {
                out.put("type", "tuic")
                    .put("uuid", proxy.uuid ?: throw UnsupportedConfigException("tuic without uuid"))
                    .put("password", proxy.password.orEmpty())
                    .put("congestion_control", proxy.congestionControl ?: "bbr")
                    .put("udp_relay_mode", proxy.udpRelayMode ?: "native")
                applyTls(out, proxy, forceEnabled = true)
            }

            Protocol.SOCKS -> {
                out.put("type", "socks").put("version", "5")
                proxy.uuid?.let { out.put("username", it) }
                proxy.password?.let { out.put("password", it) }
            }

            Protocol.HTTP -> {
                out.put("type", "http")
                proxy.uuid?.let { out.put("username", it) }
                proxy.password?.let { out.put("password", it) }
                if (proxy.isTls) applyTls(out, proxy)
            }

            Protocol.WIREGUARD ->
                throw UnsupportedConfigException("WireGuard is configured as an endpoint")
        }
        return out
    }

    private fun buildWireGuardEndpoint(proxy: ProxyConfig): JSONObject {
        val peer = JSONObject()
            .put("address", proxy.server)
            .put("port", proxy.port)
            .put(
                "public_key",
                proxy.peerPublicKey ?: throw UnsupportedConfigException("wireguard without peer key"),
            )
            .put("allowed_ips", JSONArray().put("0.0.0.0/0").put("::/0"))
        if (!proxy.preSharedKey.isNullOrBlank()) {
            peer.put("pre_shared_key", proxy.preSharedKey)
        }
        if (proxy.reserved.size == 3) {
            peer.put("reserved", JSONArray(proxy.reserved))
        }

        val endpoint = JSONObject()
            .put("type", "wireguard")
            .put("tag", PROXY_TAG)
            .put("system", false)
            .put("address", JSONArray(proxy.localAddresses))
            .put(
                "private_key",
                proxy.privateKey ?: throw UnsupportedConfigException("wireguard without private key"),
            )
            .put("peers", JSONArray().put(peer))
        proxy.mtu?.let { endpoint.put("mtu", it) }
        return endpoint
    }

    private fun applyTls(out: JSONObject, proxy: ProxyConfig, forceEnabled: Boolean = false) {
        if (!forceEnabled && !proxy.isTls) return
        val tls = JSONObject().put("enabled", true)
        val serverName = proxy.sni ?: proxy.host ?: proxy.server
        if (serverName.isNotBlank()) tls.put("server_name", serverName)
        if (proxy.allowInsecure) tls.put("insecure", true)
        if (proxy.alpn.isNotEmpty()) tls.put("alpn", JSONArray(proxy.alpn))

        if (proxy.isReality) {
            tls.put(
                "reality",
                JSONObject()
                    .put("enabled", true)
                    .put(
                        "public_key",
                        proxy.publicKey ?: throw UnsupportedConfigException("reality without public key"),
                    )
                    .put("short_id", proxy.shortId.orEmpty()),
            )
            // Reality always rides on uTLS; chrome is the safest default.
            tls.put(
                "utls",
                JSONObject()
                    .put("enabled", true)
                    .put("fingerprint", proxy.fingerprint?.takeIf { it.isNotBlank() } ?: "chrome"),
            )
        } else if (!proxy.fingerprint.isNullOrBlank()) {
            tls.put(
                "utls",
                JSONObject().put("enabled", true).put("fingerprint", proxy.fingerprint),
            )
        }
        out.put("tls", tls)
    }

    private fun applyTransport(out: JSONObject, proxy: ProxyConfig) {
        when (proxy.network) {
            "tcp" -> return // raw TCP needs no transport object

            "ws" -> {
                val transport = JSONObject().put("type", "ws")
                val rawPath = proxy.path ?: "/"
                // v2ray encodes early data as `?ed=2048` on the path.
                val path = rawPath.substringBefore('?')
                val earlyData = rawPath.substringAfter("ed=", "").substringBefore('&').toIntOrNull()
                transport.put("path", path.ifBlank { "/" })
                if (!proxy.host.isNullOrBlank()) {
                    transport.put("headers", JSONObject().put("Host", proxy.host))
                }
                if (earlyData != null && earlyData > 0) {
                    transport.put("max_early_data", earlyData)
                    transport.put("early_data_header_name", "Sec-WebSocket-Protocol")
                }
                out.put("transport", transport)
            }

            "grpc" -> {
                val name = proxy.serviceName ?: proxy.path?.trimStart('/') ?: ""
                out.put(
                    "transport",
                    JSONObject().put("type", "grpc").put("service_name", name),
                )
            }

            "http" -> {
                val transport = JSONObject().put("type", "http")
                if (!proxy.host.isNullOrBlank()) {
                    transport.put("host", JSONArray(proxy.host.split(',').map { it.trim() }))
                }
                transport.put("path", proxy.path ?: "/")
                out.put("transport", transport)
            }

            "httpupgrade" -> {
                val transport = JSONObject().put("type", "httpupgrade")
                if (!proxy.host.isNullOrBlank()) transport.put("host", proxy.host)
                transport.put("path", proxy.path ?: "/")
                out.put("transport", transport)
            }

            "quic" -> out.put("transport", JSONObject().put("type", "quic"))

            else -> throw UnsupportedConfigException(
                "sing-box has no ${proxy.network} transport",
            )
        }
    }
}
