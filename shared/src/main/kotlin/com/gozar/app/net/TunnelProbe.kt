package com.gozar.app.net

import com.gozar.app.data.Latency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Proves a tunnel actually carries traffic.
 *
 * A handshake probe only shows that something is listening; plenty of scraped
 * configs accept a connection and then fail at TLS or authentication, which
 * surfaces to the user as "connected, but nothing loads". So before a server is
 * accepted, one real request is sent *through the core's own loopback inbound*.
 */
object TunnelProbe {

    private const val PROBE_URL = "http://cp.cloudflare.com/generate_204"

    data class Result(val delayMs: Int, val error: String?)

    /** True when something is accepting connections on the core's inbound. */
    suspend fun inboundIsUp(port: Int, attempts: Int = 3): Boolean = withContext(Dispatchers.IO) {
        repeat(attempts) { attempt ->
            val socket = java.net.Socket()
            try {
                socket.connect(InetSocketAddress("127.0.0.1", port), 1_500)
                return@withContext true
            } catch (_: Throwable) {
                if (attempt < attempts - 1) Thread.sleep(400)
            } finally {
                runCatching { socket.close() }
            }
        }
        false
    }

    suspend fun measure(localProxyPort: Int): Result = withContext(Dispatchers.IO) {
        if (localProxyPort <= 0) return@withContext Result(Latency.FAILED, "no proxy port")
        // A fresh client: a shared one carries a connection pool whose existing
        // connections would not go through this proxy.
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localProxyPort)))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(PROBE_URL)
            .header("User-Agent", Http.USER_AGENT)
            .build()
        try {
            val start = System.nanoTime()
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..399) {
                    return@withContext Result(Latency.FAILED, "HTTP ${response.code}")
                }
            }
            Result(((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1), null)
        } catch (error: Throwable) {
            // Never swallow this: the reason a probe fails is the whole
            // diagnosis, and discarding it once cost five rounds of debugging.
            Result(Latency.FAILED, "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { client.dispatcher.executorService.shutdown() }
            runCatching { client.connectionPool.evictAll() }
        }
    }
}
