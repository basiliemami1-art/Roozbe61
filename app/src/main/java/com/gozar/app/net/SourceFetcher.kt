package com.gozar.app.net

import android.util.Log
import com.gozar.app.data.DefaultSources
import com.gozar.app.data.GozarDatabase
import com.gozar.app.data.ServerEntity
import com.gozar.app.data.SettingsRepository
import com.gozar.app.data.SourceEntity
import com.gozar.app.data.SourceSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class RefreshProgress(
    val done: Int,
    val total: Int,
    val currentSource: String,
)

data class RefreshResult(
    val newConfigs: Int,
    val totalParsed: Int,
    val sourcesOk: Int,
    val sourcesFailed: Int,
)

/**
 * Pulls raw config text from every enabled source and stores the de-duplicated
 * result. Fetching and parsing live in [SourceLoader], shared with the desktop
 * app; this owns the concurrency policy and the database.
 */
class SourceFetcher(
    private val db: GozarDatabase,
    private val maxServers: Int = 10_000,
) {
    private val tag = "SourceFetcher"

    suspend fun refreshAll(
        onProgress: (RefreshProgress) -> Unit = {},
    ): RefreshResult = withContext(Dispatchers.IO) {
        val sources = db.sourceDao().enabled()
        if (sources.isEmpty()) return@withContext RefreshResult(0, 0, 0, 0)

        // Subscription endpoints tolerate parallel requests; t.me does not.
        val subscriptionLimit = Semaphore(6)
        val telegramLimit = Semaphore(3)
        val done = AtomicInteger(0)
        val newTotal = AtomicInteger(0)
        val parsedTotal = AtomicInteger(0)
        val okCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        coroutineScope {
            sources.map { source ->
                async {
                    val limiter = if (source.isTelegram) telegramLimit else subscriptionLimit
                    limiter.withPermit {
                        if (source.isTelegram) delay(250) // be polite to t.me
                        val outcome = runCatching { refreshOne(source) }
                        val result = outcome.getOrNull()
                        if (result == null) {
                            failCount.incrementAndGet()
                            val message = outcome.exceptionOrNull()?.message ?: "failed"
                            db.sourceDao().markUpdated(
                                source.id,
                                System.currentTimeMillis(),
                                source.configCount,
                                message.take(160),
                            )
                            Log.w(tag, "source ${source.name} failed: $message")
                        } else {
                            okCount.incrementAndGet()
                            newTotal.addAndGet(result.first)
                            parsedTotal.addAndGet(result.second)
                        }
                        onProgress(
                            RefreshProgress(done.incrementAndGet(), sources.size, source.name),
                        )
                    }
                }
            }.awaitAll()
        }

        db.serverDao().prune(maxServers)

        RefreshResult(
            newConfigs = newTotal.get(),
            totalParsed = parsedTotal.get(),
            sourcesOk = okCount.get(),
            sourcesFailed = failCount.get(),
        )
    }

    /** @return (newly inserted, total parsed) */
    private suspend fun refreshOne(source: SourceEntity): Pair<Int, Int> {
        val result = SourceLoader.load(source.url)
        val configs = result.configs

        // Recorded even when the body turns out to be empty: a lapsed paid
        // subscription returns nothing, and the expiry date is the explanation.
        result.info?.let {
            db.sourceDao().markSubscription(
                source.id, it.upload, it.download, it.total, it.expiresAt,
            )
        }

        if (configs.isEmpty()) {
            db.sourceDao().markUpdated(source.id, System.currentTimeMillis(), 0, "no configs found")
            return 0 to 0
        }

        val entities = configs.map { config ->
            ServerEntity(
                uniqueKey = config.uniqueKey,
                name = config.name.take(120).ifBlank { "${config.server}:${config.port}" },
                protocol = config.protocol.name,
                address = config.server,
                port = config.port,
                raw = config.raw,
                sourceId = source.id,
                sourceName = source.name,
            )
        }

        // OnConflictStrategy.IGNORE returns -1 for rows that collided with an
        // existing uniqueKey, which is exactly our "already known" count.
        val ids = db.serverDao().insertAll(entities)
        val inserted = ids.count { it != -1L }

        db.sourceDao().markUpdated(source.id, System.currentTimeMillis(), configs.size, null)
        return inserted to configs.size
    }

    companion object {

        private fun entitiesFor(specs: List<SourceSpec>) = specs.map { spec ->
            SourceEntity(
                name = spec.name,
                url = spec.url,
                kind = spec.kind.name,
                enabled = true,
                builtIn = true,
            )
        }

        /**
         * Adds every built-in source this install has not been offered yet.
         *
         * Seeding only into an empty table would mean an upgrade never delivers
         * a newly shipped source, and re-inserting the whole list every launch
         * would resurrect the ones the user deleted. So the URLs already offered
         * are remembered and only genuinely new ones are inserted. The one
         * unavoidable exception is the first launch after this change, which has
         * no record to consult.
         */
        suspend fun seedDefaults(
            db: GozarDatabase,
            prefs: SettingsRepository,
        ) = withContext(Dispatchers.IO) {
            val seeded = prefs.seededSources()
            val all = DefaultSources.all
            val fresh = all.filterNot { it.url in seeded }
            // The url column is uniquely indexed and inserts ignore conflicts,
            // so anything already present is left untouched.
            if (fresh.isNotEmpty()) db.sourceDao().insertAll(entitiesFor(fresh))
            prefs.markSourcesSeeded(all.mapTo(HashSet()) { it.url })
        }

        /** Restores any built-in source the user deleted, without touching theirs. */
        suspend fun restoreDefaults(db: GozarDatabase) = withContext(Dispatchers.IO) {
            db.sourceDao().insertAll(entitiesFor(DefaultSources.all))
        }
    }
}
