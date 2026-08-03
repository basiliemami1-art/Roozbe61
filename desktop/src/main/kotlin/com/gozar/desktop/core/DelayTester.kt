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
            val up = withContext(Dispatchers.IO) {
                configFile.writeText(config)
                start(dir, configFile)
            }
            if (!up) return emptyList()
            RealDelay.measureAll(
                apiPort = apiPort,
                candidates = candidates,
                // Higher than the default: these are 204 responses, and the wait
                // is almost entirely the timeout on servers that never answer.
                // At eight, fifty candidates took over half a minute.
                concurrency = 16,
                onResult = onResult,
                onProgress = onProgress,
            )
        } finally {
            stop()
        }
    }

    /** @return false when the core refused to start; the reason is logged. */
    private fun start(dir: File, configFile: File): Boolean {
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

        // Drained rather than logged line by line: fifty outbounds produce a lot
        // of noise, and only a refusal to start is worth surfacing.
        val firstLines = ArrayDeque<String>()
        Thread({
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    synchronized(firstLines) {
                        if (firstLines.size < KEPT_LINES) firstLines.addLast(line.take(200))
                    }
                }
            }
        }, "gozar-tester-log").apply { isDaemon = true }.start()

        // Without this the measurement would just time out waiting for an API
        // that was never going to answer, and report every server as dead.
        if (started.waitFor(STARTUP_GRACE_MS, TimeUnit.MILLISECONDS)) {
            val reason = synchronized(firstLines) { firstLines.joinToString(" | ") }
            onLog("tester core exited (code ${started.exitValue()}): $reason")
            process = null
            return false
        }
        return true
    }

    private companion object {
        const val STARTUP_GRACE_MS = 700L
        const val KEPT_LINES = 6
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
