package com.gozar.app.net

import com.gozar.app.data.GozarDatabase
import com.gozar.app.data.Latency
import com.gozar.app.data.LatencyResult
import com.gozar.app.model.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TestProgress(val done: Int, val total: Int, val alive: Int)

/**
 * Runs a latency sweep over the stored servers.
 *
 * The probing itself lives in [Prober], shared with the desktop app; this is
 * only the part that knows about Room — which rows to test and how to write the
 * results back.
 */
class LatencyTester(
    private val db: GozarDatabase,
    concurrency: Int = 64,
    timeoutMs: Int = 4_000,
) {

    private val prober = Prober(concurrency = concurrency, timeoutMs = timeoutMs)

    suspend fun testAll(
        limit: Int = 2_000,
        onProgress: (TestProgress) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val candidates = db.serverDao().testCandidates(limit)
        if (candidates.isEmpty()) return@withContext 0

        val targets = candidates.map { server ->
            Prober.Target(
                id = server.id,
                host = server.address,
                port = server.port,
                // QUIC-based protocols have no TCP handshake to time.
                udpOnly = server.protocolEnum in UDP_PROTOCOLS,
            )
        }

        prober.sweep(
            targets = targets,
            onBatch = { batch ->
                // One transaction per batch: Room invalidates observed queries
                // per commit, and writing row-by-row re-ran every list query
                // thousands of times over, which locked the UI.
                db.serverDao().updateLatencies(
                    batch.map { outcome ->
                        LatencyResult(
                            id = outcome.id,
                            latency = outcome.latencyMs,
                            weight = Latency.weightFor(outcome.latencyMs),
                            domesticEntry = outcome.domesticEntry,
                        )
                    },
                )
            },
            onProgress = { onProgress(TestProgress(it.done, it.total, it.alive)) },
        )
    }

    fun shutdown() = prober.shutdown()

    companion object {
        private val UDP_PROTOCOLS =
            setOf(Protocol.HYSTERIA2, Protocol.TUIC, Protocol.WIREGUARD)

        /**
         * End-to-end delay through the tunnel that is currently up, measured
         * through the core's loopback inbound rather than directly.
         */
        suspend fun measureTunnelDelay(localProxyPort: Int): Int {
            val result = TunnelProbe.measure(localProxyPort)
            if (result.error != null) {
                com.gozar.app.vpn.Diagnostics.log("probe failed: ${result.error}")
            }
            return result.delayMs
        }
    }
}
