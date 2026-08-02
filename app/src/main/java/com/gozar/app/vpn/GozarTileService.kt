package com.gozar.app.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.gozar.app.MainActivity
import com.gozar.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Quick-settings toggle for the tunnel. */
class GozarTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        newScope.launch {
            VpnState.status.collectLatest { render(it) }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        when (VpnState.status.value) {
            ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING ->
                GozarVpnService.stop(applicationContext)

            else -> {
                // Without an existing grant the user has to confirm in the app.
                if (VpnService.prepare(applicationContext) != null) {
                    launchApp()
                } else {
                    GozarVpnService.start(applicationContext)
                }
            }
        }
    }

    private fun launchApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The Intent overload throws on Android 14+.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(status: ConnectionStatus) {
        val tile = qsTile ?: return
        tile.state = when (status) {
            ConnectionStatus.CONNECTED -> Tile.STATE_ACTIVE
            ConnectionStatus.CONNECTING, ConnectionStatus.STOPPING -> Tile.STATE_UNAVAILABLE
            ConnectionStatus.DISCONNECTED -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.app_name)
        tile.contentDescription = getString(
            when (status) {
                ConnectionStatus.CONNECTED -> R.string.state_connected
                ConnectionStatus.CONNECTING -> R.string.state_connecting
                ConnectionStatus.STOPPING -> R.string.state_stopping
                ConnectionStatus.DISCONNECTED -> R.string.state_disconnected
            },
        )
        tile.updateTile()
    }
}
