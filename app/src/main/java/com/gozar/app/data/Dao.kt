package com.gozar.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One server's measured latency, used for batched writes. */
data class LatencyResult(val id: Long, val latency: Int, val weight: Int)

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
          AND (:onlyWorking = 0 OR latency > 0)
          AND (:onlyFavorite = 0 OR favorite = 1)
          AND (:protocol = '' OR protocol = :protocol)
        ORDER BY favorite DESC, sortWeight ASC, id ASC
        LIMIT :limit
        """,
    )
    fun observeFiltered(
        search: String,
        onlyWorking: Int,
        onlyFavorite: Int,
        protocol: String,
        limit: Int,
    ): Flow<List<ServerEntity>>

    @Query("SELECT COUNT(*) FROM servers")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM servers WHERE latency > 0")
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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(servers: List<ServerEntity>): List<Long>

    @Query("UPDATE servers SET latency = :latency, sortWeight = :weight, lastTested = :time WHERE id = :id")
    suspend fun updateLatency(id: Long, latency: Int, weight: Int, time: Long)

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
            updateLatency(result.id, result.latency, result.weight, now)
        }
    }

    @Query("UPDATE servers SET favorite = NOT favorite WHERE id = :id")
    suspend fun toggleFavorite(id: Long)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM servers WHERE latency = -2 AND favorite = 0")
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
