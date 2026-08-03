package com.gozar.desktop.core

import com.gozar.app.core.SingBoxConfig
import com.gozar.app.data.Settings
import com.gozar.app.model.ProxyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Runs the sing-box core as a child process.
 *
 * Desktop deliberately does not use a TUN device. TUN on Windows needs the
 * Wintun driver and an elevation prompt on every start; a loopback proxy plus
 * the system proxy setting covers browsers and most applications, installs
 * without administrator rights, and cannot leave the machine in a broken
 * routing state if the app is killed.
 */
class SingBoxProcess(
    private val workDir: File,
    private val binary: File,
    private val onLog: (String) -> Unit,
) {

    @Volatile
    private var process: Process? = null

    /** Where the core publishes its byte counters; valid while it is running. */
    @Volatile
    var clashApiPort: Int = 0
        private set

    val isRunning: Boolean get() = process?.isAlive == true

    /** @return the loopback port the core is listening on. */
    fun start(proxy: ProxyConfig, settings: Settings): Int {
        stop()
        require(binary.exists()) { "sing-box binary is missing at ${binary.absolutePath}" }

        // Both at once. Two separate calls each open a socket, read the port and
        // close it, so the OS is free to hand back the same number twice — and
        // then the core cannot bind its second listener and exits, which looks
        // exactly like every server being dead.
        val (port, apiPort) = findFreePorts(2)
        val config = SingBoxConfig.build(
            proxy = proxy,
            settings = settings,
            localProxyPort = port,
            tun = false,
            clashApiPort = apiPort,
        )
        clashApiPort = apiPort
        workDir.mkdirs()
        val configFile = File(workDir, "config.json")
        configFile.writeText(config)

        val started = ProcessBuilder(
            binary.absolutePath,
            "run",
            "-c", configFile.absolutePath,
            "-D", workDir.absolutePath,
        )
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        process = started

        // Kept as well as logged: when the core refuses a config it says why and
        // exits within milliseconds, and that sentence is the entire diagnosis.
        val firstLines = ArrayDeque<String>()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    synchronized(firstLines) {
                        if (firstLines.size < KEPT_LINES) firstLines.addLast(line.take(300))
                    }
                    onLog("core: ${line.take(300)}")
                }
            }
        }

        // A core that died on startup would otherwise be indistinguishable from
        // a dead server: every candidate would fail its inbound check after six
        // seconds and the app would retry for ever without ever saying why.
        if (started.waitFor(STARTUP_GRACE_MS, TimeUnit.MILLISECONDS)) {
            val reason = synchronized(firstLines) { firstLines.joinToString(" | ") }
            process = null
            clashApiPort = 0
            error(
                "sing-box exited immediately (code ${started.exitValue()})" +
                    if (reason.isNotBlank()) ": $reason" else "",
            )
        }
        return port
    }

    fun stop() {
        val running = process ?: return
        process = null
        clashApiPort = 0
        runCatching {
            running.destroy()
            if (!running.waitFor(3, TimeUnit.SECONDS)) running.destroyForcibly()
        }
    }

    companion object {
        /** Long enough for a config rejection, short enough not to be felt. */
        private const val STARTUP_GRACE_MS = 700L
        private const val KEPT_LINES = 6

        fun findFreePort(): Int = findFreePorts(1).first()

        /** Distinct ports: every socket is held open until all are chosen. */
        fun findFreePorts(count: Int): List<Int> {
            val sockets = List(count) { ServerSocket(0) }
            val ports = sockets.map { it.localPort }
            sockets.forEach { runCatching { it.close() } }
            return ports
        }

        /**
         * Looks for the bundled binary next to the installed app, then falls
         * back to the layout used when running from Gradle.
         */
        fun locate(): File {
            val packaged = System.getProperty("compose.application.resources.dir")
            if (packaged != null) {
                val candidate = File(packaged, "sing-box.exe")
                if (candidate.exists()) return candidate
            }
            return File("desktop/resources/windows/sing-box.exe").absoluteFile
        }
    }
}
