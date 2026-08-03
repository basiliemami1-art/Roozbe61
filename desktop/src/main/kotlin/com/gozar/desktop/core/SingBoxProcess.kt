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

        val port = findFreePort()
        val apiPort = findFreePort()
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

        // The core's own log is the only place its errors appear; losing it is
        // what makes "connected but nothing loads" impossible to diagnose.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) onLog("core: ${line.take(300)}")
                }
            }
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
        fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

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
