package com.gozar.app.model

/** Wire protocols we can parse. All of them are supported by the sing-box core. */
enum class Protocol(val scheme: String, val label: String) {
    VMESS("vmess", "VMess"),
    VLESS("vless", "VLESS"),
    TROJAN("trojan", "Trojan"),
    SHADOWSOCKS("ss", "Shadowsocks"),
    HYSTERIA2("hysteria2", "Hysteria2"),
    TUIC("tuic", "TUIC"),
    WIREGUARD("wireguard", "WireGuard"),
    SOCKS("socks", "SOCKS"),
    HTTP("http", "HTTP");

    companion object {
        fun fromScheme(raw: String): Protocol? = when (raw.lowercase()) {
            "vmess" -> VMESS
            "vless" -> VLESS
            "trojan", "trojan-go" -> TROJAN
            "ss" -> SHADOWSOCKS
            "hysteria2", "hy2" -> HYSTERIA2
            "tuic" -> TUIC
            "wireguard", "wg", "warp" -> WIREGUARD
            "socks", "socks5" -> SOCKS
            "http", "https" -> HTTP
            else -> null
        }
    }
}

/**
 * A fully parsed proxy definition. Every field the config generator could need
 * lives here so that [com.gozar.app.core.SingBoxConfig] never has to re-parse
 * the original URI.
 */
data class ProxyConfig(
    val protocol: Protocol,
    val name: String,
    val server: String,
    val port: Int,

    // Credentials
    val uuid: String? = null,
    val password: String? = null,
    val method: String? = null,
    val alterId: Int = 0,
    val encryption: String? = null,
    val flow: String? = null,

    // Transport
    val network: String = "tcp",
    val headerType: String? = null,
    val host: String? = null,
    val path: String? = null,
    val serviceName: String? = null,
    val grpcMode: String? = null,

    // TLS
    val security: String = "none",
    val sni: String? = null,
    val alpn: List<String> = emptyList(),
    val fingerprint: String? = null,
    val publicKey: String? = null,
    val shortId: String? = null,
    val allowInsecure: Boolean = false,

    // Hysteria2
    val obfs: String? = null,
    val obfsPassword: String? = null,
    val upMbps: Int? = null,
    val downMbps: Int? = null,

    // TUIC
    val congestionControl: String? = null,
    val udpRelayMode: String? = null,

    // WireGuard
    val privateKey: String? = null,
    val peerPublicKey: String? = null,
    val preSharedKey: String? = null,
    val localAddresses: List<String> = emptyList(),
    val reserved: List<Int> = emptyList(),
    val mtu: Int? = null,

    /**
     * A Cloudflare WARP link, which carries an endpoint but no credentials —
     * those are issued per account by [com.gozar.app.net.Warp] at connect time.
     */
    val warp: Boolean = false,

    val raw: String = "",
) {
    val isTls: Boolean get() = security == "tls" || security == "reality"
    val isReality: Boolean get() = security == "reality"

    /**
     * Stable identity used for de-duplication. Deliberately excludes the display
     * name: aggregators rename the same endpoint constantly.
     */
    val uniqueKey: String
        get() = buildString {
            append(protocol.scheme).append('|')
            append(server.lowercase()).append('|')
            append(port).append('|')
            append(uuid ?: password ?: privateKey ?: "").append('|')
            append(path ?: serviceName ?: "").append('|')
            append(sni ?: host ?: "")
        }
}
