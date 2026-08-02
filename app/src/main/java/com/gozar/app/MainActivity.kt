package com.gozar.app

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.gozar.app.ui.GozarApp
import com.gozar.app.ui.MainViewModel
import com.gozar.app.ui.theme.GozarTheme

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                viewModel.connect()
            } else {
                viewModel.emitToast(getString(R.string.error_vpn_permission))
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        requestNotificationPermission()
        handleIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsState()
            GozarTheme(themeMode = settings.theme) {
                GozarApp(
                    viewModel = viewModel,
                    onRequestConnect = ::requestConnect,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Asks for the VPN grant if we do not already hold it; [MainViewModel.connect]
     * runs either way once the user has answered.
     */
    private fun requestConnect() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.connect()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Config links and shared text both arrive here. */
    private fun handleIntent(intent: Intent?) {
        val payload = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!payload.isNullOrBlank()) {
            viewModel.importText(payload)
        }
    }
}
