package com.gozar.app.core

import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Answers whether an address is inside Iran.
 *
 * This exists for one specific situation: when international routing is cut but
 * the domestic network keeps working, the only servers still reachable are the
 * ones whose *entry point* sits inside the country. Those relay onward if they
 * still hold transit, so recognising them is what makes the app usable during a
 * partial shutdown.
 *
 * The range list ships inside the binary and is never downloaded — fetching it
 * would fail in the exact conditions it is needed for.
 */
object IranRanges {

    private const val RESOURCE = "/iran_ipv4.txt"

    // Parallel arrays of inclusive bounds, sorted by start. Primitives, so a
    // lookup during a several-thousand-server sweep costs nothing measurable.
    private var starts: LongArray = LongArray(0)
    private var ends: LongArray = LongArray(0)

    @Volatile
    private var loaded = false

    /** Reads the bundled list. Safe to call repeatedly; only the first does work. */
    @Synchronized
    fun load() {
        if (loaded) return
        val stream: InputStream? = IranRanges::class.java.getResourceAsStream(RESOURCE)
        val bounds = ArrayList<LongArray>(2048)
        stream?.bufferedReader()?.use { reader ->
            reader.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isNotEmpty() && !line.startsWith("#")) {
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
        if (!loaded) load()
        val value = toLong(address.address) ?: return false
        return lookup(value)
    }

    fun contains(dottedQuad: String): Boolean {
        if (!loaded) load()
        val value = parseIpv4(dottedQuad) ?: return false
        return lookup(value)
    }

    private fun lookup(value: Long): Boolean {
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
        val span = 1L shl (32 - bits)
        val start = base and (span - 1).inv()
        return longArrayOf(start, start + span - 1)
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
