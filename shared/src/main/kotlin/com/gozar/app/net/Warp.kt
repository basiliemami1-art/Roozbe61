package com.gozar.app.net

import com.gozar.app.model.ProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Cloudflare WARP.
 *
 * WARP is WireGuard, but the keys are issued rather than published: a config
 * with someone else's key would be an account shared by every user of this app.
 * So a free account is registered on demand — the same call the official client
 * makes on first run — and the link in a subscription only carries the endpoint
 * to reach it on.
 *
 * The account lives for the life of the process and is not persisted. It costs
 * one request to obtain, it is worthless to keep, and re-registering avoids
 * having to store a private key on disk.
 */
object Warp {

    /** Hosts that mean "no endpoint given"; the suffix is an address-family hint. */
    val AUTO_HOSTS = setOf("auto", "auto4", "auto6")

    /** One of Cloudflare's anycast WARP addresses, used to resolve `auto`. */
    const val DEFAULT_ENDPOINT = "162.159.192.1"

    private const val REGISTER_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val CLIENT_VERSION = "a-6.30-3596"

    /** WARP's own client uses 1280; larger and handshakes start disappearing. */
    const val MTU = 1280

    data class Account(
        val privateKey: String,
        val peerPublicKey: String,
        val host: String,
        val port: Int,
        val addresses: List<String>,
        val reserved: List<Int>,
    )

    class UnavailableException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private val lock = Mutex()
    private var cached: Account? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Registers once per process; concurrent callers share the one account. */
    suspend fun account(): Account = lock.withLock {
        cached ?: register().also { cached = it }
    }

    /**
     * Fills in the credentials a `warp://` link deliberately omits. Anything
     * else is returned untouched.
     *
     * The endpoint is left alone: the parser has already settled it, and the
     * Iranian lists publish specific addresses that survive local filtering
     * where the one Cloudflare hands out does not.
     */
    suspend fun fill(proxy: ProxyConfig): ProxyConfig {
        if (!proxy.warp) return proxy
        val account = account()
        return proxy.copy(
            privateKey = account.privateKey,
            peerPublicKey = account.peerPublicKey,
            localAddresses = account.addresses,
            reserved = account.reserved,
            mtu = proxy.mtu ?: MTU,
        )
    }

    private suspend fun register(): Account = withContext(Dispatchers.IO) {
        val (privateKey, publicKey) = keyPair()
        val body = buildJsonObject {
            put("install_id", "")
            put("fcm_token", "")
            put("tos", timestamp())
            put("key", publicKey)
            put("model", "PC")
            put("type", "Android")
            put("locale", "en_US")
        }.toString()

        val request = Request.Builder()
            .url(REGISTER_URL)
            .header("CF-Client-Version", CLIENT_VERSION)
            .header("User-Agent", "okhttp/3.12.1")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val payload = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UnavailableException("WARP registration returned HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
        parse(payload, privateKey)
    }

    private fun parse(payload: String, privateKey: String): Account {
        val config = runCatching {
            json.parseToJsonElement(payload).jsonObject["config"]!!.jsonObject
        }.getOrElse { throw UnavailableException("WARP returned an unreadable account", it) }

        val peer = config["peers"]!!.jsonArray.first().jsonObject
        val endpoint = peer["endpoint"]!!.jsonObject["host"]!!.jsonPrimitive.content
        val addresses = config["interface"]!!.jsonObject["addresses"]!!.jsonObject
        return Account(
            privateKey = privateKey,
            peerPublicKey = peer["public_key"]!!.jsonPrimitive.content,
            host = endpoint.substringBeforeLast(':'),
            port = endpoint.substringAfterLast(':').toIntOrNull() ?: 2408,
            addresses = listOfNotNull(
                addresses.string("v4")?.let { "$it/32" },
                addresses.string("v6")?.let { "$it/128" },
            ),
            // `client_id` is three bytes of base64 that every WARP packet has to
            // carry; without them the peer answers nothing at all.
            reserved = Base64.getDecoder()
                .decode(config["client_id"]!!.jsonPrimitive.content)
                .map { it.toInt() and 0xFF },
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    /**
     * An X25519 pair, encoded the way WireGuard expects.
     *
     * Android only gained the `XDH` provider in API 33, so on older phones this
     * throws and WARP is simply unavailable — everything else keeps working.
     */
    private fun keyPair(): Pair<String, String> = try {
        val generator = KeyPairGenerator.getInstance("XDH")
        generator.initialize(NamedParameterSpec.X25519)
        val pair = generator.generateKeyPair()
        val secret = (pair.private as XECPrivateKey).scalar
            .orElseThrow { UnavailableException("the platform hid the private scalar") }
        val public = (pair.public as XECPublicKey).u
        encode(secret) to encode(littleEndian(public))
    } catch (error: UnavailableException) {
        throw error
    } catch (error: Throwable) {
        throw UnavailableException("this system cannot generate X25519 keys", error)
    }

    /**
     * WireGuard encodes the public u-coordinate as 32 little-endian bytes.
     * BigInteger hands it back big-endian, without leading zeros, and sometimes
     * with an extra sign byte — so it is copied out one byte at a time from the
     * least significant end rather than reversed wholesale.
     */
    private fun littleEndian(value: BigInteger): ByteArray {
        val bigEndian = value.toByteArray()
        val out = ByteArray(32)
        for (index in 0 until minOf(32, bigEndian.size)) {
            out[index] = bigEndian[bigEndian.size - 1 - index]
        }
        return out
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.000'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
}
