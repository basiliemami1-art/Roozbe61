package com.gozar.app.net

import com.gozar.app.data.GozarDatabase
import com.gozar.app.data.Latency
import com.gozar.app.data.LatencyResult
import com.gozar.app.data.ServerEntity
import com.gozar.app.model.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class TestProgress(val done: Int, val total: Int, val alive: Int)

/**
 * Ranks servers so the fastest float to the top of the list.
 *
 * The sweep is a TCP handshake probe, which is what makes testing thousands of
 * endpoints practical: it needs no proxy core and finishes in seconds. It
 * measures reachability and round-trip time to the endpoint — not end-to-end
 * throughput, and not whether the proxy will actually authenticate. Real
 * end-to-end verification happens at connect time in
 * `GozarVpnService.connectWithFailover`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LatencyTester(
    private val db: GozarDatabase,
    private val concurrency: Int = 64,
    private val timeoutMs: Int = 4_000,
) {

    private val parallelism = concurrency.coerceIn(1, 128)

    /**
     * A private slice of the IO pool. Sharing `Dispatchers.IO` would let a sweep
     * of thousands of blocking socket connects starve every other IO user in the
     * app — including the queries that keep the list on screen responsive.
     */
    private val probeDispatcher = Dispatchers.IO.limitedParallelism(parallelism)

    private val resolved = ConcurrentHashMap<String, InetAddress>()
    private val unresolvable = ConcurrentHashMap<String, Boolean>()

    /**
     * `InetAddress.getByName` has no timeout and is not interruptible, so it
     * runs on its own bounded pool. A hung lookup parks one of these threads
     * instead of a probe coroutine.
     */
    private val resolverPool = Executors.newFixedThreadPool(RESOLVER_THREADS) { runnable ->
        Thread(runnable, "gozar-dns").apply { isDaemon = true }
    }

    suspend fun testAll(
        limit: Int = 2_000,
        onProgress: (TestProgress) -> Unit = {},
    ): Int = withContext(probeDispatcher) {
        val candidates = db.serverDao().testCandidates(limit)
        if (candidates.isEmpty()) return@withContext 0

        val done = AtomicInteger(0)
        val alive = AtomicInteger(0)
        val pending = ArrayList<LatencyResult>(FLUSH_SIZE)
        val pendingLock = Mutex()
        var lastProgressAt = 0L

        suspend fun flush(force: Boolean) {
            val batch = pendingLock.withLock {
                if (!force && pending.size < FLUSH_SIZE) return
                val copy = ArrayList(pending)
                pending.clear()
                copy
            }
            if (batch.isNotEmpty()) {
                // Room invalidates observed queries once per committed
                // transaction, so batching is what keeps the list from being
                // re-queried thousands of times during a sweep.
                runCatching { db.serverDao().updateLatencies(batch) }
            }
        }

        try {
            coroutineScope {
                // The whole sweep is chunked so at most `parallelism` probes are
                // outstanding without needing a semaphore around every launch.
                candidates.chunked(parallelism).forEach { chunk ->
                    chunk.map { server ->
                        async {
                            // A single bad server must never abort the sweep;
                            // an escaping exception used to cancel the scope and
                            // leave the progress bar stuck forever.
                            val latency = runCatching { probe(server) }
                                .getOrDefault(Latency.FAILED)
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
                            if (now - lastProgressAt >= PROGRESS_INTERVAL_MS ||
                                completed == candidates.size
                            ) {
                                lastProgressAt = now
                                onProgress(TestProgress(completed, candidates.size, alive.get()))
                            }
                        }
                    }.awaitAll()
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            flush(force = true)
            onProgress(TestProgress(done.get(), candidates.size, alive.get()))
        }
        alive.get()
    }

    fun shutdown() {
        runCatching { resolverPool.shutdownNow() }
    }

    /** @return latency in milliseconds, or [Latency.FAILED]. */
    fun probe(server: ServerEntity): Int {
        val address = resolve(server.address) ?: return Latency.FAILED
        return when (server.protocolEnum) {
            // QUIC/UDP-based protocols have no handshake we can time without the
            // core, so they are ranked on resolution plus a fixed penalty. They
            // stay comparable to each other and never outrank a measured result.
            Protocol.HYSTERIA2, Protocol.TUIC, Protocol.WIREGUARD ->
                udpReachability(address, server.port)

            else -> tcpConnect(address, server.port)
        }
    }

    private fun tcpConnect(address: InetAddress, port: Int): Int {
        val socket = Socket()
        return try {
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: Throwable) {
            Latency.FAILED
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun udpReachability(address: InetAddress, port: Int): Int = try {
        val start = System.nanoTime()
        java.net.DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(address, port))
        }
        val elapsed = ((System.nanoTime() - start) / 1_000_000L).toInt()
        (elapsed + UDP_RANK_PENALTY_MS).coerceAtLeast(UDP_RANK_PENALTY_MS)
    } catch (_: Throwable) {
        Latency.FAILED
    }

    private fun resolve(host: String): InetAddress? {
        resolved[host]?.let { return it }
        if (unresolvable.containsKey(host)) return null

        val future = runCatching {
            resolverPool.submit(Callable { InetAddress.getByName(host) })
        }.getOrNull() ?: return null

        return try {
            val address = future.get(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // ConcurrentHashMap rejects null values; failures are tracked in a
            // separate set rather than cached as nulls.
            if (address != null) resolved[host] = address else unresolvable[host] = true
            address
        } catch (_: Throwable) {
            future.cancel(true)
            unresolvable[host] = true
            null
        }
    }

    companion object {
        private const val FLUSH_SIZE = 150
        private const val PROGRESS_INTERVAL_MS = 120L
        private const val RESOLVER_THREADS = 16
        private const val RESOLVE_TIMEOUT_MS = 2_500L

        /**
         * Added to UDP-protocol results so an unmeasurable endpoint never wins
         * the "fastest" slot over a server whose RTT we actually observed.
         */
        const val UDP_RANK_PENALTY_MS = 300

        private const val PROBE_URL = "http://cp.cloudflare.com/generate_204"

        /**
         * End-to-end delay through the tunnel that is currently up.
         *
         * The request is sent through the core's loopback inbound rather than
         * directly: this app excludes itself from its own VPN, so a plain
         * request would measure the unproxied path and report a flattering,
         * wrong number.
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
