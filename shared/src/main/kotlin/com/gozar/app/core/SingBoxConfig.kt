package com.gozar.app.core

import com.gozar.app.data.Settings
import com.gozar.app.model.Protocol
import com.gozar.app.model.ProxyConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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

    private val pretty = Json { prettyPrint = true }

    /**
     * @param localProxyPort a loopback mixed inbound. On Android it is how the
     *   app measures real end-to-end delay; on desktop it is the proxy the
     *   whole system points at.
     * @param tun whether to create a TUN inbound. False on desktop, where the
     *   system proxy is used instead, so no driver and no elevation are needed.
     * @param clashApiPort exposes the core's byte counters on loopback. Android
     *   reads its throughput straight from libbox, so this is desktop-only.
     */
    fun build(
        proxy: ProxyConfig,
        settings: Settings,
        localProxyPort: Int? = null,
        tun: Boolean = true,
        clashApiPort: Int? = null,
    ): String {
        val wireGuard = proxy.protocol == Protocol.WIREGUARD
        val root = buildJsonObject {
            putJsonObject("log") {
                put("level", "warn")
                put("timestamp", false)
            }
            put("dns", buildDns(settings))

            putJsonArray("inbounds") {
                if (tun) add(buildTun(settings))
                if (localProxyPort != null) {
                    add(
                        buildJsonObject {
                            put("type", "mixed")
                            put("tag", "local-in")
                            put("listen", "127.0.0.1")
                            put("listen_port", localProxyPort)
                        },
                    )
                }
            }

            putJsonArray("outbounds") {
                if (!wireGuard) add(buildOutbound(proxy))
                add(
                    buildJsonObject {
                        put("type", "direct")
                        put("tag", DIRECT_TAG)
                    },
                )
            }
            if (wireGuard) {
                putJsonArray("endpoints") { add(buildWireGuardEndpoint(proxy)) }
            }

            put("route", buildRoute(settings, tun))
            putJsonObject("experimental") {
                putJsonObject("cache_file") {
                    put("enabled", true)
                    put("path", "cache.db")
                }
                if (clashApiPort != null) {
                    // Bound to loopback with no external controller: this is a
                    // byte counter for our own UI, not a remote control surface.
                    putJsonObject("clash_api") {
                        put("external_controller", "127.0.0.1:$clashApiPort")
                    }
                }
            }
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    // ------------------------------------------------------------------ DNS

    private fun buildDns(settings: Settings): JsonObject = buildJsonObject {
        putJsonArray("servers") {
            add(dnsServer(DNS_REMOTE_TAG, settings.remoteDns, PROXY_TAG))
            add(
                buildJsonObject {
                    put("type", "local")
                    put("tag", DNS_LOCAL_TAG)
                },
            )
        }
        putJsonArray("rules") {
            if (settings.bypassIran) {
                add(
                    buildJsonObject {
                        put("domain_suffix", stringArray(RoutingLists.iranDomainSuffixes))
                        put("server", DNS_LOCAL_TAG)
                    },
                )
            }
            if (settings.blockAds) {
                add(
                    buildJsonObject {
                        put("domain_suffix", stringArray(RoutingLists.adDomainSuffixes))
                        put("action", "reject")
                    },
                )
            }
        }
        put("final", DNS_REMOTE_TAG)
    }

    /** Accepts `https://host/path`, `tls://host`, `quic://host`, `host` or `host:port`. */
    private fun dnsServer(tag: String, spec: String, detour: String): JsonObject {
        val value = spec.trim()
        return buildJsonObject {
            put("tag", tag)
            put("detour", detour)
            when {
                value.startsWith("https://") || value.startsWith("h3://") -> {
                    val scheme = if (value.startsWith("h3://")) "h3" else "https"
                    val withoutScheme = value.substringAfter("://")
                    val host = withoutScheme.substringBefore('/')
                    val path = "/" + withoutScheme.substringAfter('/', "dns-query")
                    val (hostname, port) = splitHostPort(host, 443)
                    put("type", scheme)
                    put("server", hostname)
                    put("server_port", port)
                    put("path", path)
                }

                value.startsWith("tls://") || value.startsWith("quic://") -> {
                    val scheme = if (value.startsWith("tls://")) "tls" else "quic"
                    val (hostname, port) = splitHostPort(value.substringAfter("://"), 853)
                    put("type", scheme)
                    put("server", hostname)
                    put("server_port", port)
                }

                value.startsWith("tcp://") -> {
                    val (hostname, port) = splitHostPort(value.removePrefix("tcp://"), 53)
                    put("type", "tcp")
                    put("server", hostname)
                    put("server_port", port)
                }

                else -> {
                    val (hostname, port) = splitHostPort(value.removePrefix("udp://"), 53)
                    put("type", "udp")
                    put("server", hostname)
                    put("server_port", port)
                }
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

    private fun buildTun(settings: Settings): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        put("interface_name", "tun0")
        putJsonArray("address") {
            add("172.19.0.1/30")
            if (settings.ipv6) add("fdfe:dcba:9876::1/126")
        }
        put("mtu", settings.mtu)
        put("auto_route", true)
        put("strict_route", false)
        put("stack", settings.tunStack)
    }

    // ---------------------------------------------------------------- Route

    private fun buildRoute(settings: Settings, tun: Boolean): JsonObject = buildJsonObject {
        putJsonArray("rules") {
            add(buildJsonObject { put("action", "sniff") })
            // Only meaningful when we own a TUN; with a system proxy the OS
            // never sends us raw DNS packets.
            if (tun) {
                add(
                    buildJsonObject {
                        put("protocol", "dns")
                        put("action", "hijack-dns")
                    },
                )
            }
            if (settings.bypassLan) {
                add(
                    buildJsonObject {
                        put("ip_is_private", true)
                        put("outbound", DIRECT_TAG)
                    },
                )
            }
            if (settings.blockAds) {
                add(
                    buildJsonObject {
                        put("domain_suffix", stringArray(RoutingLists.adDomainSuffixes))
                        put("action", "reject")
                    },
                )
            }
            if (settings.bypassIran) {
                // Domain-based only. An IP-range list for Iran cannot be kept
                // accurate here, and a wrong entry silently sends real traffic
                // outside the tunnel — a failure that looks exactly like a dead
                // server, which makes it expensive to diagnose.
                add(
                    buildJsonObject {
                        put("domain_suffix", stringArray(RoutingLists.iranDomainSuffixes))
                        put("outbound", DIRECT_TAG)
                    },
                )
            }
        }
        put("final", PROXY_TAG)
        // Note: override_android_vpn is deliberately not set. Enabling it lets
        // sing-box accept an Android VPN interface as upstream, which can bind
        // the core's own sockets to our tun and loop the tunnel into itself.
        put("auto_detect_interface", true)
        putJsonObject("default_domain_resolver") {
            put("server", DNS_LOCAL_TAG)
            if (!settings.ipv6) put("strategy", "ipv4_only")
        }
    }

    // ------------------------------------------------------------ Outbounds

    fun buildOutbound(proxy: ProxyConfig, tag: String = PROXY_TAG): JsonObject = buildJsonObject {
        put("tag", tag)
        put("server", proxy.server)
        put("server_port", proxy.port)

        when (proxy.protocol) {
            Protocol.VMESS -> {
                put("type", "vmess")
                put("uuid", proxy.uuid ?: throw UnsupportedConfigException("vmess without uuid"))
                put("security", proxy.encryption?.takeIf { it != "none" } ?: "auto")
                put("alter_id", proxy.alterId)
                applyTls(proxy)
                applyTransport(proxy)
            }

            Protocol.VLESS -> {
                put("type", "vless")
                put("uuid", proxy.uuid ?: throw UnsupportedConfigException("vless without uuid"))
                put("packet_encoding", "xudp")
                // `flow` is only meaningful over raw TCP with TLS.
                val flow = proxy.flow
                if (!flow.isNullOrBlank() && proxy.network == "tcp" && proxy.isTls) put("flow", flow)
                applyTls(proxy)
                applyTransport(proxy)
            }

            Protocol.TROJAN -> {
                put("type", "trojan")
                put(
                    "password",
                    proxy.password ?: throw UnsupportedConfigException("trojan without password"),
                )
                applyTls(proxy)
                applyTransport(proxy)
            }

            Protocol.SHADOWSOCKS -> {
                put("type", "shadowsocks")
                put("method", proxy.method ?: throw UnsupportedConfigException("ss without method"))
                put("password", proxy.password.orEmpty())
            }

            Protocol.HYSTERIA2 -> {
                put("type", "hysteria2")
                put("password", proxy.password.orEmpty())
                proxy.upMbps?.let { put("up_mbps", it) }
                proxy.downMbps?.let { put("down_mbps", it) }
                if (!proxy.obfs.isNullOrBlank()) {
                    putJsonObject("obfs") {
                        put("type", proxy.obfs)
                        put("password", proxy.obfsPassword.orEmpty())
                    }
                }
                applyTls(proxy, forceEnabled = true)
            }

            Protocol.TUIC -> {
                put("type", "tuic")
                put("uuid", proxy.uuid ?: throw UnsupportedConfigException("tuic without uuid"))
                put("password", proxy.password.orEmpty())
                put("congestion_control", proxy.congestionControl ?: "bbr")
                put("udp_relay_mode", proxy.udpRelayMode ?: "native")
                applyTls(proxy, forceEnabled = true)
            }

            Protocol.SOCKS -> {
                put("type", "socks")
                put("version", "5")
                proxy.uuid?.let { put("username", it) }
                proxy.password?.let { put("password", it) }
            }

            Protocol.HTTP -> {
                put("type", "http")
                proxy.uuid?.let { put("username", it) }
                proxy.password?.let { put("password", it) }
                if (proxy.isTls) applyTls(proxy)
            }

            Protocol.WIREGUARD ->
                throw UnsupportedConfigException("WireGuard is configured as an endpoint")
        }
    }

    private fun buildWireGuardEndpoint(proxy: ProxyConfig): JsonObject = buildJsonObject {
        put("type", "wireguard")
        put("tag", PROXY_TAG)
        put("system", false)
        put("address", stringArray(proxy.localAddresses))
        put(
            "private_key",
            proxy.privateKey ?: throw UnsupportedConfigException("wireguard without private key"),
        )
        putJsonArray("peers") {
            add(
                buildJsonObject {
                    put("address", proxy.server)
                    put("port", proxy.port)
                    put(
                        "public_key",
                        proxy.peerPublicKey
                            ?: throw UnsupportedConfigException("wireguard without peer key"),
                    )
                    putJsonArray("allowed_ips") {
                        add("0.0.0.0/0")
                        add("::/0")
                    }
                    if (!proxy.preSharedKey.isNullOrBlank()) {
                        put("pre_shared_key", proxy.preSharedKey)
                    }
                    if (proxy.reserved.size == 3) {
                        putJsonArray("reserved") { proxy.reserved.forEach { add(it) } }
                    }
                },
            )
        }
        proxy.mtu?.let { put("mtu", it) }
    }

    private fun JsonObjectBuilder.applyTls(proxy: ProxyConfig, forceEnabled: Boolean = false) {
        if (!forceEnabled && !proxy.isTls) return
        putJsonObject("tls") {
            put("enabled", true)
            val serverName = proxy.sni ?: proxy.host ?: proxy.server
            if (serverName.isNotBlank()) put("server_name", serverName)
            if (proxy.allowInsecure) put("insecure", true)
            if (proxy.alpn.isNotEmpty()) put("alpn", stringArray(proxy.alpn))

            if (proxy.isReality) {
                putJsonObject("reality") {
                    put("enabled", true)
                    put(
                        "public_key",
                        proxy.publicKey
                            ?: throw UnsupportedConfigException("reality without public key"),
                    )
                    put("short_id", proxy.shortId.orEmpty())
                }
                // Reality always rides on uTLS; chrome is the safest default.
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", proxy.fingerprint?.takeIf { it.isNotBlank() } ?: "chrome")
                }
            } else if (!proxy.fingerprint.isNullOrBlank()) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", proxy.fingerprint)
                }
            }
        }
    }

    private fun JsonObjectBuilder.applyTransport(proxy: ProxyConfig) {
        when (proxy.network) {
            "tcp" -> return // raw TCP needs no transport object

            "ws" -> putJsonObject("transport") {
                put("type", "ws")
                val rawPath = proxy.path ?: "/"
                // v2ray encodes early data as `?ed=2048` on the path.
                val path = rawPath.substringBefore('?')
                val earlyData = rawPath.substringAfter("ed=", "").substringBefore('&').toIntOrNull()
                put("path", path.ifBlank { "/" })
                if (!proxy.host.isNullOrBlank()) {
                    putJsonObject("headers") { put("Host", proxy.host) }
                }
                if (earlyData != null && earlyData > 0) {
                    put("max_early_data", earlyData)
                    put("early_data_header_name", "Sec-WebSocket-Protocol")
                }
            }

            "grpc" -> putJsonObject("transport") {
                put("type", "grpc")
                put("service_name", proxy.serviceName ?: proxy.path?.trimStart('/') ?: "")
            }

            "http" -> putJsonObject("transport") {
                put("type", "http")
                if (!proxy.host.isNullOrBlank()) {
                    put("host", stringArray(proxy.host.split(',').map { it.trim() }))
                }
                put("path", proxy.path ?: "/")
            }

            "httpupgrade" -> putJsonObject("transport") {
                put("type", "httpupgrade")
                if (!proxy.host.isNullOrBlank()) put("host", proxy.host)
                put("path", proxy.path ?: "/")
            }

            "quic" -> putJsonObject("transport") { put("type", "quic") }

            else -> throw UnsupportedConfigException("sing-box has no ${proxy.network} transport")
        }
    }

    private fun stringArray(values: Collection<String>): JsonArray =
        buildJsonArray { values.forEach { add(it) } }
}
