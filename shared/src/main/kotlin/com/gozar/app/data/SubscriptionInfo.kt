package com.gozar.app.data

/**
 * The quota a paid subscription reports about itself.
 *
 * Sent by nearly every panel (Marzban, X-UI, Hiddify, v2board) in a
 * `subscription-userinfo` response header:
 *
 *     subscription-userinfo: upload=1024; download=2048; total=107374182400; expire=1735689600
 *
 * All figures are bytes, and `expire` is a Unix timestamp in seconds. Any field
 * may be missing — free links send no header at all, and some panels send the
 * traffic counters without an expiry.
 */
data class SubscriptionInfo(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expiresAt: Long = 0,
) {
    val used: Long get() = upload + download

    /** Null when the panel reports no limit, which it signals by `total=0`. */
    val remaining: Long? get() = if (total > 0) (total - used).coerceAtLeast(0) else null

    val fractionUsed: Float?
        get() = if (total > 0) (used.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null

    /** Null when no expiry was reported; negative once the subscription lapsed. */
    fun daysLeft(now: Long = System.currentTimeMillis()): Long? =
        if (expiresAt <= 0) null else (expiresAt * 1000 - now) / 86_400_000L

    val isEmpty: Boolean get() = total <= 0 && expiresAt <= 0 && used <= 0

    companion object {
        const val HEADER = "subscription-userinfo"

        /**
         * Tolerant on purpose: panels disagree on separators and spacing, and a
         * header we cannot fully read should still yield the fields it does
         * carry rather than nothing.
         */
        fun parse(header: String?): SubscriptionInfo? {
            if (header.isNullOrBlank()) return null
            val fields = HashMap<String, Long>()
            for (part in header.split(';', ',')) {
                val key = part.substringBefore('=', "").trim().lowercase()
                if (key.isEmpty()) continue
                val value = part.substringAfter('=', "").trim().toLongOrNull() ?: continue
                fields[key] = value
            }
            if (fields.isEmpty()) return null
            val info = SubscriptionInfo(
                upload = fields["upload"] ?: 0,
                download = fields["download"] ?: 0,
                total = fields["total"] ?: 0,
                expiresAt = fields["expire"] ?: 0,
            )
            return info.takeUnless { it.isEmpty }
        }
    }
}
