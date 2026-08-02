package com.gozar.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.util.Log
import com.gozar.app.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Reconnects after a reboot. Disabled in the manifest by default and enabled by
 * [setEnabled] only once the user turns the option on, so the app claims no
 * boot hook it does not use.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        try {
            val enabled = runBlocking {
                withContext(Dispatchers.IO) {
                    SettingsRepository(context.applicationContext).current().connectOnBoot
                }
            }
            // Without a standing VPN grant there is nothing we may start silently.
            if (enabled && VpnService.prepare(context) == null) {
                GozarVpnService.start(context.applicationContext)
            }
        } catch (error: Throwable) {
            Log.w("GozarBoot", "boot start failed: ${error.message}")
        } finally {
            pending.finish()
        }
    }

    companion object {
        fun setEnabled(context: Context, enabled: Boolean) {
            val component = android.content.ComponentName(context, BootReceiver::class.java)
            context.packageManager.setComponentEnabledSetting(
                component,
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
