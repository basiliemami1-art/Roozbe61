package com.gozar.desktop

import com.gozar.app.core.IranRanges
import com.gozar.app.data.Latency
import com.gozar.app.data.Settings
import com.gozar.app.model.Protocol
import com.gozar.app.net.Prober
import com.gozar.app.net.SourceLoader
import com.gozar.app.net.TunnelProbe
import com.gozar.app.parser.ConfigParser
import com.gozar.desktop.core.SingBoxProcess
import com.gozar.desktop.core.SystemProxy
import com.gozar.desktop.data.ServerRecord
import com.gozar.desktop.data.SourceRecord
import com.gozar.desktop.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, STOPPING }

enum class ServerFilter { ALL, WORKING, FAVORITE, DOMESTIC }

sealed interface Busy {
    data class Refreshing(val done: Int, val total: Int, val source: String) : Busy
    data class Testing(val done: Int, val total: Int, val alive: Int) : Busy
}

/**
 * All of the desktop app's state and behaviour.
 *
 * The Windows app runs the core as a child process behind the system proxy
 * rather than a TUN device, so unlike Android there is no service to talk to —
 * the whole lifecycle fits here.
 */
class AppState(
    private val store: Store = Store.default(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val workDir = File(System.getenv("LOCALAPPDATA") ?: ".", "Gozar/core")
    private val core = SingBoxProcess(workDir, SingBoxProcess.locate(), ::log)

    private val _settings = MutableStateFlow(store.loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _servers = MutableStateFlow(store.loadServers())
    val servers: StateFlow<List<ServerRecord>> = _servers.asStateFlow()

    private val _sources = MutableStateFlow(store.loadSources())
    val sources: StateFlow<List<SourceRecord>> = _sources.asStateFlow()

    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _activeServerId = MutableStateFlow(0L)
    val activeServerId: StateFlow<Long> = _activeServerId.asStateFlow()

    private val _busy = MutableStateFlow<Busy?>(null)
    val busy: StateFlow<Busy?> = _busy.asStateFlow()

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private val _delayMs = MutableStateFlow(0)
    val delayMs: StateFlow<Int> = _delayMs.asStateFlow()

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val _filter = MutableStateFlow(ServerFilter.ALL)
    val filter: StateFlow<ServerFilter> = _filter.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var busyJob: Job? = null
    private var proxyPort = 0

    init {
        IranRanges.load()
        if (_servers.value.isEmpty() && _settings.value.autoUpdateSources) {
            refreshSources()
        }
    }

    // ------------------------------------------------------------------- Log

    private val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(message: String) {
        val line = "${timestamp.format(Date())}  $message"
        _log.value = (_log.value + line).takeLast(300)
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    // -------------------------------------------------------------- Filtering

    fun setSearch(value: String) {
        _search.value = value
    }

    fun setFilter(value: ServerFilter) {
        _filter.value = value
    }

    /** The list as shown: filtered, ranked, and with the live server pinned. */
    fun visibleServers(
        all: List<ServerRecord>,
        query: String,
        filter: ServerFilter,
        activeId: Long,
    ): List<ServerRecord> {
        val needle = query.trim().lowercase()
        val filtered = all.asSequence()
            .filter { needle.isEmpty() || it.name.lowercase().contains(needle) || it.address.contains(needle) }
            .filter {
                when (filter) {
                    ServerFilter.ALL -> true
                    ServerFilter.WORKING -> it.latency > 0
                    ServerFilter.FAVORITE -> it.favorite
                    ServerFilter.DOMESTIC -> it.domesticEntry
                }
            }
            .sortedWith(compareByDescending<ServerRecord> { it.favorite }.thenBy { it.sortWeight })
            .take(600)
            .toList()
        val active = all.firstOrNull { it.id == activeId } ?: return filtered
        return listOf(active) + filtered.filterNot { it.id == active.id }
    }

    // --------------------------------------------------------------- Sources

    fun refreshSources() {
        if (busyJob?.isActive == true) return
        busyJob = scope.launch {
            _busy.value = Busy.Refreshing(0, 1, "")
            val enabled = _sources.value.filter { it.enabled }
            if (enabled.isEmpty()) {
                _busy.value = null
                return@launch
            }
            // Subscription endpoints tolerate parallel requests; t.me does not.
            val subs = Semaphore(6)
            val telegram = Semaphore(3)
            val byKey = _servers.value.associateBy { it.uniqueKey }.toMutableMap()
            var nextId = (_servers.value.maxOfOrNull { it.id } ?: 0L) + 1
            var done = 0
            var added = 0
            val updatedSources = enabled.toMutableList()

            for ((index, source) in enabled.withIndex()) {
                val limiter = if (source.kind == "TELEGRAM") telegram else subs
                limiter.withPermit {
                    if (source.kind == "TELEGRAM") delay(250)
                    val outcome = runCatching { SourceLoader.fetch(source.url) }
                    val configs = outcome.getOrNull()
                    if (configs == null) {
                        updatedSources[index] = source.copy(
                            lastError = outcome.exceptionOrNull()?.message?.take(120),
                            lastUpdated = System.currentTimeMillis(),
                        )
                    } else {
                        for (config in configs) {
                            if (byKey.containsKey(config.uniqueKey)) continue
                            byKey[config.uniqueKey] = ServerRecord(
                                id = nextId++,
                                uniqueKey = config.uniqueKey,
                                name = config.name.take(120)
                                    .ifBlank { "${config.server}:${config.port}" },
                                protocol = config.protocol.name,
                                address = config.server,
                                port = config.port,
                                raw = config.raw,
                                sourceName = source.name,
                            )
                            added++
                        }
                        updatedSources[index] = source.copy(
                            configCount = configs.size,
                            lastUpdated = System.currentTimeMillis(),
                            lastError = null,
                        )
                    }
                }
                done++
                _busy.value = Busy.Refreshing(done, enabled.size, source.name)
            }

            val merged = byKey.values.sortedBy { it.sortWeight }.take(_settings.value.maxServers)
            _servers.value = merged
            store.saveServers(merged)
            val keptDisabled = _sources.value.filterNot { it.enabled }
            _sources.value = updatedSources + keptDisabled
            store.saveSources(_sources.value)
            log("sources updated — $added new configs from ${enabled.size} sources")
            _busy.value = null
            if (added > 0) testAll()
        }
    }

    fun addSource(input: String) {
        val spec = SourceLoader.normalizeUserInput(input) ?: run {
            log("could not read that as a link or channel")
            return
        }
        if (_sources.value.any { it.url == spec.url }) return
        _sources.value = _sources.value +
            SourceRecord(spec.name, spec.url, spec.kind.name, builtIn = false)
        store.saveSources(_sources.value)
        refreshSources()
    }

    fun setSourceEnabled(url: String, enabled: Boolean) {
        _sources.value = _sources.value.map { if (it.url == url) it.copy(enabled = enabled) else it }
        store.saveSources(_sources.value)
    }

    fun deleteSource(url: String) {
        _sources.value = _sources.value.filterNot { it.url == url }
        store.saveSources(_sources.value)
    }

    fun restoreDefaultSources() {
        _sources.value = store.restoreDefaultSources(_sources.value)
        store.saveSources(_sources.value)
    }

    // --------------------------------------------------------------- Testing

    fun testAll() {
        if (busyJob?.isActive == true && _busy.value is Busy.Testing) return
        busyJob = scope.launch {
            _busy.value = Busy.Testing(0, 1, 0)
            val settings = _settings.value
            val prober = Prober(settings.testConcurrency, settings.testTimeoutSeconds * 1000)
            try {
                val current = _servers.value
                val targets = current
                    .sortedWith(compareBy({ it.latency != Latency.UNTESTED }, { it.sortWeight }))
                    .take(2_000)
                    .map { server ->
                        Prober.Target(
                            id = server.id,
                            host = server.address,
                            port = server.port,
                            udpOnly = server.protocol in UDP_PROTOCOLS,
                        )
                    }
                val results = HashMap<Long, Prober.Outcome>()
                prober.sweep(
                    targets = targets,
                    onBatch = { batch -> batch.forEach { results[it.id] = it } },
                    onProgress = { _busy.value = Busy.Testing(it.done, it.total, it.alive) },
                )
                val updated = _servers.value.map { server ->
                    val outcome = results[server.id] ?: return@map server
                    server.copy(
                        latency = outcome.latencyMs,
                        domesticEntry = outcome.domesticEntry,
                    )
                }
                _servers.value = updated
                store.saveServers(updated)
                log("tested ${targets.size} servers, ${results.values.count { it.latencyMs > 0 }} answered")
            } finally {
                prober.shutdown()
                _busy.value = null
            }
        }
    }

    fun cancelBusy() {
        busyJob?.cancel()
        busyJob = null
        _busy.value = null
    }

    // ------------------------------------------------------------- Connection

    fun connect(serverId: Long = 0) {
        if (_status.value != ConnectionStatus.DISCONNECTED) return
        _status.value = ConnectionStatus.CONNECTING
        scope.launch {
            try {
                val connected = connectWithFailover(serverId)
                _status.value = ConnectionStatus.CONNECTED
                _progress.value = null
                log("connected — ${connected.name}")
            } catch (error: Throwable) {
                log("connect failed: ${error.message}")
                _progress.value = null
                stop()
            }
        }
    }

    /**
     * Same contract as on Android: a server is only accepted once a real request
     * has gone through it. A handshake probe cannot tell a working proxy from
     * one that will fail at authentication.
     */
    private suspend fun connectWithFailover(requestedId: Long): ServerRecord {
        val settings = _settings.value
        val all = _servers.value
        val preferredId = if (requestedId > 0) requestedId else settings.selectedServerId
        val preferred = all.firstOrNull { it.id == preferredId }
        val best = all.sortedBy { it.sortWeight }.take(MAX_ATTEMPTS)
        // Domestic-entry servers are kept in reserve: during an international
        // cut they are the only ones reachable, and when routing is fine they
        // simply lose on latency and never get tried.
        val domestic = all.filter { it.domesticEntry }.sortedBy { it.sortWeight }.take(3)
        val candidates = (listOfNotNull(preferred) + best + domestic)
            .distinctBy { it.id }
            .take(MAX_ATTEMPTS + 3)

        if (candidates.isEmpty()) error("no servers yet — update the sources first")
        log("connecting — ${candidates.size} candidates")

        var lastError: String? = null
        for ((index, server) in candidates.withIndex()) {
            val proxy = ConfigParser.parse(server.raw) ?: continue
            _activeServerId.value = server.id
            _progress.value = "Trying ${index + 1}/${candidates.size} — ${server.name}"
            log(
                "try ${index + 1}/${candidates.size}: ${proxy.protocol.label} " +
                    "${proxy.server}:${proxy.port}" +
                    if (server.domesticEntry) " [domestic]" else "",
            )

            val port = try {
                withContext(Dispatchers.IO) { core.start(proxy, settings) }
            } catch (error: Throwable) {
                lastError = error.message
                log("core would not start: ${error.message}")
                continue
            }
            proxyPort = port

            if (!TunnelProbe.inboundIsUp(port)) {
                lastError = "core inbound never came up"
                log(lastError!!)
                core.stop()
                continue
            }
            val result = TunnelProbe.measure(port)
            if (result.delayMs > 0) {
                _delayMs.value = result.delayMs
                markLatency(server.id, result.delayMs)
                SystemProxy.enable(port)
                log("system proxy set to 127.0.0.1:$port")
                _settings.value = settings.copy(selectedServerId = server.id)
                store.saveSettings(_settings.value)
                return server
            }
            lastError = result.error ?: "no traffic"
            log("${server.name} carried no traffic (${result.error})")
            markLatency(server.id, Latency.FAILED)
            core.stop()
        }
        error("none of the ${candidates.size} servers carried traffic (${lastError ?: "unknown"})")
    }

    fun stop() {
        if (_status.value == ConnectionStatus.DISCONNECTED) return
        _status.value = ConnectionStatus.STOPPING
        SystemProxy.disable()
        core.stop()
        proxyPort = 0
        _delayMs.value = 0
        _activeServerId.value = 0
        _status.value = ConnectionStatus.DISCONNECTED
        log("disconnected")
    }

    /** Always run on the way out: a stale system proxy breaks all browsing. */
    fun shutdown() {
        SystemProxy.disable()
        core.stop()
    }

    private fun markLatency(id: Long, latency: Int) {
        val updated = _servers.value.map { if (it.id == id) it.copy(latency = latency) else it }
        _servers.value = updated
        store.saveServers(updated)
    }

    fun toggleFavorite(id: Long) {
        val updated = _servers.value.map { if (it.id == id) it.copy(favorite = !it.favorite) else it }
        _servers.value = updated
        store.saveServers(updated)
    }

    fun removeDead() {
        val updated = _servers.value.filterNot { it.latency == Latency.FAILED && !it.favorite }
        _servers.value = updated
        store.saveServers(updated)
    }

    // -------------------------------------------------------------- Settings

    fun update(transform: (Settings) -> Settings) {
        _settings.value = transform(_settings.value)
        store.saveSettings(_settings.value)
    }

    companion object {
        private const val MAX_ATTEMPTS = 6
        private val UDP_PROTOCOLS = setOf(
            Protocol.HYSTERIA2.name,
            Protocol.TUIC.name,
            Protocol.WIREGUARD.name,
        )
    }
}
