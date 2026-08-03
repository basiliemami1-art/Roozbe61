package com.gozar.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "gozar_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val THEME = stringPreferencesKey("theme")
        val SELECTED_SERVER = longPreferencesKey("selected_server")
        val BYPASS_IRAN = booleanPreferencesKey("bypass_iran")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val BLOCK_ADS = booleanPreferencesKey("block_ads")
        val IPV6 = booleanPreferencesKey("ipv6")
        val REMOTE_DNS = stringPreferencesKey("remote_dns")
        val DIRECT_DNS = stringPreferencesKey("direct_dns")
        val MTU = intPreferencesKey("mtu")
        val TUN_STACK = stringPreferencesKey("tun_stack")
        val PER_APP_MODE = stringPreferencesKey("per_app_mode")
        val PER_APP_LIST = stringSetPreferencesKey("per_app_list")
        val AUTO_FASTEST = booleanPreferencesKey("auto_fastest")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        val CONNECT_ON_BOOT = booleanPreferencesKey("connect_on_boot")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val TEST_CONCURRENCY = intPreferencesKey("test_concurrency")
        val TEST_TIMEOUT = intPreferencesKey("test_timeout")
        val MAX_SERVERS = intPreferencesKey("max_servers")
        val ONBOARDED = booleanPreferencesKey("onboarded")

        // Bookkeeping rather than a user setting, so it is not part of Settings.
        val SEEDED_SOURCES = stringSetPreferencesKey("seeded_sources")
    }

    /** Built-in source URLs this install has already been offered. */
    suspend fun seededSources(): Set<String> =
        context.dataStore.data.first()[Keys.SEEDED_SOURCES] ?: emptySet()

    suspend fun markSourcesSeeded(urls: Set<String>) = put(Keys.SEEDED_SOURCES, urls)

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    private fun Preferences.toSettings() = Settings(
        language = this[Keys.LANGUAGE] ?: "fa",
        theme = this[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM,
        selectedServerId = this[Keys.SELECTED_SERVER] ?: 0,
        bypassIran = this[Keys.BYPASS_IRAN] ?: true,
        bypassLan = this[Keys.BYPASS_LAN] ?: true,
        blockAds = this[Keys.BLOCK_ADS] ?: false,
        ipv6 = this[Keys.IPV6] ?: false,
        remoteDns = this[Keys.REMOTE_DNS] ?: "https://1.1.1.1/dns-query",
        directDns = this[Keys.DIRECT_DNS] ?: "8.8.8.8",
        mtu = this[Keys.MTU] ?: 9000,
        tunStack = this[Keys.TUN_STACK] ?: "mixed",
        perAppMode = this[Keys.PER_APP_MODE]?.let { runCatching { PerAppMode.valueOf(it) }.getOrNull() }
            ?: PerAppMode.OFF,
        perAppList = this[Keys.PER_APP_LIST] ?: emptySet(),
        autoSelectFastest = this[Keys.AUTO_FASTEST] ?: true,
        autoUpdateSources = this[Keys.AUTO_UPDATE] ?: true,
        connectOnBoot = this[Keys.CONNECT_ON_BOOT] ?: false,
        autoReconnect = this[Keys.AUTO_RECONNECT] ?: true,
        testConcurrency = this[Keys.TEST_CONCURRENCY] ?: 64,
        testTimeoutSeconds = this[Keys.TEST_TIMEOUT] ?: 4,
        maxServers = this[Keys.MAX_SERVERS] ?: 10_000,
        onboarded = this[Keys.ONBOARDED] ?: false,
    )

    suspend fun setLanguage(value: String) = put(Keys.LANGUAGE, value)
    suspend fun setTheme(value: ThemeMode) = put(Keys.THEME, value.name)
    suspend fun setSelectedServer(id: Long) = put(Keys.SELECTED_SERVER, id)
    suspend fun setBypassIran(value: Boolean) = put(Keys.BYPASS_IRAN, value)
    suspend fun setBypassLan(value: Boolean) = put(Keys.BYPASS_LAN, value)
    suspend fun setBlockAds(value: Boolean) = put(Keys.BLOCK_ADS, value)
    suspend fun setIpv6(value: Boolean) = put(Keys.IPV6, value)
    suspend fun setRemoteDns(value: String) = put(Keys.REMOTE_DNS, value)
    suspend fun setDirectDns(value: String) = put(Keys.DIRECT_DNS, value)
    suspend fun setMtu(value: Int) = put(Keys.MTU, value)
    suspend fun setTunStack(value: String) = put(Keys.TUN_STACK, value)
    suspend fun setPerAppMode(value: PerAppMode) = put(Keys.PER_APP_MODE, value.name)
    suspend fun setPerAppList(value: Set<String>) = put(Keys.PER_APP_LIST, value)
    suspend fun setAutoSelectFastest(value: Boolean) = put(Keys.AUTO_FASTEST, value)
    suspend fun setAutoUpdateSources(value: Boolean) = put(Keys.AUTO_UPDATE, value)
    suspend fun setConnectOnBoot(value: Boolean) = put(Keys.CONNECT_ON_BOOT, value)
    suspend fun setAutoReconnect(value: Boolean) = put(Keys.AUTO_RECONNECT, value)
    suspend fun setTestConcurrency(value: Int) = put(Keys.TEST_CONCURRENCY, value)
    suspend fun setTestTimeout(value: Int) = put(Keys.TEST_TIMEOUT, value)
    suspend fun setOnboarded(value: Boolean) = put(Keys.ONBOARDED, value)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
