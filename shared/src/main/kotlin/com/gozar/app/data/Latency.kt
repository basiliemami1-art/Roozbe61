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
     * The ranking is banded, best band first: servers whose throughput was
     * measured, then those proven to carry traffic, then those that merely
     * answered a handshake. A server never loses to one that is known less
     * well about, however good the weaker number looked.
     */
    const val MEASURED_BASE = 1
    const val PROVEN_BASE = 50_000
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
    fun rank(realDelay: Int, ping: Int): Int = rank(UNTESTED, realDelay, ping)

    /**
     * @param speedKb throughput in KB/s, measured by downloading through the
     *   proxy. When it is known it decides the order outright — it is the only
     *   figure that answers "is this server actually fast", which is what the
     *   user is choosing on. A server can answer in 40ms and still be shared by
     *   enough people that nothing is left of its bandwidth.
     */
    fun rank(speedKb: Int, realDelay: Int, ping: Int): Int = when {
        // Inverted so faster sorts first, and floored so even a very fast
        // server stays inside its band rather than colliding with the next.
        speedKb > 0 -> (SPEED_SCALE / speedKb).coerceIn(MEASURED_BASE, PROVEN_BASE - 1)
        speedKb == FAILED -> FAILED_WEIGHT
        realDelay > 0 -> PROVEN_BASE + realDelay.coerceAtMost(UNPROVEN_BASE - PROVEN_BASE - 1)
        realDelay == FAILED -> FAILED_WEIGHT
        ping > 0 -> UNPROVEN_BASE + ping.coerceAtMost(UNTESTED_WEIGHT - UNPROVEN_BASE - 1)
        ping == UNTESTED -> UNTESTED_WEIGHT
        else -> FAILED_WEIGHT
    }

    /** 10 MB/s lands at weight 1; 10 KB/s at 1000. */
    private const val SPEED_SCALE = 10_000
}
