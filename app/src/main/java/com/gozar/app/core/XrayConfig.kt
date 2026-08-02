package com.gozar.app.core

import com.gozar.app.model.Protocol
import com.gozar.app.model.ProxyConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generates an Xray configuration that exposes a loopback SOCKS5 inbound.
 *
 * Xray never touches the TUN device here: sing-box owns the tunnel and forwards
 * to this SOCKS port, which keeps a single, well-understood path for packets
 * regardless of which core the user picked. Xray's own outbound sockets escape
 * the tunnel because the app's package is routed direct (see
 * [SingBoxConfig.buildRoute]) and excluded on the VpnService builder.
 */
object XrayConfig {

    const val PROXY_TAG = "proxy"

    fun build(proxy: ProxyConfig, socksPort: Int): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        root.put(
            "inbounds",
            JSONArray().put(
                JSONObject()
                    .put("tag", "socks-in")
                    .put("listen", "127.0.0.1")
                    .put("port", socksPort)
                    .put("protocol", "socks")
                    .put(
                        "settings",
                        JSONObject().put("auth", "noauth").put("udp", true).put("userLevel", 8),
                    )
                    .put(
                        "sniffing",
                        JSONObject()
                            .put("enabled", true)
                            .put("destOverride", JSONArray().put("http").put("tls"))
                            .put("routeOnly", false),
                    ),
            ),
        )

        root.put(
            "outbounds",
            JSONArray()
                .put(buildOutbound(proxy))
                .put(
                    JSONObject()
                        .put("protocol", "freedom")
                        .put("tag", "direct")
                        .put("settings", JSONObject()),
                ),
        )

        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", JSONArray()),
        )

        root.put(
            "policy",
            JSONObject().put(
                "levels",
                JSONObject().put(
                    "8",
                    JSONObject().put("connIdle", 300).put("handshake", 4).put("uplinkOnly", 1)
                        .put("downlinkOnly", 1),
                ),
            ),
        )
        return root.toString(2)
    }

    fun buildOutbound(proxy: ProxyConfig, tag: String = PROXY_TAG): JSONObject {
        val out = JSONObject().put("tag", tag)
        when (proxy.protocol) {
            Protocol.VMESS -> {
                out.put("protocol", "vmess")
                val user = JSONObject()
                    .put("id", proxy.uuid.orEmpty())
                    .put("alterId", proxy.alterId)
                    .put("security", proxy.encryption?.takeIf { it != "none" } ?: "auto")
                    .put("level", 8)
                out.put("settings", vnext(proxy, user))
                out.put("streamSettings", streamSettings(proxy))
            }

            Protocol.VLESS -> {
                out.put("protocol", "vless")
                val user = JSONObject()
                    .put("id", proxy.uuid.orEmpty())
                    .put("encryption", proxy.encryption ?: "none")
                    .put("level", 8)
                val flow = proxy.flow
                if (!flow.isNullOrBlank() && proxy.network == "tcp" && proxy.isTls) {
                    user.put("flow", flow)
                }
                out.put("settings", vnext(proxy, user))
                out.put("streamSettings", streamSettings(proxy))
            }

            Protocol.TROJAN -> {
                out.put("protocol", "trojan")
                out.put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", proxy.server)
                                .put("port", proxy.port)
                                .put("password", proxy.password.orEmpty())
                                .put("level", 8),
                        ),
                    ),
                )
                out.put("streamSettings", streamSettings(proxy))
            }

            Protocol.SHADOWSOCKS -> {
                out.put("protocol", "shadowsocks")
                out.put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", proxy.server)
                                .put("port", proxy.port)
                                .put("method", proxy.method.orEmpty())
                                .put("password", proxy.password.orEmpty())
                                .put("level", 8)
                                .put("uot", false),
                        ),
                    ),
                )
                out.put("streamSettings", streamSettings(proxy))
            }

            Protocol.SOCKS, Protocol.HTTP -> {
                out.put("protocol", if (proxy.protocol == Protocol.SOCKS) "socks" else "http")
                val server = JSONObject().put("address", proxy.server).put("port", proxy.port)
                if (!proxy.uuid.isNullOrBlank()) {
                    server.put(
                        "users",
                        JSONArray().put(
                            JSONObject()
                                .put("user", proxy.uuid)
                                .put("pass", proxy.password.orEmpty())
                                .put("level", 8),
                        ),
                    )
                }
                out.put("settings", JSONObject().put("servers", JSONArray().put(server)))
                out.put("streamSettings", streamSettings(proxy))
            }

            Protocol.HYSTERIA2, Protocol.TUIC, Protocol.WIREGUARD ->
                throw SingBoxConfig.UnsupportedConfigException(
                    "${proxy.protocol.label} is not supported by the Xray core",
                )
        }
        return out
    }

    private fun vnext(proxy: ProxyConfig, user: JSONObject): JSONObject = JSONObject().put(
        "vnext",
        JSONArray().put(
            JSONObject()
                .put("address", proxy.server)
                .put("port", proxy.port)
                .put("users", JSONArray().put(user)),
        ),
    )

    private fun streamSettings(proxy: ProxyConfig): JSONObject {
        val stream = JSONObject().put("network", proxy.network)

        when (proxy.security) {
            "tls" -> {
                stream.put("security", "tls")
                val tls = JSONObject()
                    .put("serverName", proxy.sni ?: proxy.host ?: proxy.server)
                    .put("allowInsecure", proxy.allowInsecure)
                if (proxy.alpn.isNotEmpty()) tls.put("alpn", JSONArray(proxy.alpn))
                if (!proxy.fingerprint.isNullOrBlank()) tls.put("fingerprint", proxy.fingerprint)
                stream.put("tlsSettings", tls)
            }

            "reality" -> {
                stream.put("security", "reality")
                stream.put(
                    "realitySettings",
                    JSONObject()
                        .put("serverName", proxy.sni ?: proxy.server)
                        .put("publicKey", proxy.publicKey.orEmpty())
                        .put("shortId", proxy.shortId.orEmpty())
                        .put("fingerprint", proxy.fingerprint?.takeIf { it.isNotBlank() } ?: "chrome")
                        .put("spiderX", ""),
                )
            }

            else -> stream.put("security", "none")
        }

        when (proxy.network) {
            "tcp" -> stream.put(
                "tcpSettings",
                JSONObject().put(
                    "header",
                    JSONObject().put("type", proxy.headerType ?: "none"),
                ),
            )

            "ws" -> {
                val ws = JSONObject().put("path", proxy.path ?: "/")
                if (!proxy.host.isNullOrBlank()) {
                    ws.put("headers", JSONObject().put("Host", proxy.host))
                }
                stream.put("wsSettings", ws)
            }

            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject()
                    .put("serviceName", proxy.serviceName ?: proxy.path?.trimStart('/') ?: "")
                    .put("multiMode", proxy.grpcMode == "multi"),
            )

            "http" -> {
                val http = JSONObject().put("path", proxy.path ?: "/")
                if (!proxy.host.isNullOrBlank()) {
                    http.put("host", JSONArray(proxy.host.split(',').map { it.trim() }))
                }
                stream.put("httpSettings", http)
            }

            "httpupgrade" -> {
                val hu = JSONObject().put("path", proxy.path ?: "/")
                if (!proxy.host.isNullOrBlank()) hu.put("host", proxy.host)
                stream.put("httpupgradeSettings", hu)
            }

            else -> throw SingBoxConfig.UnsupportedConfigException(
                "Xray has no ${proxy.network} transport in this build",
            )
        }
        return stream
    }
}
