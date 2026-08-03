package com.gozar.app.parser

import com.gozar.app.model.Protocol
import com.gozar.app.model.ProxyConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns share links from aggregators, Telegram posts and the clipboard into
 * [ProxyConfig]. Everything is best-effort: a malformed link yields null rather
 * than throwing, because a single bad entry must never abort a 20k-config import.
 */
object ConfigParser {

    private val URI_PATTERN = Regex(
        """(?i)\b(vmess|vless|trojan|ss|hysteria2|hysteria|hy2|tuic|wireguard|warp|socks5|socks)://[^\s"'<>\\`\[\]{}|^]+""",
    )

    private val HTML_TAG = Regex("""<[^>]{1,400}>""")

    /** Pulls every config-looking URI out of arbitrary text or HTML. */
    fun extractAll(input: String): List<String> {
        var text = input
        if (text.contains('<') && text.contains('>')) {
            // Replace tags with newlines so adjacent links never fuse together.
            text = HTML_TAG.replace(text, "\n")
        }
        text = Codecs.unescapeHtml(text)
        return URI_PATTERN.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ';', '"', '\'') }
            .toList()
    }

    fun parseMany(input: String): List<ProxyConfig> {
        val seen = HashSet<String>()
        val out = ArrayList<ProxyConfig>()
        for (uri in extractAll(input)) {
            val config = parse(uri) ?: continue
            if (seen.add(config.uniqueKey)) out.add(config)
        }
        return out
    }

    fun parse(rawInput: String): ProxyConfig? {
        val raw = rawInput.trim()
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0) return null
        val protocol = Protocol.fromScheme(raw.substring(0, schemeEnd)) ?: return null
        return try {
            when (protocol) {
                Protocol.VMESS -> parseVmess(raw)
                Protocol.VLESS -> parseVless(raw)
                Protocol.TROJAN -> parseTrojan(raw)
                Protocol.SHADOWSOCKS -> parseShadowsocks(raw)
                Protocol.HYSTERIA2 -> parseHysteria2(raw)
                Protocol.TUIC -> parseTuic(raw)
                Protocol.WIREGUARD -> parseWireGuard(raw)
                Protocol.SOCKS, Protocol.HTTP -> parseSocksOrHttp(raw, protocol)
            }?.takeIf { it.server.isNotBlank() && it.port in 1..65535 }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- VMess

    private fun parseVmess(raw: String): ProxyConfig? {
        val body = raw.substring("vmess://".length)
        // Modern aggregators still overwhelmingly emit base64-wrapped JSON.
        val json = Codecs.decodeBase64(body.substringBefore('#'))
        if (json != null && json.trimStart().startsWith("{")) {
            return parseVmessJson(json, raw)
        }
        // Fallback: the AEAD-style `vmess://uuid@host:port?...` form.
        val uri = LooseUri.parse(raw) ?: return null
        val query = uri
        return ProxyConfig(
            protocol = Protocol.VMESS,
            name = uri.fragment ?: uri.host,
            server = uri.host,
            port = uri.port,
            uuid = uri.userInfo,
            alterId = query["alterid"]?.toIntOrNull() ?: 0,
            encryption = query.first("encryption", "scy") ?: "auto",
            network = normalizeNetwork(query.first("type", "network") ?: "tcp"),
            headerType = query["headertype"],
            host = query["host"],
            path = query["path"],
            serviceName = query["servicename"],
            security = normalizeSecurity(query.first("security", "tls")),
            sni = query["sni"],
            alpn = splitAlpn(query["alpn"]),
            fingerprint = query["fp"],
            allowInsecure = query.first("allowinsecure", "insecure") == "1",
            raw = raw,
        )
    }

    /**
     * The vmess JSON in the wild is not well typed: `port` and `aid` are
     * sometimes numbers and sometimes strings, and unknown keys are common, so
     * every field is read as loose text.
     */
    private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun JsonObject.text(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun parseVmessJson(json: String, raw: String): ProxyConfig? {
        val obj = runCatching { lenientJson.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return null
        val server = obj.text("add").ifBlank { return null }
        val port = obj.text("port").trim().toIntOrNull() ?: return null
        val net = normalizeNetwork(obj.text("net").ifBlank { "tcp" })
        val tls = normalizeSecurity(obj.text("tls"))
        val host = obj.text("host").takeIf { it.isNotBlank() }
        val path = obj.text("path").takeIf { it.isNotBlank() }
        return ProxyConfig(
            protocol = Protocol.VMESS,
            name = obj.text("ps").ifBlank { server },
            server = server,
            port = port,
            uuid = obj.text("id").ifBlank { return null },
            alterId = obj.text("aid").trim().toIntOrNull() ?: 0,
            encryption = obj.text("scy").ifBlank { "auto" },
            network = net,
            headerType = obj.text("type").takeIf { it.isNotBlank() && it != "none" },
            host = host,
            path = path,
            // gRPC reuses the `path` field for the service name in this format.
            serviceName = if (net == "grpc") path?.trimStart('/') else null,
            security = tls,
            sni = obj.text("sni").takeIf { it.isNotBlank() } ?: host.takeIf { tls != "none" },
            alpn = splitAlpn(obj.text("alpn")),
            fingerprint = obj.text("fp").takeIf { it.isNotBlank() },
            allowInsecure = obj.text("verify_cert") == "false" || obj.text("allowInsecure") == "1",
            raw = raw,
        )
    }

    // ---------------------------------------------------------------- VLESS

    private fun parseVless(raw: String): ProxyConfig? {
        val uri = LooseUri.parse(raw) ?: return null
        val uuid = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val net = normalizeNetwork(uri.first("type", "network") ?: "tcp")
        val security = normalizeSecurity(uri["security"])
        return ProxyConfig(
            protocol = Protocol.VLESS,
            name = uri.fragment ?: "${uri.host}:${uri.port}",
            server = uri.host,
            port = uri.port,
            uuid = uuid,
            encryption = uri["encryption"] ?: "none",
            flow = uri["flow"]?.takeIf { it.isNotBlank() },
            network = net,
            headerType = uri["headertype"],
            host = uri["host"],
            path = uri["path"],
            serviceName = uri["servicename"]?.trimStart('/')
                ?: if (net == "grpc") uri["path"]?.trimStart('/') else null,
            grpcMode = uri["mode"],
            security = security,
            sni = uri.first("sni", "peer") ?: uri["host"].takeIf { security != "none" },
            alpn = splitAlpn(uri["alpn"]),
            fingerprint = uri["fp"],
            publicKey = uri.first("pbk", "publickey"),
            shortId = uri.first("sid", "shortid"),
            allowInsecure = uri.first("allowinsecure", "insecure") == "1",
            raw = raw,
        )
    }

    // --------------------------------------------------------------- Trojan

    private fun parseTrojan(raw: String): ProxyConfig? {
        val uri = LooseUri.parse(raw) ?: return null
        val password = uri.userInfo?.let { Codecs.urlDecode(it) }?.takeIf { it.isNotBlank() }
            ?: return null
        val net = normalizeNetwork(uri.first("type", "network") ?: "tcp")
        // Trojan is TLS by definition unless explicitly told otherwise.
        val security = when (uri["security"]?.lowercase()) {
            "none" -> "none"
            "reality" -> "reality"
            else -> "tls"
        }
        return ProxyConfig(
            protocol = Protocol.TROJAN,
            name = uri.fragment ?: "${uri.host}:${uri.port}",
            server = uri.host,
            port = uri.port,
            password = password,
            network = net,
            headerType = uri["headertype"],
            host = uri["host"],
            path = uri["path"],
            serviceName = uri["servicename"]?.trimStart('/')
                ?: if (net == "grpc") uri["path"]?.trimStart('/') else null,
            security = security,
            sni = uri.first("sni", "peer") ?: uri["host"],
            alpn = splitAlpn(uri["alpn"]),
            fingerprint = uri["fp"],
            publicKey = uri.first("pbk", "publickey"),
            shortId = uri.first("sid", "shortid"),
            allowInsecure = uri.first("allowinsecure", "insecure") == "1",
            raw = raw,
        )
    }

    // ---------------------------------------------------------- Shadowsocks

    private fun parseShadowsocks(raw: String): ProxyConfig? {
        var body = raw.substring("ss://".length)
        var name: String? = null
        val hash = body.indexOf('#')
        if (hash >= 0) {
            name = Codecs.urlDecode(body.substring(hash + 1))
            body = body.substring(0, hash)
        }
        var query = ""
        val q = body.indexOf('?')
        if (q >= 0) {
            query = body.substring(q + 1)
            body = body.substring(0, q)
        }
        body = body.trimEnd('/')

        val method: String
        val password: String
        val host: String
        val port: Int

        val at = body.lastIndexOf('@')
        if (at >= 0) {
            // SIP002: userinfo is either base64(method:password) or plain method:password.
            val userInfo = body.substring(0, at)
            val hostPort = LooseUri.splitHostPort(body.substring(at + 1)) ?: return null
            host = hostPort.first
            port = hostPort.second
            val decoded = if (userInfo.contains(':')) {
                Codecs.urlDecode(userInfo)
            } else {
                Codecs.decodeBase64(userInfo) ?: return null
            }
            val colon = decoded.indexOf(':')
            if (colon < 0) return null
            method = decoded.substring(0, colon)
            password = decoded.substring(colon + 1)
        } else {
            // Legacy: the whole payload is base64(method:password@host:port).
            val decoded = Codecs.decodeBase64(body) ?: return null
            val atIndex = decoded.lastIndexOf('@')
            if (atIndex < 0) return null
            val creds = decoded.substring(0, atIndex)
            val hostPort = LooseUri.splitHostPort(decoded.substring(atIndex + 1)) ?: return null
            host = hostPort.first
            port = hostPort.second
            val colon = creds.indexOf(':')
            if (colon < 0) return null
            method = creds.substring(0, colon)
            password = creds.substring(colon + 1)
        }

        // Plugins (v2ray-plugin / obfs) are not represented in our model; the
        // config generators would silently drop them, so reject instead of
        // shipping a server that can never connect.
        if (query.contains("plugin=", ignoreCase = true)) return null

        return ProxyConfig(
            protocol = Protocol.SHADOWSOCKS,
            name = name ?: "$host:$port",
            server = host,
            port = port,
            method = method,
            password = password,
            raw = raw,
        )
    }

    // ------------------------------------------------------------ Hysteria2

    private fun parseHysteria2(raw: String): ProxyConfig? {
        val uri = LooseUri.parse(raw) ?: return null
        val password = uri.userInfo?.let { Codecs.urlDecode(it) }
            ?: uri.first("auth", "password")
            ?: return null
        return ProxyConfig(
            protocol = Protocol.HYSTERIA2,
            name = uri.fragment ?: "${uri.host}:${uri.port}",
            server = uri.host,
            port = uri.port,
            password = password,
            security = "tls",
            sni = uri.first("sni", "peer"),
            alpn = splitAlpn(uri["alpn"]),
            obfs = uri["obfs"]?.takeIf { it.isNotBlank() && it != "none" },
            obfsPassword = uri.first("obfs-password", "obfs_password"),
            upMbps = uri.first("up", "upmbps")?.filter { it.isDigit() }?.toIntOrNull(),
            downMbps = uri.first("down", "downmbps")?.filter { it.isDigit() }?.toIntOrNull(),
            allowInsecure = uri.first("insecure", "allowinsecure") == "1",
            raw = raw,
        )
    }

    // ----------------------------------------------------------------- TUIC

    private fun parseTuic(raw: String): ProxyConfig? {
        val uri = LooseUri.parse(raw) ?: return null
        val userInfo = uri.userInfo ?: return null
        val colon = userInfo.indexOf(':')
        val uuid = if (colon >= 0) userInfo.substring(0, colon) else userInfo
        val password = if (colon >= 0) Codecs.urlDecode(userInfo.substring(colon + 1)) else ""
        return ProxyConfig(
            protocol = Protocol.TUIC,
            name = uri.fragment ?: "${uri.host}:${uri.port}",
            server = uri.host,
            port = uri.port,
            uuid = uuid,
            password = password,
            security = "tls",
            sni = uri.first("sni", "peer"),
            alpn = splitAlpn(uri["alpn"] ?: "h3"),
            congestionControl = uri.first("congestion_control", "congestion-control") ?: "bbr",
            udpRelayMode = uri.first("udp_relay_mode", "udp-relay-mode") ?: "native",
            allowInsecure = uri.first("allow_insecure", "insecure", "allowinsecure") == "1",
            raw = raw,
        )
    }

    // ------------------------------------------------------------ WireGuard

    private fun parseWireGuard(raw: String): ProxyConfig? {
        val uri = LooseUri.parse(raw) ?: return null
        val privateKey = uri.userInfo?.let { Codecs.urlDecode(it) }
            ?: uri.first("privatekey", "secretkey")
            ?: return null
        val addresses = (uri.first("address", "ip", "addresses") ?: "172.16.0.2/32")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val reserved = uri["reserved"]
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 3 }
            ?: emptyList()
        return ProxyConfig(
            protocol = Protocol.WIREGUARD,
            name = uri.fragment ?: "${uri.host}:${uri.port}",
            server = uri.host,
            port = uri.port,
            privateKey = privateKey,
            peerPublicKey = uri.first("publickey", "peer", "pubkey"),
            preSharedKey = uri.first("presharedkey", "psk"),
            localAddresses = addresses,
            reserved = reserved,
            mtu = uri["mtu"]?.toIntOrNull(),
            raw = raw,
        )
    }

    // ----------------------------------------------------------- SOCKS/HTTP

    private fun parseSocksOrHttp(raw: String, protocol: Protocol): ProxyConfig? {
        val uri = LooseUri.parse(raw) ?: return null
        // Bare `http://…` links in scraped text are almost always web pages.
        if (protocol == Protocol.HTTP && uri.userInfo == null) return null
        var user: String? = null
        var pass: String? = null
        uri.userInfo?.let { info ->
            val decoded = Codecs.decodeBase64(info)?.takeIf { it.contains(':') } ?: Codecs.urlDecode(info)
            val colon = decoded.indexOf(':')
            if (colon >= 0) {
                user = decoded.substring(0, colon)
                pass = decoded.substring(colon + 1)
            } else {
                user = decoded
            }
        }
        return ProxyConfig(
            protocol = protocol,
            name = uri.fragment ?: "${uri.host}:${uri.port}",
            server = uri.host,
            port = uri.port,
            uuid = user,
            password = pass,
            security = if (raw.startsWith("https://")) "tls" else "none",
            raw = raw,
        )
    }

    // -------------------------------------------------------------- Helpers

    private fun normalizeNetwork(value: String): String = when (value.lowercase()) {
        "", "tcp", "raw" -> "tcp"
        "ws", "websocket" -> "ws"
        "grpc", "gun" -> "grpc"
        "h2", "http" -> "http"
        "quic" -> "quic"
        "httpupgrade" -> "httpupgrade"
        "xhttp", "splithttp" -> "xhttp"
        "kcp", "mkcp" -> "kcp"
        else -> value.lowercase()
    }

    private fun normalizeSecurity(value: String?): String = when (value?.lowercase()) {
        null, "", "none" -> "none"
        "tls", "xtls" -> "tls"
        "reality" -> "reality"
        "1", "true" -> "tls"
        else -> "none"
    }

    private fun splitAlpn(value: String?): List<String> =
        value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
