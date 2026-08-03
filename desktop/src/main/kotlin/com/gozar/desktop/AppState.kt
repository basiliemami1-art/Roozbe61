package com.gozar.desktop

import com.gozar.app.core.IranRanges
import com.gozar.app.data.Latency
import com.gozar.app.data.Settings
import com.gozar.app.model.Protocol
import com.gozar.app.net.Prober
import com.gozar.app.net.RealDelay
import com.gozar.app.net.SourceLoader
import com.gozar.app.net.TunnelProbe
import com.gozar.app.net.Warp
import com.gozar.app.parser.ConfigParser
import com.gozar.desktop.core.DelayTester
import com.gozar.desktop.core.SingBoxProcess
import com.gozar.desktop.core.SystemProxy
import com.gozar.desktop.core.TrafficMonitor
import com.gozar.desktop.data.ServerRecord
import com.gozar.desktop.data.SourceRecord
import com.gozar.desktop.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, STOPPING }

enum class ServerFilter { ALL, WORKING, FAVORITE, DOMESTIC }

sealed interface Busy {
    data class Refreshing(val done: Int, val total: Int, val source: String) : Busy

    /** The cheap handshake sweep over everything. */
    data class Testing(val done: Int, val total: Int, val alive: Int) : Busy

    /** The real request through each of the best few. */
    data class Measuring(val done: Int, val total: Int, val alive: Int) : Busy
}

/**
 * Which server the connect loop is on. Kept structured rather than pre-rendered
 * so the window can phrase it in the user's language.
 */
data class Attempt(val round: Int, val index: Int, val total: Int, val server: String)

/** Per-second rates and the running totals for this session. */
data class Throughput(
    val upBytesPerSecond: Long = 0,
    val downBytesPerSecond: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
)

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
    private val tester = DelayTester(workDir, SingBoxProcess.locate(), ::log)

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

    private val _progress = MutableStateFlow<Attempt?>(null)
    val progress: StateFlow<Attempt?> = _progress.asStateFlow()

    private val _delayMs = MutableStateFlow(0)
    val delayMs: StateFlow<Int> = _delayMs.asStateFlow()

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val _filter = MutableStateFlow(ServerFilter.ALL)
    val filter: StateFlow<ServerFilter> = _filter.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** Bytes moved in the last second, straight from the core's counters. */
    private val _throughput = MutableStateFlow(Throughput())
    val throughput: StateFlow<Throughput> = _throughput.asStateFlow()

    private var busyJob: Job? = null
    private var connectJob: Job? = null
    private var trafficJob: Job? = null
    private var monitor: TrafficMonitor? = null
    private var proxyPort = 0

    // ------------------------------------------------------------------- Log

    // Declared above `init`: the refresh it kicks off logs from another thread,
    // and a property initialised further down is still null at that point.
    private val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        IranRanges.load()
        if (_servers.value.isEmpty() && _settings.value.autoUpdateSources) {
            refreshSources()
        }
    }

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
                    // "Working" means proven, not merely reachable: a server
                    // that answers a handshake and then carries nothing is
                    // precisely what this filter exists to hide.
                    ServerFilter.WORKING -> it.proven
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

    private var lastPublishAt = 0L

    /**
     * Pushes the working set to the screen while a sweep is still running.
     *
     * Throttled because a refresh lands tens of thousands of configs across
     * fifty sources: re-sorting and re-emitting the whole list on every one of
     * them would spend more time in Compose than on the network.
     */
    private fun publishServers(servers: Collection<ServerRecord>, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishAt < PUBLISH_INTERVAL_MS) return
        lastPublishAt = now
        _servers.value = servers.sortedBy { it.sortWeight }.take(_settings.value.maxServers)
    }

    fun refreshSources() {
        if (busyJob?.isActive == true) return
        busyJob = scope.launch {
            val enabled = _sources.value.filter { it.enabled }
            if (enabled.isEmpty()) return@launch
            val keptDisabled = _sources.value.filterNot { it.enabled }
            _busy.value = Busy.Refreshing(0, enabled.size, "")

            // Subscription endpoints tolerate parallel requests; t.me does not.
            val subs = Semaphore(6)
            val telegram = Semaphore(3)
            // Guards every shared structure below. The sources genuinely run at
            // once now — they used to be launched inside a plain `for`, which
            // meant the semaphores guarded nothing and fifty downloads happened
            // one after another.
            val guard = Mutex()
            val byKey = LinkedHashMap<String, ServerRecord>()
            for (server in _servers.value) byKey[server.uniqueKey] = server
            var nextId = (_servers.value.maxOfOrNull { it.id } ?: 0L) + 1
            val updated = enabled.toMutableList()
            val done = AtomicInteger(0)
            val added = AtomicInteger(0)

            try {
                coroutineScope {
                    enabled.mapIndexed { index, source ->
                        async {
                            val limiter = if (source.kind == "TELEGRAM") telegram else subs
                            limiter.withPermit {
                                if (source.kind == "TELEGRAM") delay(250)
                                val outcome = runCatching { SourceLoader.load(source.url) }
                                val result = outcome.getOrNull()

                                guard.withLock {
                                    var record = updated[index]
                                    // Recorded even when the body is empty: that
                                    // is what a lapsed paid subscription returns,
                                    // and the expiry is the explanation.
                                    result?.info?.let { quota ->
                                        record = record.copy(
                                            upload = quota.upload,
                                            download = quota.download,
                                            total = quota.total,
                                            expiresAt = quota.expiresAt,
                                        )
                                    }
                                    val configs = result?.configs
                                    record = if (configs == null) {
                                        record.copy(
                                            lastError = outcome.exceptionOrNull()
                                                ?.message?.take(120),
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
                                            added.incrementAndGet()
                                        }
                                        record.copy(
                                            configCount = configs.size,
                                            lastUpdated = System.currentTimeMillis(),
                                            lastError = null,
                                        )
                                    }
                                    updated[index] = record
                                    // Straight onto the screen, rather than after
                                    // all fifty sources have answered.
                                    publishServers(byKey.values)
                                    _sources.value = updated + keptDisabled
                                }
                            }
                            _busy.value =
                                Busy.Refreshing(done.incrementAndGet(), enabled.size, source.name)
                        }
                    }.awaitAll()
                }
            } finally {
                // No lock needed: coroutineScope has already waited for every
                // child, including when the user cancelled the sweep. Whatever
                // arrived before that is kept rather than thrown away.
                publishServers(byKey.values, force = true)
                _sources.value = updated + keptDisabled
                store.saveServers(_servers.value)
                store.saveSources(_sources.value)
                _busy.value = null
            }
            log("sources updated — ${added.get()} new configs from ${enabled.size} sources")
            if (added.get() > 0) testAll()
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
            val current = _servers.value
            // Declared outside the try so a cancelled sweep can still publish
            // every measurement it managed to take.
            val byId = current.associateByTo(LinkedHashMap()) { it.id }
            try {
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
                var answered = 0
                prober.sweep(
                    targets = targets,
                    onBatch = { batch ->
                        for (outcome in batch) {
                            val server = byId[outcome.id] ?: continue
                            if (outcome.latencyMs > 0) answered++
                            byId[outcome.id] = server.copy(
                                latency = outcome.latencyMs,
                                domesticEntry = outcome.domesticEntry,
                            )
                        }
                        // Applied as each batch lands, so the ranking settles in
                        // front of the user instead of after the whole sweep.
                        publishServers(byId.values)
                    },
                    onProgress = { _busy.value = Busy.Testing(it.done, it.total, it.alive) },
                )
                log("pinged ${targets.size} servers, $answered answered")
                // The handshake only narrows the field. What decides the order
                // is the second stage, which times a real request through each
                // of the best few over the user's own connection.
                measureBest(byId, settings)
            } finally {
                // A cancelled sweep still keeps everything it measured.
                publishServers(byId.values, force = true)
                store.saveServers(_servers.value)
                prober.shutdown()
                _busy.value = null
            }
        }
    }

    /**
     * Times a real request through the best few servers and re-ranks on that.
     *
     * Only a shortlist: each measurement is a full handshake plus an HTTPS
     * request through the proxy, so doing it for the whole list would be slow
     * and would spend real traffic. The handshake sweep is what narrows the
     * field down to something worth spending that on.
     */
    private suspend fun measureBest(
        byId: MutableMap<Long, ServerRecord>,
        settings: Settings,
    ) {
        val shortlist = byId.values
            .filter { it.latency > 0 && settings.allowsProtocol(it.protocol) }
            .filter { RealDelay.testable(it.protocol) }
            .sortedBy { it.latency }
            .take(REAL_TEST_SIZE)
        if (shortlist.isEmpty()) return

        val candidates = shortlist.mapNotNull { server ->
            ConfigParser.parse(server.raw)?.let { server.id to it }
        }
        if (candidates.isEmpty()) return

        _busy.value = Busy.Measuring(0, candidates.size, 0)
        log("measuring ${candidates.size} servers through the proxy")
        val outcomes = tester.measure(
            candidates = candidates,
            settings = settings,
            onResult = { outcome ->
                byId[outcome.id]?.let { byId[outcome.id] = it.copy(realDelay = outcome.delayMs) }
                publishServers(byId.values)
            },
            onProgress = { _busy.value = Busy.Measuring(it.done, it.total, it.alive) },
        )
        val working = outcomes.count { it.delayMs > 0 }
        log("$working of ${candidates.size} carried a real request")
    }

    fun cancelBusy() {
        busyJob?.cancel()
        busyJob = null
        // The tester owns a child process; cancelling the job alone would
        // orphan it, since its cleanup runs in the cancelled coroutine.
        tester.stop()
        _busy.value = null
    }

    // ------------------------------------------------------------- Connection

    fun connect(serverId: Long = 0) {
        val previous = _status.value
        // A connect or a disconnect already in flight owns the core; anything
        // else would race it.
        if (previous == ConnectionStatus.CONNECTING || previous == ConnectionStatus.STOPPING) return
        _status.value = ConnectionStatus.CONNECTING
        connectJob = scope.launch {
            // Clicking a different server while connected switches to it.
            // Without this the click did nothing at all.
            if (previous == ConnectionStatus.CONNECTED) {
                withContext(Dispatchers.IO) { teardown() }
            }
            try {
                val connected = keepTrying(serverId)
                _status.value = ConnectionStatus.CONNECTED
                _progress.value = null
                log("connected — ${connected.name}")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                log("connect failed: ${error.message}")
                _progress.value = null
                stop()
            }
        }
    }

    /** The only thing that ends a connect attempt short of success. */
    fun cancelConnect() {
        if (_status.value != ConnectionStatus.CONNECTING) return
        val attempt = connectJob
        connectJob = null
        _status.value = ConnectionStatus.STOPPING
        _progress.value = null
        scope.launch {
            // Waited on rather than cancelled-and-forgotten: the attempt may be
            // inside core.start, and tearing the core down first would leave the
            // process it is about to spawn running with nothing tracking it.
            attempt?.cancelAndJoin()
            withContext(Dispatchers.IO) { teardown() }
            _activeServerId.value = 0
            _status.value = ConnectionStatus.DISCONNECTED
            log("connect cancelled")
        }
    }

    /**
     * Tries servers until one carries traffic, or the user cancels.
     *
     * A fixed budget of attempts was the wrong shape: with tens of thousands of
     * scraped servers, most of which are dead on any given day, running out
     * after six left the user pressing a button that had quietly stopped
     * trying. Each exhausted pass widens the net and starts again, so the only
     * thing that ends this is success or cancellation.
     *
     * A server is still only accepted once a real request has gone through it —
     * a handshake cannot tell a working proxy from one that fails at
     * authentication.
     */
    private suspend fun keepTrying(requestedId: Long): ServerRecord {
        val tried = HashSet<Long>()
        var round = 1
        var lastError: String? = null

        while (true) {
            currentCoroutineContext().ensureActive()
            val settings = _settings.value
            val candidates = rank(requestedId, round, settings)
            if (candidates.isEmpty()) {
                error(
                    if (_servers.value.isEmpty()) "no servers yet — update the sources first"
                    else "no servers match the protocols you allowed",
                )
            }
            val fresh = candidates.filterNot { it.id in tried }
            if (fresh.isEmpty()) {
                log("round $round exhausted (${lastError ?: "no reason recorded"}); widening")
                tried.clear()
                round++
                // A short pause so a transient outage is not hammered, and so
                // any refresh or test running alongside can land new results
                // before the next pass ranks the list again.
                delay(RETRY_PAUSE_MS)
                continue
            }
            for ((index, server) in fresh.withIndex()) {
                currentCoroutineContext().ensureActive()
                tried += server.id
                val failure = attempt(server, settings, round, index + 1, fresh.size)
                if (failure == null) return server
                lastError = failure
            }
        }
    }

    /**
     * The candidates for one pass, widening with each failed round. Re-read
     * every time because a sweep running alongside keeps changing the ranking.
     */
    private fun rank(requestedId: Long, round: Int, settings: Settings): List<ServerRecord> {
        val all = _servers.value.filter { settings.allowsProtocol(it.protocol) }
        // An explicit click always wins; otherwise the last-used server is only
        // pinned when the user has turned auto-pick off.
        val preferredId = when {
            requestedId > 0 -> requestedId
            !settings.autoSelectFastest -> settings.selectedServerId
            else -> 0L
        }
        val preferred = if (preferredId > 0) all.firstOrNull { it.id == preferredId } else null
        val width = (FIRST_ROUND_WIDTH * round).coerceAtMost(MAX_CANDIDATES)
        val best = all.sortedBy { it.sortWeight }.take(width)
        // Domestic-entry servers are kept in reserve: during an international
        // cut they are the only ones reachable, and when routing is fine they
        // simply lose on latency and never get tried.
        val domestic = all.filter { it.domesticEntry }.sortedBy { it.sortWeight }
            .take(DOMESTIC_ATTEMPTS)
        return (listOfNotNull(preferred) + best + domestic).distinctBy { it.id }
    }

    /** @return null once the server is carrying traffic, otherwise why it is not. */
    private suspend fun attempt(
        server: ServerRecord,
        settings: Settings,
        round: Int,
        index: Int,
        total: Int,
    ): String? {
        val parsed = ConfigParser.parse(server.raw) ?: return "unparseable config"
        // A WARP link carries no credentials; one free account is registered on
        // demand and reused. A failure here must not stop the pass.
        val proxy = try {
            Warp.fill(parsed)
        } catch (error: Throwable) {
            log("WARP unavailable: ${error.message}")
            return "WARP unavailable: ${error.message}"
        }
        _activeServerId.value = server.id
        _progress.value = Attempt(round, index, total, server.name)
        log(
            "round $round try $index/$total: ${proxy.protocol.label} " +
                "${proxy.server}:${proxy.port}" +
                if (server.domesticEntry) " [domestic]" else "",
        )

        val port = try {
            withContext(Dispatchers.IO) { core.start(proxy, settings) }
        } catch (error: Throwable) {
            log("core would not start: ${error.message}")
            return "core error: ${error.message}"
        }
        proxyPort = port

        if (!TunnelProbe.inboundIsUp(port)) {
            log("core inbound never came up")
            core.stop()
            return "core inbound never came up"
        }
        val result = TunnelProbe.measure(port)
        if (result.delayMs > 0) {
            _delayMs.value = result.delayMs
            markLatency(server.id, result.delayMs)
            SystemProxy.enable(port)
            log("system proxy set to 127.0.0.1:$port")
            _settings.value = settings.copy(selectedServerId = server.id)
            store.saveSettings(_settings.value)
            watchTraffic()
            return null
        }
        log("${server.name} carried no traffic (${result.error})")
        markLatency(server.id, Latency.FAILED)
        core.stop()
        return result.error ?: "no traffic"
    }

    /**
     * Follows the core's `/traffic` stream for as long as it is up. The rates
     * arrive already averaged over a second, so they are stored as sent and
     * accumulated here for the session totals.
     */
    private fun watchTraffic() {
        stopWatchingTraffic()
        val port = core.clashApiPort
        if (port <= 0) return
        val watcher = TrafficMonitor(port)
        monitor = watcher
        trafficJob = scope.launch {
            watcher.stream { sample ->
                val previous = _throughput.value
                _throughput.value = Throughput(
                    upBytesPerSecond = sample.upBytes,
                    downBytesPerSecond = sample.downBytes,
                    upTotal = previous.upTotal + sample.upBytes,
                    downTotal = previous.downTotal + sample.downBytes,
                )
            }
        }
    }

    private fun stopWatchingTraffic() {
        trafficJob?.cancel()
        trafficJob = null
        monitor?.shutdown()
        monitor = null
        _throughput.value = Throughput()
    }

    /**
     * Blocking: killing the core waits on the process, and restoring the system
     * proxy shells out. Never call it straight from a click.
     */
    private fun teardown() {
        stopWatchingTraffic()
        SystemProxy.disable()
        core.stop()
        proxyPort = 0
        _delayMs.value = 0
    }

    fun stop() {
        if (_status.value == ConnectionStatus.DISCONNECTED) return
        _status.value = ConnectionStatus.STOPPING
        scope.launch {
            withContext(Dispatchers.IO) { teardown() }
            _activeServerId.value = 0
            _status.value = ConnectionStatus.DISCONNECTED
            log("disconnected")
        }
    }

    /**
     * Always run on the way out, and synchronously: a stale system proxy points
     * every browser on the machine at a port that is no longer listening.
     */
    fun shutdown() {
        tester.stop()
        teardown()
    }

    /**
     * Records what a connect attempt learned. Both outcomes are real requests
     * through the proxy, so they belong in [ServerRecord.realDelay] — the same
     * scale the measurement stage writes, not the handshake one.
     */
    private fun markLatency(id: Long, latency: Int) {
        val updated = _servers.value.map { if (it.id == id) it.copy(realDelay = latency) else it }
        _servers.value = updated
        store.saveServers(updated)
    }

    fun toggleFavorite(id: Long) {
        val updated = _servers.value.map { if (it.id == id) it.copy(favorite = !it.favorite) else it }
        _servers.value = updated
        store.saveServers(updated)
    }

    fun removeDead() {
        val updated = _servers.value.filterNot {
            !it.favorite && (it.latency == Latency.FAILED || it.realDelay == Latency.FAILED)
        }
        _servers.value = updated
        store.saveServers(updated)
    }

    // -------------------------------------------------------------- Settings

    fun update(transform: (Settings) -> Settings) {
        _settings.value = transform(_settings.value)
        store.saveSettings(_settings.value)
    }

    companion object {
        /** Candidates in the first pass; each failed round multiplies this. */
        private const val FIRST_ROUND_WIDTH = 6
        private const val MAX_CANDIDATES = 400
        private const val DOMESTIC_ATTEMPTS = 3
        private const val RETRY_PAUSE_MS = 1_500L
        private const val PUBLISH_INTERVAL_MS = 400L

        /**
         * How many of the best-pinged servers get a real request put through
         * them. Every one costs a handshake and an HTTPS round trip on the
         * user's own connection, so this stays a shortlist.
         */
        private const val REAL_TEST_SIZE = 50

        private val UDP_PROTOCOLS = setOf(
            Protocol.HYSTERIA2.name,
            Protocol.TUIC.name,
            Protocol.WIREGUARD.name,
        )
    }
}
