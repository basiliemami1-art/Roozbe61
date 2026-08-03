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

        private const val BINARY = "sing-box.exe"

        /**
         * Every place the core could reasonably be, in order.
         *
         * Deliberately more than one: the packaged layout depends on how the
         * Compose plugin lays out app resources, and getting that wrong means
         * the core is simply absent — which shows up as every server in the
         * list failing to connect, with nothing pointing at the real cause.
         * Searching a few candidates and reporting all of them costs nothing
         * and makes that failure name itself.
         */
        fun searchPath(): List<File> {
            val paths = ArrayList<File>()
            System.getProperty("compose.application.resources.dir")?.let { dir ->
                paths += File(dir, BINARY)
                paths += File(File(dir, "windows"), BINARY)
                paths += File(File(dir).parentFile ?: File(dir), BINARY)
            }
            // A jpackage image puts the runtime under the install directory, so
            // its grandparent is the application root.
            System.getProperty("java.home")?.let { home ->
                val root = File(home).parentFile
                if (root != null) {
                    paths += File(root, BINARY)
                    paths += File(File(root, "app"), BINARY)
                    paths += File(File(root, "app/resources"), BINARY)
                }
            }
            paths += File("desktop/resources/windows/$BINARY").absoluteFile
            paths += File(BINARY).absoluteFile
            return paths
        }

        fun locate(): File = searchPath().firstOrNull { it.isFile } ?: searchPath().first()

        /** One line at startup, so the log answers "is the core there?" first. */
        fun describeLocation(): String {
            val found = searchPath().firstOrNull { it.isFile }
            return if (found != null) {
                "core: ${found.absolutePath} (${found.length() / 1_048_576} MB)"
            } else {
                "core NOT FOUND. Looked in:\n" +
                    searchPath().joinToString("\n") { "  ${it.absolutePath}" }
            }
        }
    }
}
