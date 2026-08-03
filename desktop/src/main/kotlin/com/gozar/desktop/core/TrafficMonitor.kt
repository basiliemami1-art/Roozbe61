package com.gozar.desktop.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Live throughput, read from the core's own counters.
 *
 * sing-box's Clash API streams a JSON object per second on `/traffic`, holding
 * the bytes moved in that second. Reading it beats measuring at our end: the
 * desktop app never sees the traffic, which flows from the browser straight
 * into the core.
 *
 * The stream is a long-lived response body, so it is read line by line and only
 * ends when the call is cancelled or the core exits.
 */
class TrafficMonitor(private val port: Int) {

    data class Sample(val upBytes: Long, val downBytes: Long)

    // No read timeout: an idle tunnel legitimately sends nothing for minutes,
    // and a timeout would tear the stream down and look like a disconnection.
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun stream(onSample: (Sample) -> Unit) = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder()
            .url("http://127.0.0.1:$port/traffic")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext
                val source = response.body?.source() ?: return@withContext
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    parse(line)?.let(onSample)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            // The core exiting closes the stream; that is a normal disconnect,
            // not something worth surfacing.
        }
    }

    fun shutdown() {
        runCatching { client.dispatcher.cancelAll() }
        runCatching { client.connectionPool.evictAll() }
    }

    /**
     * Hand-read rather than deserialised: the payload is two integers, and the
     * stream must survive a field being added on the core's side.
     */
    private fun parse(line: String): Sample? {
        val up = numberAfter(line, "\"up\"")
        val down = numberAfter(line, "\"down\"")
        if (up == null && down == null) return null
        return Sample(up ?: 0, down ?: 0)
    }

    private fun numberAfter(line: String, key: String): Long? {
        val at = line.indexOf(key)
        if (at < 0) return null
        val colon = line.indexOf(':', at + key.length)
        if (colon < 0) return null
        val digits = line.drop(colon + 1).trimStart().takeWhile { it.isDigit() }
        return digits.toLongOrNull()
    }
}
