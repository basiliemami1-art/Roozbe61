package com.gozar.desktop.data

import com.gozar.app.data.DefaultSources
import com.gozar.app.data.Latency
import com.gozar.app.data.Settings
import com.gozar.app.data.SourceSpec
import com.gozar.app.data.SubscriptionInfo
import com.gozar.app.data.ThemeMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File

@Serializable
data class ServerRecord(
    val id: Long,
    val uniqueKey: String,
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val raw: String,
    val sourceName: String = "",
    val latency: Int = Latency.UNTESTED,
    val favorite: Boolean = false,
    val domesticEntry: Boolean = false,
) {
    val sortWeight: Int get() = Latency.weightFor(latency)
}

@Serializable
data class SourceRecord(
    val name: String,
    val url: String,
    val kind: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = true,
    val lastUpdated: Long = 0,
    val configCount: Int = 0,
    val lastError: String? = null,
    // What a paid panel reports about the subscription itself. Zero throughout
    // for a free link, which sends no `subscription-userinfo` header at all.
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expiresAt: Long = 0,
) {
    val subscription: SubscriptionInfo?
        get() = SubscriptionInfo(upload, download, total, expiresAt).takeUnless { it.isEmpty }
}

@Serializable
private data class PersistedSettings(
    val theme: String = ThemeMode.SYSTEM.name,
    val language: String = "fa",
    val selectedServerId: Long = 0,
    val bypassIran: Boolean = true,
    val bypassLan: Boolean = true,
    val blockAds: Boolean = false,
    val autoSelectFastest: Boolean = true,
    val autoUpdateSources: Boolean = true,
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val testConcurrency: Int = 64,
    val testTimeoutSeconds: Int = 4,
    val allowedProtocols: Set<String> = emptySet(),
)

/**
 * Plain JSON files under the user's app data directory.
 *
 * A database would buy indexes this app does not need — the whole server list
 * fits in memory and is sorted there — while costing a native dependency and a
 * migration story on a platform where the app can be deleted by dragging a
 * folder to the bin.
 */
class Store(private val dir: File) {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    private val serversFile = File(dir, "servers.json")
    private val sourcesFile = File(dir, "sources.json")
    private val settingsFile = File(dir, "settings.json")

    /** Built-in URLs already offered, so deletions survive an upgrade. */
    private val seenFile = File(dir, "seeded-sources.json")

    init {
        dir.mkdirs()
    }

    fun loadServers(): List<ServerRecord> = read(serversFile) { emptyList() }

    fun saveServers(servers: List<ServerRecord>) = write(serversFile, servers)

    /**
     * The stored list, plus any built-in source this install has not seen.
     *
     * Without the second half an upgrade would never deliver a newly shipped
     * source. The seen URLs are recorded separately so a source the user
     * deleted stays deleted instead of reappearing on the next launch.
     */
    fun loadSources(): List<SourceRecord> {
        val stored = read<SourceRecord>(sourcesFile) { emptyList() }
        if (stored.isEmpty()) {
            val defaults = DefaultSources.all.map { it.toRecord() }
            saveSources(defaults)
            markSeen(DefaultSources.all.map { it.url })
            return defaults
        }
        val seen = read<String>(seenFile) { emptyList() }.toSet()
        val fresh = DefaultSources.all.filterNot { it.url in seen }.map { it.toRecord() }
        if (fresh.isEmpty()) return stored
        val known = stored.mapTo(HashSet()) { it.url }
        val merged = stored + fresh.filterNot { it.url in known }
        markSeen(DefaultSources.all.map { it.url })
        saveSources(merged)
        return merged
    }

    private fun markSeen(urls: List<String>) = write(seenFile, urls)

    private fun SourceSpec.toRecord() = SourceRecord(name = name, url = url, kind = kind.name)

    fun saveSources(sources: List<SourceRecord>) = write(sourcesFile, sources)

    fun restoreDefaultSources(existing: List<SourceRecord>): List<SourceRecord> {
        val known = existing.mapTo(HashSet()) { it.url }
        val missing = DefaultSources.all
            .filterNot { it.url in known }
            .map { SourceRecord(name = it.name, url = it.url, kind = it.kind.name) }
        return existing + missing
    }

    fun loadSettings(): Settings {
        val stored = runCatching {
            if (settingsFile.exists()) {
                json.decodeFromString(PersistedSettings.serializer(), settingsFile.readText())
            } else {
                PersistedSettings()
            }
        }.getOrDefault(PersistedSettings())
        return Settings(
            language = stored.language,
            theme = runCatching { ThemeMode.valueOf(stored.theme) }.getOrDefault(ThemeMode.SYSTEM),
            selectedServerId = stored.selectedServerId,
            bypassIran = stored.bypassIran,
            bypassLan = stored.bypassLan,
            blockAds = stored.blockAds,
            autoSelectFastest = stored.autoSelectFastest,
            autoUpdateSources = stored.autoUpdateSources,
            remoteDns = stored.remoteDns,
            testConcurrency = stored.testConcurrency,
            testTimeoutSeconds = stored.testTimeoutSeconds,
            allowedProtocols = stored.allowedProtocols,
        )
    }

    fun saveSettings(settings: Settings) {
        val persisted = PersistedSettings(
            theme = settings.theme.name,
            language = settings.language,
            selectedServerId = settings.selectedServerId,
            bypassIran = settings.bypassIran,
            bypassLan = settings.bypassLan,
            blockAds = settings.blockAds,
            autoSelectFastest = settings.autoSelectFastest,
            autoUpdateSources = settings.autoUpdateSources,
            remoteDns = settings.remoteDns,
            testConcurrency = settings.testConcurrency,
            testTimeoutSeconds = settings.testTimeoutSeconds,
            allowedProtocols = settings.allowedProtocols,
        )
        runCatching {
            settingsFile.writeText(json.encodeToString(PersistedSettings.serializer(), persisted))
        }
    }

    // The serializer is built explicitly rather than left to the reified
    // `encodeToString(value)` extension, which needs its own import and
    // otherwise resolves to the two-argument overload with a confusing error.
    private inline fun <reified T> read(file: File, fallback: () -> List<T>): List<T> =
        runCatching {
            if (!file.exists()) fallback()
            else json.decodeFromString(ListSerializer(serializer<T>()), file.readText())
                .ifEmpty { fallback() }
        }.getOrElse { fallback() }

    private inline fun <reified T> write(file: File, values: List<T>) {
        runCatching {
            // Written beside the target and moved into place, so a crash
            // mid-write cannot leave a truncated file behind.
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(ListSerializer(serializer<T>()), values))
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        fun default(): Store {
            val base = System.getenv("LOCALAPPDATA")
                ?: System.getProperty("user.home")
            return Store(File(base, "Gozar"))
        }

        fun specOf(record: SourceRecord): SourceSpec = SourceSpec(
            record.name,
            record.url,
            runCatching { SourceSpec.Kind.valueOf(record.kind) }
                .getOrDefault(SourceSpec.Kind.SUBSCRIPTION),
        )
    }
}
