package com.gozar.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, STOPPING }

data class TunnelStats(
    val uplinkBytes: Long = 0,
    val downlinkBytes: Long = 0,
    val uplinkSpeed: Long = 0,
    val downlinkSpeed: Long = 0,
    val connectedSince: Long = 0,
    val delayMs: Int = 0,
)

/**
 * Single source of truth for tunnel state.
 *
 * The VPN service runs in the app's main process, so a plain in-process
 * singleton is enough — no cross-process messaging required.
 */
object VpnState {

    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _activeServerId = MutableStateFlow(0L)
    val activeServerId: StateFlow<Long> = _activeServerId.asStateFlow()

    val isRunning: Boolean
        get() = _status.value == ConnectionStatus.CONNECTED ||
            _status.value == ConnectionStatus.CONNECTING

    fun setStatus(status: ConnectionStatus) {
        _status.value = status
        if (status == ConnectionStatus.DISCONNECTED) {
            _stats.value = TunnelStats()
            _activeServerId.value = 0
        }
    }

    fun setStats(stats: TunnelStats) {
        _stats.value = stats
    }

    fun setError(message: String?) {
        _lastError.value = message
    }

    fun clearError() {
        _lastError.value = null
    }

    fun setActiveServer(id: Long) {
        _activeServerId.value = id
    }
}
