@file:Suppress("UsePropertyAccessSyntax")

package com.gozar.app.vpn

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.app.ServiceCompat
import com.gozar.app.R
import com.gozar.app.core.SingBoxConfig
import com.gozar.app.data.GozarDatabase
import com.gozar.app.data.Latency
import com.gozar.app.data.ServerEntity
import com.gozar.app.data.Settings
import com.gozar.app.data.SettingsRepository
import com.gozar.app.net.LatencyTester
import com.gozar.app.net.RealDelay
import com.gozar.app.net.SpeedTest
import com.gozar.app.net.Warp
import com.gozar.app.parser.ConfigParser
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ServerSocket

/**
 * Owns the tunnel.
 *
 * sing-box always drives the TUN device. When the user picks the Xray core,
 * Xray is started first as a loopback SOCKS5 proxy and sing-box is pointed at
 * it, so the packet path is identical either way.
 */
class GozarVpnService : android.net.VpnService(), CommandServerHandler {

    private val tag = "GozarVpn"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val notifications by lazy { ServiceNotification(this) }
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var database: GozarDatabase

    private var platform: PlatformInterfaceImpl? = null
    private var commandServer: CommandServer? = null
    private val upstream by lazy { UnderlyingNetwork(applicationContext) }

    private var currentSettings: Settings = Settings()
    private var startedAt: Long = 0
    private var localProxyPort: Int = 0

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        database = GozarDatabase.get(applicationContext)
        notifications.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }

            else -> {
                if (VpnState.status.value == ConnectionStatus.DISCONNECTED) {
                    // A null intent means START_STICKY brought us back after the
                    // process was killed, which is the case "reconnect after a
                    // drop" is about.
                    val systemRestart = intent == null
                    val serverId = intent?.getLongExtra(EXTRA_SERVER_ID, 0L) ?: 0L
                    startTunnel(serverId, systemRestart)
                }
                return START_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.w(tag, "VPN permission revoked by the system")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------- Lifecycle

    private fun startTunnel(requestedServerId: Long, systemRestart: Boolean = false) {
        VpnState.clearError()
        VpnState.setStatus(ConnectionStatus.CONNECTING)
        ServiceCompat.startForeground(
            this,
            ServiceNotification.NOTIFICATION_ID,
            notifications.build(
                getString(R.string.app_name),
                getString(R.string.state_connecting),
            ),
            ServiceNotification.foregroundServiceType,
        )

        scope.launch {
            try {
                val settings = settingsRepository.current()
                currentSettings = settings

                if (systemRestart && !settings.autoReconnect) {
                    Log.i(tag, "restarted by the system but auto-reconnect is off")
                    stopTunnel()
                    return@launch
                }

                val connected = connectWithFailover(requestedServerId, settings)
                startedAt = System.currentTimeMillis()
                VpnState.setStatus(ConnectionStatus.CONNECTED)
                VpnState.setProgress(null)
                notifications.update(getString(R.string.state_connected), connected.name)
                monitorTraffic(connected.name)
            } catch (error: Throwable) {
                Log.e(tag, "start failed", error)
                Diagnostics.log("start failed: ${error.javaClass.simpleName}: ${error.message}")
                VpnState.setError(error.message ?: error.javaClass.simpleName)
                stopTunnel()
            }
        }
    }

    /**
     * Brings the tunnel up and proves it actually carries traffic.
     *
     * A TCP handshake probe — which is all the bulk server test can afford —
     * only shows that something is listening. Plenty of scraped configs accept
     * a connection and then fail at TLS or authentication, which surfaces as
     * "connected, but nothing loads". So each candidate is verified with a real
     * request through the core's loopback inbound, and a failure moves on to
     * the next one via `startOrReloadService`, reusing the same TUN.
     *
     * @return the server that verified successfully.
     */
    private suspend fun connectWithFailover(
        requestedServerId: Long,
        settings: Settings,
    ): ServerEntity {
        val probePort = findFreePort()
        localProxyPort = probePort
        prepareCore()
        measureShortlist(settings)

        val tried = HashSet<Long>()
        var round = 1
        var lastError: String? = null

        // Runs until something works or the user disconnects, which cancels this
        // coroutine. A fixed budget of attempts was the wrong shape: with tens of
        // thousands of scraped servers, most of them dead on any given day,
        // stopping after six left the user pressing a button that had quietly
        // given up.
        while (true) {
            currentCoroutineContext().ensureActive()
            val candidates = candidateServers(requestedServerId, settings, round)
            if (candidates.isEmpty()) {
                throw IllegalStateException(getString(R.string.error_no_server))
            }
            val fresh = candidates.filterNot { it.id in tried }
            if (fresh.isEmpty()) {
                Diagnostics.log(
                    "round $round exhausted (${lastError ?: "no reason recorded"}); widening",
                )
                tried.clear()
                round++
                // A pause so a transient outage is not hammered, and so a test
                // sweep running alongside can re-rank the list first.
                delay(RETRY_PAUSE_MS)
                continue
            }
            Diagnostics.log("round $round — ${fresh.size} candidates, probe port $probePort")

            for ((index, server) in fresh.withIndex()) {
                currentCoroutineContext().ensureActive()
                tried += server.id
                val failure = attemptServer(server, settings, probePort, round, index + 1, fresh.size)
                if (failure == null) return server
                lastError = failure
            }
        }
    }

    /**
     * Times a real request through the best few servers before choosing one.
     *
     * The handshake sweep only proves something is listening on the port, and
     * scraped lists are full of servers that answer it and then carry nothing.
     * So the shortlist is loaded into a core that has no TUN and no inbound —
     * it only answers Clash API delay queries — and each candidate gets one
     * HTTPS request put through it over the ordinary connection. That runs on
     * the same command server the tunnel will use, one config reload earlier,
     * so nothing extra has to be started.
     *
     * Best effort throughout: if any of it fails the connect loop still runs,
     * just ranked on handshakes as before.
     */
    private suspend fun measureShortlist(settings: Settings) {
        // Skipped when the ranking is still fresh. The stage costs a request per
        // server, which is half a minute the user would spend staring at a
        // connect button — worth paying once, not on every connect.
        val fresh = runCatching {
            database.serverDao().provenCount(System.currentTimeMillis() - MEASUREMENT_TTL_MS)
        }.getOrDefault(0)
        if (fresh >= ENOUGH_PROVEN) {
            Diagnostics.log("skipping measurement — $fresh servers measured recently")
            return
        }

        val shortlist = runCatching { database.serverDao().measureCandidates(REAL_TEST_SIZE) }
            .getOrNull()
            .orEmpty()
            .filter { settings.allowsProtocol(it.protocol) }
        if (shortlist.isEmpty()) return

        val candidates = shortlist.mapNotNull { server ->
            ConfigParser.parse(server.raw)?.let { server.id to it }
        }
        if (candidates.isEmpty()) return

        val apiPort = findFreePort()
        val testProxyPort = findFreePort()
        Diagnostics.log("measuring ${candidates.size} servers through the proxy")
        VpnState.setProgress(getString(R.string.measuring_servers, candidates.size))

        runCatching {
            applyConfig(
                SingBoxConfig.buildTester(
                    candidates = candidates.map { (id, proxy) -> RealDelay.tagFor(id) to proxy },
                    settings = settings,
                    clashApiPort = apiPort,
                    localProxyPort = testProxyPort,
                ),
            )
            val delays = RealDelay.measureAll(
                apiPort = apiPort,
                candidates = candidates,
                onResult = { outcome ->
                    database.serverDao().updateRealDelay(
                        outcome.id,
                        outcome.delayMs,
                        System.currentTimeMillis(),
                    )
                },
                onProgress = { progress ->
                    VpnState.setProgress(
                        getString(R.string.measuring_progress, progress.done, progress.total),
                    )
                },
            )
            Diagnostics.log(
                "${delays.count { it.delayMs > 0 }} of ${candidates.size} answered",
            )

            // Responsiveness only narrowed the field. What the user is actually
            // choosing on is how fast the thing moves bytes, and no amount of
            // delay measurement answers that.
            val fastest = delays.filter { it.delayMs > 0 }
                .sortedBy { it.delayMs }
                .take(SPEED_TEST_SIZE)
                .map { it.id }
            val speeds = SpeedTest.measureAll(
                apiPort = apiPort,
                proxyPort = testProxyPort,
                candidates = fastest,
                onResult = { outcome ->
                    database.serverDao().updateSpeed(
                        outcome.id,
                        outcome.kbPerSecond,
                        System.currentTimeMillis(),
                    )
                },
                onProgress = { progress ->
                    VpnState.setProgress(
                        getString(R.string.speed_progress, progress.done, progress.total),
                    )
                },
            )
            Diagnostics.log(
                "fastest of ${speeds.size} measured: " +
                    "${speeds.maxOfOrNull { it.kbPerSecond } ?: 0} KB/s",
            )
        }.onFailure { error ->
            Diagnostics.log("measurement stage failed: ${error.message}")
        }
    }

    /** @return null once the tunnel is verified, otherwise why it is not. */
    private suspend fun attemptServer(
        server: ServerEntity,
        settings: Settings,
        probePort: Int,
        round: Int,
        index: Int,
        total: Int,
    ): String? {
        val parsed = ConfigParser.parse(server.raw) ?: return "unparseable config"
        // A WARP link carries no credentials; one free account is registered on
        // demand and reused. Failing here must not stop the pass — every other
        // candidate is still worth trying.
        val proxy = try {
            Warp.fill(parsed)
        } catch (error: Throwable) {
            Diagnostics.log("WARP unavailable: ${error.message}")
            return "WARP unavailable: ${error.message}"
        }

        VpnState.setActiveServer(server.id)
        // Shape of the config only — never the credentials in it.
        Diagnostics.log(
            "round $round try $index/$total: ${proxy.protocol.label} " +
                "${proxy.server}:${proxy.port} net=${proxy.network} sec=${proxy.security}" +
                if (server.domesticEntry) " [domestic entry]" else "",
        )
        VpnState.setProgress(
            getString(R.string.trying_server_round, index, total, round, server.name),
        )
        notifications.update(getString(R.string.state_connecting), server.name)

        try {
            applyConfig(SingBoxConfig.build(proxy, settings, probePort))
        } catch (error: Throwable) {
            Diagnostics.log("core rejected ${server.name}: ${error.message}")
            markServerFailed(server)
            return "core error: ${error.message}"
        }

        // Give the inbound a moment to bind before probing through it.
        delay(700)

        // Separating these two failures matters: if the loopback inbound is not
        // even listening the fault is ours, not the server's, and every
        // candidate would fail identically. Retrying that forever would spin, so
        // it ends the attempt outright.
        if (!isLocalInboundUp(probePort)) {
            Diagnostics.log("local inbound 127.0.0.1:$probePort is not listening")
            throw IllegalStateException("core inbound 127.0.0.1:$probePort never came up")
        }

        val delayMs = LatencyTester.measureTunnelDelay(probePort)
        if (delayMs > 0) {
            Diagnostics.log("OK ${server.name} — ${delayMs}ms through the tunnel")
            // Recorded as a real delay, not a handshake: this number came from a
            // request that actually went through the proxy.
            database.serverDao().updateRealDelay(
                id = server.id,
                realDelay = delayMs,
                time = System.currentTimeMillis(),
            )
            VpnState.setStats(VpnState.stats.value.copy(delayMs = delayMs))
            settingsRepository.setSelectedServer(server.id)
            return null
        }

        Diagnostics.log("${server.name} came up but carried no traffic")
        markServerFailed(server)
        return "no traffic"
    }

    private suspend fun markServerFailed(server: ServerEntity) {
        // Also a real-delay result: the server was tried through the tunnel and
        // carried nothing, which is worth more than any handshake it may pass.
        database.serverDao().updateRealDelay(
            id = server.id,
            realDelay = Latency.FAILED,
            time = System.currentTimeMillis(),
        )
    }

    /**
     * The server to try first, followed by the next best alternatives so a dead
     * config does not strand the user on a connect button that does nothing.
     */
    private suspend fun candidateServers(
        requestedServerId: Long,
        settings: Settings,
        round: Int,
    ): List<ServerEntity> {
        val dao = database.serverDao()
        val preferredId = when {
            requestedServerId > 0 -> requestedServerId
            !settings.autoSelectFastest -> settings.selectedServerId
            else -> 0L
        }
        val preferred = if (preferredId > 0) dao.byId(preferredId) else null
        // Each failed pass widens the net instead of giving up. The protocol
        // filter is applied after the query rather than inside it so the SQL
        // stays one indexed ORDER BY rather than a variable-length IN clause.
        val width = (FIRST_ROUND_WIDTH * round).coerceAtMost(MAX_CANDIDATES)
        val alternatives = dao.best(width)
        // A last tier of servers whose entry point is inside Iran. When
        // international routing is cut these are the only ones still reachable,
        // and they cost nothing to include when it is not — they simply lose on
        // latency and never get tried.
        val domestic = dao.bestDomestic(DOMESTIC_FALLBACK_ATTEMPTS)
        return (listOfNotNull(preferred) + alternatives + domestic)
            .distinctBy { it.id }
            .filter { settings.allowsProtocol(it.protocol) }
    }

    /** One-time core setup; the command server outlives individual configs. */
    private fun prepareCore() {
        if (commandServer != null) return
        val basePath = filesDir
        val workingPath = File(filesDir, "work").also { it.mkdirs() }
        val tempPath = File(cacheDir, "box").also { it.mkdirs() }
        setupLibbox(basePath, workingPath, tempPath)

        // Must be tracking before the core starts: the DNS transport and the
        // interface monitor both depend on knowing the non-VPN network.
        upstream.start()

        val platformInterface = PlatformInterfaceImpl(this, upstream) { currentSettings }
        platform = platformInterface

        val server = Libbox.newCommandServer(this, platformInterface)
        server.start()
        commandServer = server
    }

    /** Swaps the running configuration without tearing the tunnel down. */
    private fun applyConfig(configJson: String) {
        val server = commandServer ?: error("command server is not running")
        Log.d(tag, "sing-box config:\n$configJson")
        val overrides = OverrideOptions()
        overrides.setAutoRedirect(false)
        overrides.setIncludePackage(StringList(emptyList()))
        overrides.setExcludePackage(StringList(emptyList()))
        server.startOrReloadService(configJson, overrides)
    }

    /** Asks the OS for a free loopback port for the core's local inbound. */
    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    /** True when the core's loopback inbound is accepting connections. */
    private suspend fun isLocalInboundUp(port: Int): Boolean = withContext(Dispatchers.IO) {
        repeat(3) { attempt ->
            val socket = java.net.Socket()
            try {
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1_500)
                return@withContext true
            } catch (_: Throwable) {
                if (attempt < 2) delay(400)
            } finally {
                runCatching { socket.close() }
            }
        }
        false
    }

    private fun setupLibbox(basePath: File, workingPath: File, tempPath: File) {
        if (libboxReady) return
        val options = SetupOptions()
        options.setBasePath(basePath.absolutePath)
        options.setWorkingPath(workingPath.absolutePath)
        options.setTempPath(tempPath.absolutePath)
        // Go's stack probing misbehaves on some Android kernels; the fix routes
        // platform callbacks through a fresh goroutine.
        options.setFixAndroidStack(true)
        options.setLogMaxLines(200L)
        options.setDebug(false)
        Libbox.setup(options)
        libboxReady = true
    }

    private fun stopTunnel() {
        if (VpnState.status.value == ConnectionStatus.DISCONNECTED) {
            stopSelfSafely()
            return
        }
        VpnState.setStatus(ConnectionStatus.STOPPING)

        runCatching { commandServer?.closeService() }
            .onFailure { Log.w(tag, "closeService: ${it.message}") }
        runCatching { commandServer?.close() }
            .onFailure { Log.w(tag, "close: ${it.message}") }
        commandServer = null

        platform?.closeTun()
        platform = null
        upstream.stop()

        VpnState.setStatus(ConnectionStatus.DISCONNECTED)
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- Stats

    private suspend fun monitorTraffic(serverName: String) {
        val uid = Process.myUid()
        var lastRx = TrafficStats.getUidRxBytes(uid)
        var lastTx = TrafficStats.getUidTxBytes(uid)
        val baseRx = lastRx
        val baseTx = lastTx

        val probePort = localProxyPort
        scope.launch {
            // One end-to-end probe once the tunnel has settled.
            delay(2_000)
            val measured = LatencyTester.measureTunnelDelay(probePort)
            if (measured > 0) {
                VpnState.setStats(VpnState.stats.value.copy(delayMs = measured))
            }
        }

        while (scope.isActive && VpnState.status.value == ConnectionStatus.CONNECTED) {
            delay(1_000)
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            if (rx == TrafficStats.UNSUPPORTED.toLong()) break

            val currentDelay = VpnState.stats.value.delayMs
            VpnState.setStats(
                TunnelStats(
                    // Our own UID carries the tunnel's upstream sockets, because
                    // the app itself is excluded from the VPN. That makes this a
                    // close approximation of tunnel throughput, not an exact count.
                    uplinkBytes = (tx - baseTx).coerceAtLeast(0),
                    downlinkBytes = (rx - baseRx).coerceAtLeast(0),
                    uplinkSpeed = (tx - lastTx).coerceAtLeast(0),
                    downlinkSpeed = (rx - lastRx).coerceAtLeast(0),
                    connectedSince = startedAt,
                    delayMs = currentDelay,
                ),
            )
            lastRx = rx
            lastTx = tx
            notifications.update(getString(R.string.state_connected), serverName)
        }
    }

    // ----------------------------------------------- CommandServerHandler

    override fun serviceStop() {
        stopTunnel()
    }

    override fun serviceReload() {
        val serverId = VpnState.activeServerId.value
        stopTunnel()
        startTunnel(serverId)
    }

    override fun getSystemProxyStatus(): SystemProxyStatus {
        val status = SystemProxyStatus()
        status.setAvailable(false)
        status.setEnabled(false)
        return status
    }

    override fun setSystemProxyEnabled(enabled: Boolean) = Unit

    /** The core's own messages — its errors are the ones worth seeing. */
    override fun writeDebugMessage(message: String?) {
        val text = message.orEmpty().trim()
        if (text.isEmpty()) return
        Log.d(tag, text)
        if (text.contains("error", ignoreCase = true) ||
            text.contains("failed", ignoreCase = true) ||
            text.contains("fatal", ignoreCase = true)
        ) {
            Diagnostics.log("core: ${text.take(220)}")
        }
    }

    companion object {
        const val ACTION_START = "com.gozar.app.START"
        const val ACTION_STOP = "com.gozar.app.STOP"
        const val EXTRA_SERVER_ID = "server_id"

        /** Candidates in the first pass; each failed round multiplies this. */
        private const val FIRST_ROUND_WIDTH = 6

        /** Ceiling on how wide a single pass gets, however many rounds it takes. */
        private const val MAX_CANDIDATES = 400

        /** Extra attempts reserved for domestic-entry servers. */
        private const val DOMESTIC_FALLBACK_ATTEMPTS = 3

        /** Pause between exhausted passes, so an outage is not hammered. */
        private const val RETRY_PAUSE_MS = 1_500L

        /**
         * How many of the best-pinged servers get a real request put through
         * them before connecting. Each costs a handshake and an HTTPS round
         * trip on the user's own connection, so this stays a shortlist.
         */
        private const val REAL_TEST_SIZE = 50

        /**
         * How many of the best-responding get a download put through them.
         * Each spends up to 1.5 MB of the user's own data and runs alone so the
         * measurements do not share the line, so this stays small.
         */
        private const val SPEED_TEST_SIZE = 8

        /** Enough recently measured servers to connect without measuring again. */
        private const val ENOUGH_PROVEN = 5

        /** How long a measurement is trusted before it is worth redoing. */
        private const val MEASUREMENT_TTL_MS = 6 * 60 * 60 * 1000L

        @Volatile
        private var libboxReady = false

        fun start(context: Context, serverId: Long = 0) {
            val intent = Intent(context, GozarVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SERVER_ID, serverId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GozarVpnService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
