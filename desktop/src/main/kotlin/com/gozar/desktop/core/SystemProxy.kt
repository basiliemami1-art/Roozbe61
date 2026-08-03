package com.gozar.desktop.core

import java.util.concurrent.TimeUnit

/**
 * Points Windows at the local proxy, and puts it back afterwards.
 *
 * Writing the registry alone is not enough: WinINET caches the settings, and
 * applications keep using the old values until it is told to refresh. The
 * refresh normally needs a native call, so a short PowerShell shim invokes
 * `InternetSetOption` instead of adding a JNI dependency for two constants.
 */
object SystemProxy {

    private const val KEY =
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

    /** Hosts that must not go through the tunnel, including anything local. */
    private const val BYPASS = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;" +
        "172.19.*;172.2*;172.30.*;172.31.*;192.168.*;<local>"

    val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Volatile
    private var applied = false

    fun enable(port: Int): Boolean {
        if (!isWindows) return false
        val ok = reg("ProxyEnable", "REG_DWORD", "1") &&
            reg("ProxyServer", "REG_SZ", "127.0.0.1:$port") &&
            reg("ProxyOverride", "REG_SZ", BYPASS)
        if (ok) {
            applied = true
            notifyWinInet()
        }
        return ok
    }

    fun disable() {
        if (!isWindows || !applied) return
        applied = false
        reg("ProxyEnable", "REG_DWORD", "0")
        notifyWinInet()
    }

    private fun reg(name: String, type: String, value: String): Boolean = runCatching {
        val process = ProcessBuilder(
            "reg", "add", KEY, "/v", name, "/t", type, "/d", value, "/f",
        ).redirectErrorStream(true).start()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    /**
     * INTERNET_OPTION_SETTINGS_CHANGED (39) then INTERNET_OPTION_REFRESH (37):
     * without both, already-running applications keep the previous settings.
     */
    private fun notifyWinInet() {
        val script = """
            ${'$'}sig = '[DllImport("wininet.dll", SetLastError=true)]
              public static extern bool InternetSetOption(IntPtr h, int o, IntPtr b, int l);'
            ${'$'}t = Add-Type -MemberDefinition ${'$'}sig -Name W -Namespace G -PassThru
            [void]${'$'}t::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0)
            [void]${'$'}t::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0)
        """.trimIndent()
        runCatching {
            ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script,
            ).redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
        }
    }
}
