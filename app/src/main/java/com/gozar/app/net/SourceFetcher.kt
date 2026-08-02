package com.gozar.app.net

import android.util.Log
import com.gozar.app.data.GozarDatabase
import com.gozar.app.data.ServerEntity
import com.gozar.app.data.SourceEntity
import com.gozar.app.data.SourceSpec
import com.gozar.app.parser.Codecs
import com.gozar.app.parser.ConfigParser
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
 * Pulls raw config text from every enabled source, parses it, and stores the
 * de-duplicated result.
 *
 * Subscription endpoints tolerate parallel requests; `t.me` does not, so the two
 * kinds get separate concurrency limits.
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
                            RefreshProgress(
                                done.incrementAndGet(),
                                sources.size,
                                source.name,
                            ),
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
        val body = download(resolveUrl(source))
        val text = if (Codecs.looksBase64(body)) Codecs.decodeBase64(body) ?: body else body

        val configs = ConfigParser.parseMany(text)

        if (configs.isEmpty()) {
            db.sourceDao().markUpdated(
                source.id,
                System.currentTimeMillis(),
                0,
                "no configs found",
            )
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

        db.sourceDao().markUpdated(
            source.id,
            System.currentTimeMillis(),
            configs.size,
            null,
        )
        return inserted to configs.size
    }

    private fun resolveUrl(source: SourceEntity): String = when {
        source.url.startsWith("tg:") -> "https://t.me/s/${source.url.removePrefix("tg:")}"
        else -> source.url
    }

    private fun download(url: String): String {
        Http.client.newCall(Http.request(url)).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    companion object {

        /** Populates the source table on first launch. */
        suspend fun seedDefaults(db: GozarDatabase) = withContext(Dispatchers.IO) {
            if (db.sourceDao().count() > 0) return@withContext
            db.sourceDao().insertAll(
                com.gozar.app.data.DefaultSources.all.map { spec ->
                    SourceEntity(
                        name = spec.name,
                        url = spec.url,
                        kind = spec.kind.name,
                        enabled = true,
                        builtIn = true,
                    )
                },
            )
        }

        /** Restores any built-in source the user deleted, without touching their own. */
        suspend fun restoreDefaults(db: GozarDatabase) = withContext(Dispatchers.IO) {
            db.sourceDao().insertAll(
                com.gozar.app.data.DefaultSources.all.map { spec ->
                    SourceEntity(
                        name = spec.name,
                        url = spec.url,
                        kind = spec.kind.name,
                        enabled = true,
                        builtIn = true,
                    )
                },
            )
        }

        /**
         * Normalises whatever the user pasted into the "add source" field.
         * Accepts a full URL, `t.me/foo`, `@foo`, or a bare channel name.
         */
        fun normalizeUserInput(input: String): SourceSpec? {
            val value = input.trim()
            if (value.isEmpty()) return null
            val telegramMatch = Regex("""(?:https?://)?t\.me/(?:s/)?([A-Za-z0-9_]{4,})""")
                .find(value)
            if (telegramMatch != null) {
                val channel = telegramMatch.groupValues[1]
                return SourceSpec("@$channel", "tg:$channel", SourceSpec.Kind.TELEGRAM)
            }
            if (value.startsWith("@")) {
                val channel = value.removePrefix("@")
                if (channel.matches(Regex("[A-Za-z0-9_]{4,}"))) {
                    return SourceSpec("@$channel", "tg:$channel", SourceSpec.Kind.TELEGRAM)
                }
            }
            if (value.startsWith("http://") || value.startsWith("https://")) {
                val name = value.substringAfterLast('/').ifBlank { value }.take(40)
                return SourceSpec(name, value, SourceSpec.Kind.SUBSCRIPTION)
            }
            if (value.matches(Regex("[A-Za-z0-9_]{4,}"))) {
                return SourceSpec("@$value", "tg:$value", SourceSpec.Kind.TELEGRAM)
            }
            return null
        }
    }
}
