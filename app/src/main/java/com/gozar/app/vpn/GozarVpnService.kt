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
import kotlinx.coroutines.delay
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
        val candidates = candidateServers(requestedServerId, settings)
        if (candidates.isEmpty()) {
            throw IllegalStateException(getString(R.string.error_no_server))
        }

        val probePort = findFreePort()
        localProxyPort = probePort
        Diagnostics.log("connecting — ${candidates.size} candidates, probe port $probePort")
        prepareCore()

        var lastError: String? = null
        for ((index, server) in candidates.withIndex()) {
            val proxy = ConfigParser.parse(server.raw)
            if (proxy == null) {
                lastError = "unparseable config"
                continue
            }

            VpnState.setActiveServer(server.id)
            // Shape of the config only — never the credentials in it.
            Diagnostics.log(
                "try ${index + 1}/${candidates.size}: ${proxy.protocol.label} " +
                    "${proxy.server}:${proxy.port} net=${proxy.network} sec=${proxy.security}" +
                    if (server.domesticEntry) " [domestic entry]" else "",
            )
            VpnState.setProgress(
                getString(R.string.trying_server, index + 1, candidates.size, server.name),
            )
            notifications.update(getString(R.string.state_connecting), server.name)

            try {
                applyConfig(SingBoxConfig.build(proxy, settings, probePort))
            } catch (error: Throwable) {
                Diagnostics.log("core rejected ${server.name}: ${error.message}")
                lastError = "core error: ${error.message}"
                markServerFailed(server)
                continue
            }

            // Give the inbound a moment to bind before probing through it.
            delay(700)

            // Separating these two failures matters: if the loopback inbound is
            // not even listening the fault is ours, not the server's, and every
            // candidate would fail identically.
            if (!isLocalInboundUp(probePort)) {
                Diagnostics.log("local inbound 127.0.0.1:$probePort is not listening")
                lastError = "core inbound not listening"
                break
            }

            val delayMs = LatencyTester.measureTunnelDelay(probePort)
            if (delayMs > 0) {
                Diagnostics.log("OK ${server.name} — ${delayMs}ms through the tunnel")
                database.serverDao().updateLatency(
                    id = server.id,
                    latency = delayMs,
                    weight = ServerEntity.weightFor(delayMs),
                    time = System.currentTimeMillis(),
                )
                VpnState.setStats(VpnState.stats.value.copy(delayMs = delayMs))
                settingsRepository.setSelectedServer(server.id)
                return server
            }

            Diagnostics.log("${server.name} came up but carried no traffic")
            lastError = "no traffic"
            markServerFailed(server)
        }

        throw IllegalStateException(
            getString(R.string.error_all_failed, candidates.size) +
                (lastError?.let { " ($it)" } ?: ""),
        )
    }

    private suspend fun markServerFailed(server: ServerEntity) {
        database.serverDao().updateLatency(
            id = server.id,
            latency = Latency.FAILED,
            weight = ServerEntity.weightFor(Latency.FAILED),
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
    ): List<ServerEntity> {
        val dao = database.serverDao()
        val preferredId = when {
            requestedServerId > 0 -> requestedServerId
            !settings.autoSelectFastest -> settings.selectedServerId
            else -> 0L
        }
        val preferred = if (preferredId > 0) dao.byId(preferredId) else null
        val alternatives = dao.best(MAX_CONNECT_ATTEMPTS)
        // A last tier of servers whose entry point is inside Iran. When
        // international routing is cut these are the only ones still reachable,
        // and they cost nothing to include when it is not — they simply lose on
        // latency and never get tried.
        val domestic = dao.bestDomestic(DOMESTIC_FALLBACK_ATTEMPTS)
        return (listOfNotNull(preferred) + alternatives + domestic)
            .distinctBy { it.id }
            .take(MAX_CONNECT_ATTEMPTS + DOMESTIC_FALLBACK_ATTEMPTS)
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

        /** How many servers to try before giving up on a connect request. */
        private const val MAX_CONNECT_ATTEMPTS = 6

        /** Extra attempts reserved for domestic-entry servers. */
        private const val DOMESTIC_FALLBACK_ATTEMPTS = 3

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
