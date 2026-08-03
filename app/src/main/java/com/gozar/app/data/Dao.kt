package com.gozar.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One server's handshake result, used for batched writes. */
data class LatencyResult(
    val id: Long,
    val latency: Int,
    val domesticEntry: Boolean,
)

/** One server's round trip measured through the proxy itself. */
data class RealDelayResult(val id: Long, val delayMs: Int)

@Dao
interface SourceDao {

    @Query("SELECT * FROM sources ORDER BY builtIn DESC, id ASC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE enabled = 1")
    suspend fun enabled(): List<SourceEntity>

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(source: SourceEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(sources: List<SourceEntity>)

    @Update
    suspend fun update(source: SourceEntity)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE sources SET lastUpdated = :time, configCount = :count, lastError = :error WHERE id = :id")
    suspend fun markUpdated(id: Long, time: Long, count: Int, error: String?)

    @Query(
        "UPDATE sources SET upload = :upload, download = :download, " +
            "total = :total, expiresAt = :expiresAt WHERE id = :id",
    )
    suspend fun markSubscription(
        id: Long,
        upload: Long,
        download: Long,
        total: Long,
        expiresAt: Long,
    )

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sources WHERE builtIn = 1")
    suspend fun deleteBuiltIn()
}

@Dao
interface ServerDao {

    @Query(
        """
        SELECT * FROM servers
        WHERE (:search = '' OR name LIKE '%' || :search || '%' OR address LIKE '%' || :search || '%')
          AND (:onlyWorking = 0 OR realDelay > 0)
          AND (:onlyFavorite = 0 OR favorite = 1)
          AND (:onlyDomestic = 0 OR domesticEntry = 1)
          AND (:protocol = '' OR protocol = :protocol)
        ORDER BY favorite DESC, sortWeight ASC, id ASC
        LIMIT :limit
        """,
    )
    fun observeFiltered(
        search: String,
        onlyWorking: Int,
        onlyFavorite: Int,
        onlyDomestic: Int,
        protocol: String,
        limit: Int,
    ): Flow<List<ServerEntity>>

    @Query("SELECT COUNT(*) FROM servers")
    fun observeCount(): Flow<Int>

    /**
     * "Working" means proven, not merely reachable. A server that answers a
     * handshake and then carries nothing is exactly what this count should not
     * be claiming as working.
     */
    @Query("SELECT COUNT(*) FROM servers WHERE realDelay > 0")
    fun observeWorkingCount(): Flow<Int>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun byId(id: Long): ServerEntity?

    @Query("SELECT * FROM servers WHERE id = :id")
    fun observeById(id: Long): Flow<ServerEntity?>

    /** Candidates for a latency sweep: untested first, then stalest results. */
    @Query(
        """
        SELECT * FROM servers
        ORDER BY CASE WHEN latency = -1 THEN 0 ELSE 1 END ASC, lastTested ASC
        LIMIT :limit
        """,
    )
    suspend fun testCandidates(limit: Int): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE latency > 0 ORDER BY sortWeight ASC LIMIT 1")
    suspend fun fastest(): ServerEntity?

    @Query("SELECT * FROM servers ORDER BY sortWeight ASC, id ASC LIMIT :limit")
    suspend fun best(limit: Int): List<ServerEntity>

    /**
     * Best servers whose entry point is inside Iran — the fallback tier when
     * international routing is cut.
     */
    @Query(
        "SELECT * FROM servers WHERE domesticEntry = 1 ORDER BY sortWeight ASC, id ASC LIMIT :limit",
    )
    suspend fun bestDomestic(limit: Int): List<ServerEntity>

    @Query("SELECT COUNT(*) FROM servers WHERE domesticEntry = 1")
    fun observeDomesticCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(servers: List<ServerEntity>): List<Long>

    @Query("SELECT * FROM servers WHERE realDelay > 0 ORDER BY sortWeight ASC LIMIT 1")
    suspend fun fastestProven(): ServerEntity?

    /**
     * Writes a whole batch in one transaction.
     *
     * Room's invalidation tracker fires once per committed transaction, so
     * batching is what keeps a sweep of a few thousand servers from re-running
     * every observed query — and re-composing the list — thousands of times.
     */
    @Transaction
    suspend fun updateLatencies(results: List<LatencyResult>) {
        val now = System.currentTimeMillis()
        for (result in results) {
            updateProbeResult(result.id, result.latency, now, result.domesticEntry)
        }
    }

    /**
     * The sortWeight expression here mirrors [com.gozar.app.data.Latency.rank]
     * exactly, and the two have to be changed together. It is repeated in SQL
     * rather than passed in because the weight depends on the *other* column —
     * a handshake result must not overwrite a ranking already earned by a real
     * request — and reading that back per row would cost a query each.
     */
    @Query(
        """
        UPDATE servers
        SET latency = :latency, lastTested = :time, domesticEntry = :domestic,
            sortWeight = CASE
                WHEN realDelay > 0 THEN realDelay
                WHEN realDelay = -2 THEN 999999
                WHEN :latency > 0 THEN 100000 + :latency
                WHEN :latency = -1 THEN 900000
                ELSE 999999
            END
        WHERE id = :id
        """,
    )
    suspend fun updateProbeResult(
        id: Long,
        latency: Int,
        time: Long,
        domestic: Boolean,
    )

    /** Same mirror of [com.gozar.app.data.Latency.rank], from the other side. */
    @Query(
        """
        UPDATE servers
        SET realDelay = :realDelay, lastTested = :time,
            sortWeight = CASE
                WHEN :realDelay > 0 THEN :realDelay
                WHEN :realDelay = -2 THEN 999999
                WHEN latency > 0 THEN 100000 + latency
                WHEN latency = -1 THEN 900000
                ELSE 999999
            END
        WHERE id = :id
        """,
    )
    suspend fun updateRealDelay(id: Long, realDelay: Int, time: Long)

    /** Written in one transaction, for the same reason as [updateLatencies]. */
    @Transaction
    suspend fun updateRealDelays(results: List<RealDelayResult>) {
        val now = System.currentTimeMillis()
        for (result in results) updateRealDelay(result.id, result.delayMs, now)
    }

    /** Candidates for the expensive stage: reachable, ranked by handshake. */
    @Query(
        """
        SELECT * FROM servers
        WHERE latency > 0 AND protocol != 'WIREGUARD'
        ORDER BY latency ASC
        LIMIT :limit
        """,
    )
    suspend fun measureCandidates(limit: Int): List<ServerEntity>

    @Query("UPDATE servers SET favorite = NOT favorite WHERE id = :id")
    suspend fun toggleFavorite(id: Long)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM servers WHERE (latency = -2 OR realDelay = -2) AND favorite = 0")
    suspend fun deleteDead(): Int

    @Query("DELETE FROM servers WHERE favorite = 0")
    suspend fun deleteAllExceptFavorites()

    @Query("DELETE FROM servers WHERE sourceId = :sourceId AND favorite = 0")
    suspend fun deleteBySource(sourceId: Long)

    /**
     * Keeps the table bounded. Favourites and healthy servers are preserved
     * ahead of untested ones, so pruning never throws away a known-good server.
     */
    @Query(
        """
        DELETE FROM servers WHERE id IN (
            SELECT id FROM servers
            WHERE favorite = 0
            ORDER BY sortWeight DESC, addedAt ASC
            LIMIT MAX(0, (SELECT COUNT(*) FROM servers) - :keep)
        )
        """,
    )
    suspend fun prune(keep: Int)
}
