package com.gozar.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gozar.app.model.Protocol

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
    // Flattened rather than embedded so a schema migration is a plain ALTER.
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expiresAt: Long = 0,
) {
    val isTelegram: Boolean get() = kind == SourceSpec.Kind.TELEGRAM.name

    /** Null unless the panel reported a quota for this subscription. */
    val subscription: SubscriptionInfo?
        get() = SubscriptionInfo(upload, download, total, expiresAt).takeUnless { it.isEmpty }
}

@Entity(
    tableName = "servers",
    indices = [
        Index(value = ["uniqueKey"], unique = true),
        Index(value = ["sortWeight"]),
        Index(value = ["sourceId"]),
        Index(value = ["domesticEntry"]),
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
     * The entry point resolves to an address inside Iran.
     *
     * Set during the latency sweep, from the address the host actually resolves
     * to — a `.ir` domain is no guarantee, since most of them are fronted by
     * foreign CDNs. These are the only servers still reachable when
     * international routing is cut and the domestic network stays up.
     */
    @ColumnInfo(defaultValue = "0")
    val domesticEntry: Boolean = false,
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

}
