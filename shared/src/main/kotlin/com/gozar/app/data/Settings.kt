package com.gozar.app.data

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class PerAppMode { OFF, INCLUDE, EXCLUDE }

/**
 * Everything the config generator and the connect logic need to know about the
 * user's choices. Kept free of any storage concern so the Android app can back
 * it with DataStore and the desktop app with a JSON file.
 */
data class Settings(
    val language: String = "fa",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val selectedServerId: Long = 0,
    val bypassIran: Boolean = true,
    val bypassLan: Boolean = true,
    val blockAds: Boolean = false,
    val ipv6: Boolean = false,
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "8.8.8.8",
    val mtu: Int = 9000,
    val tunStack: String = "mixed",
    val perAppMode: PerAppMode = PerAppMode.OFF,
    val perAppList: Set<String> = emptySet(),
    /**
     * Protocol names ([com.gozar.app.model.Protocol]) the user is willing to
     * use. Empty means no restriction, which is also what a fresh install gets —
     * an empty set cannot accidentally exclude a protocol added in a later
     * version, the way an explicit "all" list would.
     */
    val allowedProtocols: Set<String> = emptySet(),
    val autoSelectFastest: Boolean = true,
    val autoUpdateSources: Boolean = true,
    val connectOnBoot: Boolean = false,
    val autoReconnect: Boolean = true,
    val testConcurrency: Int = 64,
    val testTimeoutSeconds: Int = 4,
    val maxServers: Int = 10_000,
    val onboarded: Boolean = false,
) {
    /** True when [allowedProtocols] is empty, or names this protocol. */
    fun allowsProtocol(name: String): Boolean =
        allowedProtocols.isEmpty() || name in allowedProtocols
}
