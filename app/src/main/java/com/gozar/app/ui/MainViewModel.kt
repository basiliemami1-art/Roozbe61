package com.gozar.app.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gozar.app.App
import com.gozar.app.data.GozarDatabase
import com.gozar.app.data.PerAppMode
import com.gozar.app.data.ServerEntity
import com.gozar.app.data.Settings
import com.gozar.app.data.SettingsRepository
import com.gozar.app.data.SourceEntity
import com.gozar.app.data.ThemeMode
import com.gozar.app.net.LatencyTester
import com.gozar.app.net.SourceFetcher
import com.gozar.app.parser.ConfigParser
import com.gozar.app.vpn.BootReceiver
import com.gozar.app.vpn.GozarVpnService
import com.gozar.app.vpn.VpnState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ServerFilter { ALL, WORKING, FAVORITE }

sealed interface BusyState {
    data class Refreshing(val done: Int, val total: Int, val source: String) : BusyState
    data class Testing(val done: Int, val total: Int, val alive: Int) : BusyState
}

data class Toast(val message: String, val id: Long = System.nanoTime())

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = GozarDatabase.get(application)
    private val settingsRepository = SettingsRepository(application)

    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val status = VpnState.status
    val stats = VpnState.stats
    val vpnError = VpnState.lastError

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val _filter = MutableStateFlow(ServerFilter.ALL)
    val filter: StateFlow<ServerFilter> = _filter.asStateFlow()

    private val _busy = MutableStateFlow<BusyState?>(null)
    val busy: StateFlow<BusyState?> = _busy.asStateFlow()

    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast.asStateFlow()

    val servers: StateFlow<List<ServerEntity>> =
        combine(_search.debounce(220), _filter) { query, filter -> query to filter }
            .flatMapLatest { (query, filter) ->
                database.serverDao().observeFiltered(
                    search = query.trim(),
                    onlyWorking = if (filter == ServerFilter.WORKING) 1 else 0,
                    onlyFavorite = if (filter == ServerFilter.FAVORITE) 1 else 0,
                    protocol = "",
                    limit = 600,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val serverCount: StateFlow<Int> = database.serverDao().observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val workingCount: StateFlow<Int> = database.serverDao().observeWorkingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val sources: StateFlow<List<SourceEntity>> = database.sourceDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeServer: StateFlow<ServerEntity?> =
        combine(VpnState.activeServerId, settings) { activeId, s ->
            if (activeId > 0) activeId else s.selectedServerId
        }
            .flatMapLatest { database.serverDao().observeById(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val current = settingsRepository.current()
            if (current.autoUpdateSources && database.serverDao().testCandidates(1).isEmpty()) {
                // First run: there is nothing to connect to until we fetch.
                refreshSources()
            }
        }
    }

    // ------------------------------------------------------------- Filtering

    fun setSearch(value: String) {
        _search.value = value
    }

    fun setFilter(value: ServerFilter) {
        _filter.value = value
    }

    // -------------------------------------------------------------- Sources

    fun refreshSources() {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = BusyState.Refreshing(0, 1, "")
            val current = settingsRepository.current()
            val fetcher = SourceFetcher(database, current.maxServers)
            val result = fetcher.refreshAll { progress ->
                _busy.value = BusyState.Refreshing(
                    progress.done,
                    progress.total,
                    progress.currentSource,
                )
            }
            _busy.value = null
            emitToast(
                getApplication<Application>().getString(
                    com.gozar.app.R.string.source_updated,
                    result.newConfigs,
                    result.sourcesOk,
                ),
            )
            if (result.newConfigs > 0) testAll()
        }
    }

    fun addSource(input: String) {
        viewModelScope.launch {
            val spec = SourceFetcher.normalizeUserInput(input)
            if (spec == null) {
                emitToast(getApplication<Application>().getString(com.gozar.app.R.string.import_nothing))
                return@launch
            }
            database.sourceDao().insert(
                SourceEntity(
                    name = spec.name,
                    url = spec.url,
                    kind = spec.kind.name,
                    enabled = true,
                    builtIn = false,
                ),
            )
            refreshSources()
        }
    }

    fun setSourceEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { database.sourceDao().setEnabled(id, enabled) }
    }

    fun deleteSource(source: SourceEntity) {
        viewModelScope.launch {
            database.sourceDao().delete(source.id)
            database.serverDao().deleteBySource(source.id)
        }
    }

    fun restoreDefaultSources() {
        viewModelScope.launch { SourceFetcher.restoreDefaults(database) }
    }

    fun importFromClipboard() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                .orEmpty()
            importText(text)
        }
    }

    fun importText(text: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val configs = ConfigParser.parseMany(text)
            if (configs.isEmpty()) {
                emitToast(context.getString(com.gozar.app.R.string.import_nothing))
                return@launch
            }
            val entities = configs.map { config ->
                ServerEntity(
                    uniqueKey = config.uniqueKey,
                    name = config.name.take(120),
                    protocol = config.protocol.name,
                    address = config.server,
                    port = config.port,
                    raw = config.raw,
                    sourceId = 0,
                    sourceName = "manual",
                )
            }
            val inserted = database.serverDao().insertAll(entities).count { it != -1L }
            emitToast(context.getString(com.gozar.app.R.string.import_success, inserted))
        }
    }

    // -------------------------------------------------------------- Testing

    fun testAll() {
        if (_busy.value is BusyState.Testing) return
        viewModelScope.launch {
            val current = settingsRepository.current()
            _busy.value = BusyState.Testing(0, 1, 0)
            val tester = LatencyTester(
                db = database,
                concurrency = current.testConcurrency,
                timeoutMs = current.testTimeoutSeconds * 1000,
            )
            tester.testAll { progress ->
                _busy.value = BusyState.Testing(progress.done, progress.total, progress.alive)
            }
            _busy.value = null
        }
    }

    // -------------------------------------------------------------- Servers

    fun selectServer(id: Long) {
        viewModelScope.launch { settingsRepository.setSelectedServer(id) }
    }

    fun toggleFavorite(id: Long) {
        viewModelScope.launch { database.serverDao().toggleFavorite(id) }
    }

    fun deleteServer(id: Long) {
        viewModelScope.launch { database.serverDao().delete(id) }
    }

    fun deleteDeadServers() {
        viewModelScope.launch { database.serverDao().deleteDead() }
    }

    fun deleteAllServers() {
        viewModelScope.launch { database.serverDao().deleteAllExceptFavorites() }
    }

    fun selectFastest() {
        viewModelScope.launch {
            val fastest = database.serverDao().fastest()
            if (fastest == null) {
                emitToast(getApplication<Application>().getString(com.gozar.app.R.string.error_no_server))
            } else {
                settingsRepository.setSelectedServer(fastest.id)
                emitToast("${fastest.name} · ${fastest.latency} ms")
            }
        }
    }

    // ------------------------------------------------------------ Connection

    /** @return true when a server was available and the service was asked to start. */
    fun connect(): Boolean {
        val selected = settings.value.selectedServerId
        val hasAny = serverCount.value > 0
        if (selected == 0L && !hasAny) {
            emitToast(getApplication<Application>().getString(com.gozar.app.R.string.error_no_server))
            return false
        }
        GozarVpnService.start(getApplication(), selected)
        return true
    }

    fun disconnect() {
        GozarVpnService.stop(getApplication())
    }

    // ------------------------------------------------------------- Settings

    // These are bound straight to UI callbacks of type (T) -> Unit, so they use
    // block bodies and return Unit rather than the Job that `launch` produces.

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setTheme(mode) }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch {
            settingsRepository.setLanguage(tag)
            App.applyLanguage(tag)
        }
    }

    fun setBypassIran(value: Boolean) {
        viewModelScope.launch { settingsRepository.setBypassIran(value) }
    }

    fun setBypassLan(value: Boolean) {
        viewModelScope.launch { settingsRepository.setBypassLan(value) }
    }

    fun setBlockAds(value: Boolean) {
        viewModelScope.launch { settingsRepository.setBlockAds(value) }
    }

    fun setIpv6(value: Boolean) {
        viewModelScope.launch { settingsRepository.setIpv6(value) }
    }

    fun setRemoteDns(value: String) {
        viewModelScope.launch { settingsRepository.setRemoteDns(value) }
    }

    fun setAutoSelectFastest(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoSelectFastest(value) }
    }

    fun setAutoUpdateSources(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoUpdateSources(value) }
    }

    fun setAutoReconnect(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoReconnect(value) }
    }

    fun setConnectOnBoot(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.setConnectOnBoot(value)
            BootReceiver.setEnabled(getApplication(), value)
        }
    }

    fun setPerAppMode(mode: PerAppMode) {
        viewModelScope.launch { settingsRepository.setPerAppMode(mode) }
    }

    fun setPerAppList(packages: Set<String>) {
        viewModelScope.launch { settingsRepository.setPerAppList(packages) }
    }

    fun setTestConcurrency(value: Int) {
        viewModelScope.launch { settingsRepository.setTestConcurrency(value.coerceIn(4, 128)) }
    }

    fun setTestTimeout(value: Int) {
        viewModelScope.launch { settingsRepository.setTestTimeout(value.coerceIn(1, 20)) }
    }

    fun setTunStack(value: String) {
        viewModelScope.launch { settingsRepository.setTunStack(value) }
    }

    fun setMtu(value: Int) {
        viewModelScope.launch { settingsRepository.setMtu(value.coerceIn(1280, 9000)) }
    }

    // ---------------------------------------------------------------- Toast

    fun emitToast(message: String) {
        _toast.value = Toast(message)
    }

    fun consumeToast() {
        _toast.value = null
    }

    fun clearVpnError() {
        VpnState.clearError()
    }
}
