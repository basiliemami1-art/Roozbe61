package com.gozar.app.net

import com.gozar.app.core.IranRanges
import com.gozar.app.data.Latency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ranks servers so the fastest float to the top.
 *
 * The sweep is a TCP handshake probe, which is what makes testing thousands of
 * endpoints practical: it needs no proxy core and finishes in seconds. It
 * measures reachability and round-trip time — not throughput, and not whether
 * the proxy will authenticate. Real end-to-end verification happens at connect
 * time, once, against the server actually chosen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Prober(
    concurrency: Int = 64,
    private val timeoutMs: Int = 4_000,
) {

    data class Target(val id: Long, val host: String, val port: Int, val udpOnly: Boolean)

    data class Outcome(val id: Long, val latencyMs: Int, val domesticEntry: Boolean)

    data class Progress(val done: Int, val total: Int, val alive: Int)

    private val parallelism = concurrency.coerceIn(1, 128)

    /**
     * A private slice of the IO pool. Sharing `Dispatchers.IO` would let a sweep
     * of thousands of blocking connects starve every other IO user — including
     * whatever keeps the list on screen responsive.
     */
    private val dispatcher = Dispatchers.IO.limitedParallelism(parallelism)

    private val resolved = ConcurrentHashMap<String, InetAddress>()
    private val unresolvable = ConcurrentHashMap<String, Boolean>()

    /**
     * `InetAddress.getByName` has no timeout and is not interruptible, so it
     * runs on its own bounded pool: a hung lookup parks one of these threads
     * instead of a probe coroutine.
     */
    private val resolverPool = Executors.newFixedThreadPool(RESOLVER_THREADS) { runnable ->
        Thread(runnable, "gozar-dns").apply { isDaemon = true }
    }

    /**
     * @param onBatch receives results in batches so the caller can write them in
     *   one transaction rather than one row at a time.
     * @return how many targets answered.
     */
    suspend fun sweep(
        targets: List<Target>,
        onBatch: suspend (List<Outcome>) -> Unit,
        onProgress: (Progress) -> Unit = {},
    ): Int = withContext(dispatcher) {
        if (targets.isEmpty()) return@withContext 0

        val done = AtomicInteger(0)
        val alive = AtomicInteger(0)
        val pending = ArrayList<Outcome>(FLUSH_SIZE)
        val lock = Mutex()
        var lastProgressAt = 0L

        suspend fun flush(force: Boolean) {
            val batch = lock.withLock {
                if (!force && pending.size < FLUSH_SIZE) return
                val copy = ArrayList(pending)
                pending.clear()
                copy
            }
            if (batch.isNotEmpty()) runCatching { onBatch(batch) }
        }

        try {
            coroutineScope {
                targets.chunked(parallelism).forEach { chunk ->
                    chunk.map { target ->
                        async {
                            // A single bad target must never abort the sweep.
                            val outcome = runCatching { probe(target) }
                                .getOrDefault(Outcome(target.id, Latency.FAILED, false))
                            if (outcome.latencyMs > 0) alive.incrementAndGet()
                            lock.withLock { pending.add(outcome) }
                            flush(force = false)

                            val completed = done.incrementAndGet()
                            val now = System.currentTimeMillis()
                            if (now - lastProgressAt >= PROGRESS_INTERVAL_MS ||
                                completed == targets.size
                            ) {
                                lastProgressAt = now
                                onProgress(Progress(completed, targets.size, alive.get()))
                            }
                        }
                    }.awaitAll()
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            flush(force = true)
            onProgress(Progress(done.get(), targets.size, alive.get()))
        }
        alive.get()
    }

    fun probe(target: Target): Outcome {
        val address = resolve(target.host)
            ?: return Outcome(target.id, Latency.FAILED, false)
        // Classified from the address the host actually resolves to: a .ir
        // domain is usually fronted by a foreign CDN, and plenty of domestic
        // nodes sit behind neutral-looking names.
        val domestic = IranRanges.contains(address)
        val latency = if (target.udpOnly) {
            udpReachability(address, target.port)
        } else {
            tcpConnect(address, target.port)
        }
        return Outcome(target.id, latency, domestic)
    }

    fun shutdown() {
        runCatching { resolverPool.shutdownNow() }
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

    /**
     * QUIC/UDP protocols have no handshake we can time without the core, so they
     * are ranked on resolution plus a fixed penalty: comparable to each other,
     * and never outranking a result we actually measured.
     */
    private fun udpReachability(address: InetAddress, port: Int): Int = try {
        val start = System.nanoTime()
        DatagramSocket().use { socket ->
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
            // ConcurrentHashMap rejects null values, so failures are tracked in
            // a separate set rather than cached as nulls — doing otherwise threw
            // an NPE that killed entire sweeps.
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

        const val UDP_RANK_PENALTY_MS = 300
    }
}
