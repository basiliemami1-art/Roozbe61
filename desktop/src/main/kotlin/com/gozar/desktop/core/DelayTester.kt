package com.gozar.desktop.core

import com.gozar.app.core.SingBoxConfig
import com.gozar.app.data.Settings
import com.gozar.app.model.ProxyConfig
import com.gozar.app.net.RealDelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a second, short-lived core purely to time requests through candidate
 * proxies.
 *
 * Separate from the live one on purpose: measuring must not disturb a tunnel
 * the user is already browsing through, and it must not touch the system proxy.
 * This core has no TUN and no inbound at all — it only answers Clash API delay
 * queries, and its dials go over the ordinary connection, which is the path the
 * real tunnel will use too.
 */
class DelayTester(
    private val workDir: File,
    private val binary: File,
    private val onLog: (String) -> Unit,
) {

    @Volatile
    private var process: Process? = null

    /**
     * @param candidates id to config, already shortlisted.
     * @return the measurements; empty if the core never came up.
     */
    suspend fun measure(
        candidates: List<Pair<Long, ProxyConfig>>,
        settings: Settings,
        onResult: suspend (RealDelay.Outcome) -> Unit = {},
        onProgress: (RealDelay.Progress) -> Unit = {},
    ): List<RealDelay.Outcome> {
        if (candidates.isEmpty()) return emptyList()
        if (!binary.exists()) {
            onLog("cannot measure: sing-box is missing at ${binary.absolutePath}")
            return emptyList()
        }

        val apiPort = SingBoxProcess.findFreePort()
        val config = SingBoxConfig.buildTester(
            candidates = candidates.map { (id, proxy) -> RealDelay.tagFor(id) to proxy },
            settings = settings,
            clashApiPort = apiPort,
        )
        val dir = File(workDir, "test").apply { mkdirs() }
        val configFile = File(dir, "tester.json")

        return try {
            withContext(Dispatchers.IO) {
                configFile.writeText(config)
                start(dir, configFile)
            }
            RealDelay.measureAll(
                apiPort = apiPort,
                candidates = candidates,
                onResult = onResult,
                onProgress = onProgress,
            )
        } finally {
            stop()
        }
    }

    private fun start(dir: File, configFile: File) {
        stop()
        val started = ProcessBuilder(
            binary.absolutePath,
            "run",
            "-c", configFile.absolutePath,
            "-D", dir.absolutePath,
        )
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        process = started
        // Drained rather than logged line by line: fifty outbounds produce a
        // lot of noise, and only a refusal to start is worth surfacing.
        Thread({
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    if (line.contains("FATAL", ignoreCase = true) ||
                        line.contains("error", ignoreCase = true)
                    ) {
                        onLog("tester: ${line.take(200)}")
                    }
                }
            }
        }, "gozar-tester-log").apply { isDaemon = true }.start()
    }

    fun stop() {
        val running = process ?: return
        process = null
        runCatching {
            running.destroy()
            if (!running.waitFor(3, TimeUnit.SECONDS)) running.destroyForcibly()
        }
    }
}
