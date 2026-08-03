package com.gozar.app.data

object Latency {
    /** Never probed. */
    const val UNTESTED = -1

    /** Probed and unreachable. */
    const val FAILED = -2

    /** Sort weight so healthy servers rise, untested follow, dead sink. */
    const val UNTESTED_WEIGHT = 900_000
    const val FAILED_WEIGHT = 999_999

    /**
     * Where merely-pinged servers start. Any server proven to carry traffic
     * ranks below this, so it outranks every unproven one however fast its
     * handshake was.
     */
    const val UNPROVEN_BASE = 100_000

    /**
     * Ranks a server on what is actually known about it.
     *
     * [realDelay] is a round trip through the proxy over the user's own
     * connection; [ping] is only a TCP handshake to the server's address. The
     * two are not comparable, and mixing them is why a list sorted on ping puts
     * servers at the top that answer in 40ms and then carry nothing: the
     * handshake is with whatever is listening on the port, not with a working
     * proxy. So proven servers occupy the low range in measured order, and
     * unproven ones queue above them ordered by ping.
     */
    fun rank(realDelay: Int, ping: Int): Int = when {
        realDelay > 0 -> realDelay
        realDelay == FAILED -> FAILED_WEIGHT
        ping > 0 -> UNPROVEN_BASE + ping.coerceAtMost(UNTESTED_WEIGHT - UNPROVEN_BASE - 1)
        ping == UNTESTED -> UNTESTED_WEIGHT
        else -> FAILED_WEIGHT
    }
}
