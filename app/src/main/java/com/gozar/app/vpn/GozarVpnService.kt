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
import com.gozar.app.core.XrayConfig
import com.gozar.app.data.GozarDatabase
import com.gozar.app.data.Settings
import com.gozar.app.data.SettingsRepository
import com.gozar.app.model.CoreType
import com.gozar.app.model.ProxyConfig
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
import java.io.File

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
    private var xray: XrayRunner? = null

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

                val server = resolveServer(requestedServerId, settings)
                    ?: throw IllegalStateException(getString(R.string.error_no_server))

                val proxy = ConfigParser.parse(server.raw)
                    ?: throw IllegalStateException("Could not parse the selected config")

                VpnState.setActiveServer(server.id)
                launchCores(proxy, settings)

                startedAt = System.currentTimeMillis()
                VpnState.setStatus(ConnectionStatus.CONNECTED)
                notifications.update(getString(R.string.state_connected), proxy.name)
                monitorTraffic(proxy.name)
            } catch (error: Throwable) {
                Log.e(tag, "start failed", error)
                VpnState.setError(error.message ?: error.javaClass.simpleName)
                stopTunnel()
            }
        }
    }

    private suspend fun resolveServer(requestedServerId: Long, settings: Settings) =
        when {
            requestedServerId > 0 -> database.serverDao().byId(requestedServerId)
            settings.autoSelectFastest -> database.serverDao().fastest()
                ?: database.serverDao().byId(settings.selectedServerId)
            else -> database.serverDao().byId(settings.selectedServerId)
                ?: database.serverDao().fastest()
        }

    private fun launchCores(proxy: ProxyConfig, settings: Settings) {
        val basePath = filesDir
        val workingPath = File(filesDir, "work").also { it.mkdirs() }
        val tempPath = File(cacheDir, "box").also { it.mkdirs() }

        setupLibbox(basePath, workingPath, tempPath)

        var chainPort: Int? = null
        if (settings.core == CoreType.XRAY) {
            if (!proxy.protocol.supportedBy(CoreType.XRAY)) {
                throw IllegalStateException(
                    "${proxy.protocol.label} needs the sing-box core",
                )
            }
            val port = XrayRunner.findFreePort()
            val runner = XrayRunner(applicationContext)
            runner.start(XrayConfig.build(proxy, port), port)
            xray = runner
            chainPort = port
        }

        val probePort = XrayRunner.findFreePort()
        localProxyPort = probePort

        val configJson = SingBoxConfig.build(proxy, settings, chainPort, probePort)
        Log.d(tag, "sing-box config:\n$configJson")

        val platformInterface = PlatformInterfaceImpl(this) { currentSettings }
        platform = platformInterface

        val server = Libbox.newCommandServer(this, platformInterface)
        server.start()
        commandServer = server

        val overrides = OverrideOptions()
        overrides.setAutoRedirect(false)
        overrides.setIncludePackage(StringList(emptyList()))
        overrides.setExcludePackage(StringList(emptyList()))
        server.startOrReloadService(configJson, overrides)
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

        xray?.stop()
        xray = null

        platform?.closeTun()
        platform = null

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

    override fun writeDebugMessage(message: String?) {
        Log.d(tag, message.orEmpty())
    }

    companion object {
        const val ACTION_START = "com.gozar.app.START"
        const val ACTION_STOP = "com.gozar.app.STOP"
        const val EXTRA_SERVER_ID = "server_id"

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
