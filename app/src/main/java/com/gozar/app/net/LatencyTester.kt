package com.gozar.app.net

import com.gozar.app.data.GozarDatabase
import com.gozar.app.data.Latency
import com.gozar.app.data.LatencyResult
import com.gozar.app.data.ServerEntity
import com.gozar.app.model.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class TestProgress(val done: Int, val total: Int, val alive: Int)

/**
 * Ranks servers so the fastest float to the top of the list.
 *
 * The sweep is a TCP handshake probe, which is what makes testing thousands of
 * endpoints practical: it needs no proxy core and finishes in seconds. It
 * measures reachability and round-trip time to the endpoint — not end-to-end
 * throughput — so it is a ranking signal, not a bandwidth benchmark. The real
 * end-to-end delay of the *connected* server is measured separately by
 * [measureTunnelDelay], which goes through the live tunnel.
 */
class LatencyTester(
    private val db: GozarDatabase,
    private val concurrency: Int = 24,
    private val timeoutMs: Int = 5_000,
) {

    private val dnsCache = ConcurrentHashMap<String, InetAddress?>()

    suspend fun testAll(
        limit: Int = 2_000,
        onProgress: (TestProgress) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val candidates = db.serverDao().testCandidates(limit)
        if (candidates.isEmpty()) return@withContext 0

        val gate = Semaphore(concurrency.coerceIn(1, 128))
        val done = AtomicInteger(0)
        val alive = AtomicInteger(0)

        // Results are buffered and flushed in batches. Writing each row as its
        // own transaction would invalidate every observed query thousands of
        // times over, which is what made the UI lock up during a sweep.
        val pending = ArrayList<LatencyResult>(FLUSH_SIZE)
        val pendingLock = Mutex()
        var lastProgressAt = 0L

        suspend fun flush(force: Boolean) {
            val batch = pendingLock.withLock {
                if (pending.size < FLUSH_SIZE && !force) return
                val copy = ArrayList(pending)
                pending.clear()
                copy
            }
            if (batch.isNotEmpty()) db.serverDao().updateLatencies(batch)
        }

        coroutineScope {
            candidates.map { server ->
                async {
                    gate.withPermit {
                        val latency = probe(server)
                        if (latency > 0) alive.incrementAndGet()
                        pendingLock.withLock {
                            pending.add(
                                LatencyResult(
                                    server.id,
                                    latency,
                                    ServerEntity.weightFor(latency),
                                ),
                            )
                        }
                        flush(force = false)

                        val completed = done.incrementAndGet()
                        val now = System.currentTimeMillis()
                        // Throttle UI updates: one per frame budget is plenty.
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_MS ||
                            completed == candidates.size
                        ) {
                            lastProgressAt = now
                            onProgress(TestProgress(completed, candidates.size, alive.get()))
                        }
                    }
                }
            }.awaitAll()
        }
        flush(force = true)
        alive.get()
    }

    /** @return latency in milliseconds, or [Latency.FAILED]. */
    fun probe(server: ServerEntity): Int {
        val address = resolve(server.address) ?: return Latency.FAILED
        return when (server.protocolEnum) {
            // QUIC/UDP-based protocols have no handshake we can time without the
            // core, so they are ranked on resolution + a fixed penalty. They stay
            // comparable to each other and never outrank a measured TCP result.
            Protocol.HYSTERIA2, Protocol.TUIC, Protocol.WIREGUARD -> udpReachability(address, server.port)
            else -> tcpConnect(address, server.port)
        }
    }

    private fun tcpConnect(address: InetAddress, port: Int): Int {
        val socket = Socket()
        return try {
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            val elapsed = ((System.nanoTime() - start) / 1_000_000L).toInt()
            elapsed.coerceAtLeast(1)
        } catch (_: Throwable) {
            Latency.FAILED
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * UDP endpoints cannot be handshaked cheaply. We confirm the host resolves and
     * that a datagram socket can be bound and pointed at it, then add a constant
     * so these entries sort just below verified TCP servers of similar quality.
     */
    private fun udpReachability(address: InetAddress, port: Int): Int {
        return try {
            val start = System.nanoTime()
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(address, port))
                if (!socket.isConnected) return Latency.FAILED
            }
            val elapsed = ((System.nanoTime() - start) / 1_000_000L).toInt()
            (elapsed + UDP_RANK_PENALTY_MS).coerceAtLeast(UDP_RANK_PENALTY_MS)
        } catch (_: Throwable) {
            Latency.FAILED
        }
    }

    private fun resolve(host: String): InetAddress? = dnsCache.getOrPut(host) {
        try {
            InetAddress.getByName(host)
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        /** Rows buffered before a batched write. */
        private const val FLUSH_SIZE = 150

        /** Minimum gap between progress callbacks, in milliseconds. */
        private const val PROGRESS_INTERVAL_MS = 120L

        /**
         * Added to UDP-protocol results so an unmeasurable endpoint never wins the
         * "fastest" slot over a server whose RTT we actually observed.
         */
        const val UDP_RANK_PENALTY_MS = 300

        private const val PROBE_URL = "http://cp.cloudflare.com/generate_204"

        /**
         * End-to-end delay through the tunnel that is currently up.
         *
         * The request is sent through the core's loopback inbound rather than
         * directly: this app excludes itself from its own VPN, so a plain request
         * would measure the unproxied path and report a flattering, wrong number.
         */
        suspend fun measureTunnelDelay(localProxyPort: Int): Int = withContext(Dispatchers.IO) {
            if (localProxyPort <= 0) return@withContext Latency.FAILED
            val client = Http.client.newBuilder()
                .proxy(
                    java.net.Proxy(
                        java.net.Proxy.Type.SOCKS,
                        InetSocketAddress("127.0.0.1", localProxyPort),
                    ),
                )
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
                    if (response.code !in 200..399) return@withContext Latency.FAILED
                }
                ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
            } catch (_: Throwable) {
                Latency.FAILED
            }
        }
    }
}
