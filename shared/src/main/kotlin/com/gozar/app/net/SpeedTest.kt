package com.gozar.app.net

import com.gozar.app.core.SingBoxConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Measures how fast a server actually moves bytes.
 *
 * This is the number that answers "why is it slow", and nothing else does.
 * A handshake says something is listening. A round trip through the proxy says
 * it works and how responsive it is. Neither says anything about bandwidth —
 * and the free servers these lists are full of are shared by thousands of
 * people, so the ones that answer fastest are frequently the ones with nothing
 * left to give. Hiddify has the same blind spot: its balancer ranks on
 * `lowest-delay` too.
 *
 * So a bounded download is put through each candidate and timed.
 *
 * **Sequentially, always.** Run two of these at once and they split the user's
 * own line between them, so both read as half as fast as they are and the
 * ranking becomes noise. This is the one stage that must not be parallel.
 */
object SpeedTest {

    /**
     * Cloudflare's own speed endpoint: it streams exactly as many bytes as it
     * is asked for, from a network with enough capacity that the proxy is
     * reliably the bottleneck rather than the far end.
     */
    private const val URL = "https://speed.cloudflare.com/__down?bytes=20000000"

    /** Stop at whichever comes first — that bounds both time and the user's data. */
    private const val MAX_BYTES = 1_500_000L
    private const val MAX_MILLIS = 2_500L

    /** Below this the reading is noise rather than a measurement. */
    private const val MIN_BYTES = 32_768L

    data class Outcome(val id: Long, val kbPerSecond: Int)

    data class Progress(val done: Int, val total: Int, val best: Int)

    /**
     * @param proxyPort the tester core's own inbound, which the selector points
     *   at one candidate at a time.
     * @param candidates ids in the order they should be tried, best first.
     */
    suspend fun measureAll(
        apiPort: Int,
        proxyPort: Int,
        candidates: List<Long>,
        onResult: suspend (Outcome) -> Unit = {},
        onProgress: (Progress) -> Unit = {},
    ): List<Outcome> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptyList()

        val control = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(3, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
        // Through the core's inbound. A fresh client per run would re-handshake
        // every time; one client with the pool evicted between servers keeps the
        // measurement honest without paying for a new TLS session each round.
        val through = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort)))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout((MAX_MILLIS + 8_000).toLong(), TimeUnit.MILLISECONDS)
            .build()

        val results = ArrayList<Outcome>(candidates.size)
        var best = 0
        try {
            for ((index, id) in candidates.withIndex()) {
                if (!select(control, apiPort, RealDelay.tagFor(id))) {
                    results += Outcome(id, 0)
                    onProgress(Progress(index + 1, candidates.size, best))
                    continue
                }
                // The previous server's sockets are still pooled and would be
                // reused for this one's download, measuring the wrong proxy.
                runCatching { through.connectionPool.evictAll() }

                val speed = download(through)
                val outcome = Outcome(id, speed)
                results += outcome
                if (speed > best) best = speed
                runCatching { onResult(outcome) }
                onProgress(Progress(index + 1, candidates.size, best))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            for (client in listOf(control, through)) {
                runCatching { client.dispatcher.executorService.shutdown() }
                runCatching { client.connectionPool.evictAll() }
            }
        }
        results
    }

    /** Points the tester's selector at one member. */
    private fun select(client: OkHttpClient, apiPort: Int, tag: String): Boolean = runCatching {
        val body = """{"name":"$tag"}"""
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("http://127.0.0.1:$apiPort/proxies/${SingBoxConfig.SELECTOR_TAG}")
            .put(body)
            .build()
        client.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** @return KB/s, or 0 when nothing usable came through. */
    private fun download(client: OkHttpClient): Int = runCatching {
        val request = Request.Builder().url(URL).header("User-Agent", Http.USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching 0
            val source = response.body?.byteStream() ?: return@runCatching 0
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            val start = System.nanoTime()
            var elapsed = 0L
            while (total < MAX_BYTES) {
                val read = try {
                    source.read(buffer)
                } catch (_: IOException) {
                    break
                }
                if (read <= 0) break
                total += read
                elapsed = (System.nanoTime() - start) / 1_000_000
                if (elapsed >= MAX_MILLIS) break
            }
            if (total < MIN_BYTES || elapsed <= 0) return@runCatching 0
            // KB/s, from what actually arrived in the time it actually took.
            ((total * 1000L) / (elapsed * 1024L)).toInt().coerceAtLeast(1)
        }
    }.getOrDefault(0)
}
