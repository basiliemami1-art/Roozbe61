package com.gozar.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gozar.app.model.Protocol

object Latency {
    /** Never probed. */
    const val UNTESTED = -1

    /** Probed and unreachable. */
    const val FAILED = -2

    /** Sort weight so healthy servers rise, untested follow, dead sink. */
    const val UNTESTED_WEIGHT = 900_000
    const val FAILED_WEIGHT = 999_999
}

@Entity(
    tableName = "sources",
    indices = [Index(value = ["url"], unique = true)],
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val kind: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val lastUpdated: Long = 0,
    val configCount: Int = 0,
    val lastError: String? = null,
) {
    val isTelegram: Boolean get() = kind == SourceSpec.Kind.TELEGRAM.name
}

@Entity(
    tableName = "servers",
    indices = [
        Index(value = ["uniqueKey"], unique = true),
        Index(value = ["sortWeight"]),
        Index(value = ["sourceId"]),
    ],
)
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uniqueKey: String,
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val raw: String,
    val sourceId: Long = 0,
    val sourceName: String = "",
    val latency: Int = Latency.UNTESTED,
    val lastTested: Long = 0,
    val favorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    /**
     * Denormalised ordering key, maintained alongside [latency]. Keeping it as a
     * plain indexed column lets "fastest first" be an index scan instead of a
     * CASE expression over every row.
     */
    @ColumnInfo(defaultValue = "900000")
    val sortWeight: Int = Latency.UNTESTED_WEIGHT,
) {
    val protocolEnum: Protocol?
        get() = runCatching { Protocol.valueOf(protocol) }.getOrNull()

    companion object {
        fun weightFor(latency: Int): Int = when {
            latency > 0 -> latency
            latency == Latency.UNTESTED -> Latency.UNTESTED_WEIGHT
            else -> Latency.FAILED_WEIGHT
        }
    }
}
