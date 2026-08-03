package com.gozar.app.core

import android.content.Context
import com.gozar.app.R
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Answers whether an address is inside Iran.
 *
 * This exists for one specific situation: when international routing is cut but
 * the domestic network keeps working, the only servers still reachable are the
 * ones whose *entry point* sits inside the country. Those relay onward if they
 * still hold transit, so being able to recognise them is what makes the app
 * usable during a partial shutdown.
 *
 * The range list is bundled, never downloaded — fetching it would fail in the
 * exact conditions it is needed for.
 */
object IranRanges {

    // Parallel arrays of inclusive range bounds, sorted by start. Kept as
    // primitives so a lookup during a several-thousand-server sweep costs
    // nothing measurable.
    private var starts: LongArray = LongArray(0)
    private var ends: LongArray = LongArray(0)

    @Volatile
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val bounds = ArrayList<LongArray>(2048)
        runCatching {
            context.resources.openRawResource(R.raw.iran_ipv4).bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    parseCidr(line)?.let { bounds.add(it) }
                }
            }
        }
        bounds.sortBy { it[0] }
        starts = LongArray(bounds.size) { bounds[it][0] }
        ends = LongArray(bounds.size) { bounds[it][1] }
        loaded = true
    }

    val size: Int get() = starts.size

    fun contains(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val value = toLong(address.address) ?: return false
        return contains(value)
    }

    fun contains(dottedQuad: String): Boolean {
        val value = parseIpv4(dottedQuad) ?: return false
        return contains(value)
    }

    private fun contains(value: Long): Boolean {
        var low = 0
        var high = starts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                value < starts[mid] -> high = mid - 1
                value > ends[mid] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    private fun parseCidr(line: String): LongArray? {
        val slash = line.indexOf('/')
        if (slash <= 0) return null
        val base = parseIpv4(line.substring(0, slash)) ?: return null
        val bits = line.substring(slash + 1).toIntOrNull() ?: return null
        if (bits !in 0..32) return null
        val size = 1L shl (32 - bits)
        val start = base and (size - 1).inv()
        return longArrayOf(start, start + size - 1)
    }

    private fun parseIpv4(text: String): Long? {
        var value = 0L
        var octet = 0
        var digits = 0
        var parts = 0
        for (char in text) {
            when {
                char in '0'..'9' -> {
                    octet = octet * 10 + (char - '0')
                    if (octet > 255) return null
                    digits++
                }

                char == '.' -> {
                    if (digits == 0) return null
                    value = (value shl 8) or octet.toLong()
                    octet = 0
                    digits = 0
                    parts++
                    if (parts > 3) return null
                }

                else -> return null
            }
        }
        if (digits == 0 || parts != 3) return null
        return (value shl 8) or octet.toLong()
    }

    private fun toLong(bytes: ByteArray): Long? {
        if (bytes.size != 4) return null
        var value = 0L
        for (byte in bytes) value = (value shl 8) or (byte.toLong() and 0xFF)
        return value
    }
}
