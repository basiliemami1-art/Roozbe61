package com.gozar.app.data

object Latency {
    /** Never probed. */
    const val UNTESTED = -1

    /** Probed and unreachable. */
    const val FAILED = -2

    /** Sort weight so healthy servers rise, untested follow, dead sink. */
    const val UNTESTED_WEIGHT = 900_000
    const val FAILED_WEIGHT = 999_999

    fun weightFor(latency: Int): Int = when {
        latency > 0 -> latency
        latency == UNTESTED -> UNTESTED_WEIGHT
        else -> FAILED_WEIGHT
    }
}
