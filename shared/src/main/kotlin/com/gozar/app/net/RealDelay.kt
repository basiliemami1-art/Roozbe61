package com.gozar.app.net

import com.gozar.app.data.Latency
import com.gozar.app.model.Protocol
import com.gozar.app.model.ProxyConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Measures how long a real request takes *through* each proxy.
 *
 * This is the number that decides which server is actually good. The bulk
 * sweep in [Prober] only opens a TCP connection to the server's address: it
 * proves something is listening on that port, nothing more. Servers that
 * answer a handshake in 40ms and then fail at TLS or authentication are
 * extremely common in scraped lists, and sorting on that handshake puts them
 * at the very top.
 *
 * So a running core is asked instead. sing-box's Clash API exposes
 * `/proxies/{tag}/delay`, which performs an HTTPS request through one specific
 * outbound and reports the round trip. The core's own dials go over the user's
 * ordinary connection, which is exactly the path the tunnel will take.
 *
 * It is expensive — a full handshake and request per server — so it is meant
 * for a shortlist, not for the whole list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object RealDelay {

    /**
     * Must be https. sing-box blanks any `http://` url and silently falls back
     * to its own default, so an http probe would not measure what was asked for.
     */
    const val TEST_URL = "https://www.gstatic.com/generate_204"

    /** The API parses this as an int16, so it cannot exceed 32767. */
    const val TIMEOUT_MS = 5_000

    data class Outcome(val id: Long, val delayMs: Int)

    data class Progress(val done: Int, val total: Int, val alive: Int)

    /** The tag a server is given in the tester config. */
    fun tagFor(id: Long): String = "s$id"

    /**
     * WireGuard — and therefore WARP — is configured as an endpoint rather than
     * an outbound, and the Clash API resolves tags through the outbound manager
     * only. Those servers keep their handshake ranking.
     */
    fun testable(protocol: String): Boolean = protocol != Protocol.WIREGUARD.name

    /**
     * @param candidates id to config, already shortlisted by the caller.
     * @return the measurements, in completion order.
     */
    suspend fun measureAll(
        apiPort: Int,
        candidates: List<Pair<Long, ProxyConfig>>,
        concurrency: Int = 8,
        onResult: suspend (Outcome) -> Unit = {},
        onProgress: (Progress) -> Unit = {},
    ): List<Outcome> {
        if (candidates.isEmpty()) return emptyList()
        // A client that never proxies: these requests go to the core's own API
        // on loopback, and a system proxy set by this very app would otherwise
        // send them into the tunnel.
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout((TIMEOUT_MS + 3_000).toLong(), TimeUnit.MILLISECONDS)
            .callTimeout((TIMEOUT_MS + 5_000).toLong(), TimeUnit.MILLISECONDS)
            .build()

        val done = AtomicInteger(0)
        val alive = AtomicInteger(0)
        val results = ArrayList<Outcome>(candidates.size)

        try {
            if (!awaitApi(client, apiPort)) return emptyList()
            coroutineScope {
                candidates.chunked(concurrency).forEach { chunk ->
                    chunk.map { (id, _) ->
                        async(Dispatchers.IO) {
                            val delayMs = query(client, apiPort, tagFor(id))
                            val outcome = Outcome(id, delayMs)
                            if (delayMs > 0) alive.incrementAndGet()
                            synchronized(results) { results.add(outcome) }
                            runCatching { onResult(outcome) }
                            onProgress(
                                Progress(done.incrementAndGet(), candidates.size, alive.get()),
                            )
                        }
                    }.awaitAll()
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            runCatching { client.dispatcher.executorService.shutdown() }
            runCatching { client.connectionPool.evictAll() }
        }
        return results
    }

    /** The core needs a moment after start before its API answers. */
    private suspend fun awaitApi(client: OkHttpClient, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            repeat(API_ATTEMPTS) { attempt ->
                val ok = runCatching {
                    val request = Request.Builder()
                        .url("http://127.0.0.1:$port/version")
                        .build()
                    client.newCall(request).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (ok) return@withContext true
                delay(API_POLL_MS)
                if (attempt == API_ATTEMPTS - 1) return@withContext false
            }
            false
        }

    /** @return the round trip in ms, or [Latency.FAILED]. */
    private fun query(client: OkHttpClient, port: Int, tag: String): Int = runCatching {
        val url = "http://127.0.0.1:$port/proxies/$tag/delay" +
            "?timeout=$TIMEOUT_MS&url=${encode(TEST_URL)}"
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            // 503 and 504 are how the API reports a proxy that did not answer;
            // both mean the same thing here.
            if (!response.isSuccessful) return@runCatching Latency.FAILED
            val body = response.body?.string().orEmpty()
            val delay = json.parseToJsonElement(body)
                .jsonObject["delay"]?.jsonPrimitive?.intOrNull
                ?: return@runCatching Latency.FAILED
            if (delay > 0) delay else Latency.FAILED
        }
    }.getOrDefault(Latency.FAILED)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private const val API_ATTEMPTS = 20
    private const val API_POLL_MS = 250L

    /** Kept for the caller: the loopback address the API listens on. */
    fun apiBase(port: Int): String = "http://127.0.0.1:$port"
}
