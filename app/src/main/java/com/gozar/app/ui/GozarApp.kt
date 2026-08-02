package com.gozar.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gozar.app.R
import com.gozar.app.ui.theme.Violet

private enum class Tab { HOME, SERVERS, SOURCES, SETTINGS }

@Composable
fun GozarApp(
    viewModel: MainViewModel,
    onRequestConnect: (serverId: Long) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val snackbarHost = remember { SnackbarHostState() }

    val toast by viewModel.toast.collectAsState()
    val vpnError by viewModel.vpnError.collectAsState()

    LaunchedEffect(toast?.id) {
        val message = toast?.message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        viewModel.consumeToast()
    }
    LaunchedEffect(vpnError) {
        val message = vpnError ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        viewModel.clearVpnError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = tab == Tab.HOME,
                    onClick = { tab = Tab.HOME },
                    icon = { Icon(Icons.Rounded.Shield, null) },
                    label = { Text(stringResource(R.string.nav_home)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.SERVERS,
                    onClick = { tab = Tab.SERVERS },
                    icon = { Icon(Icons.Rounded.Language, null) },
                    label = { Text(stringResource(R.string.nav_servers)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.SOURCES,
                    onClick = { tab = Tab.SOURCES },
                    icon = { Icon(Icons.Rounded.Dns, null) },
                    label = { Text(stringResource(R.string.nav_subscriptions)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Rounded.Settings, null) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Violet.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                )
                .padding(padding),
        ) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                label = "tab",
            ) { current ->
                when (current) {
                    Tab.HOME -> HomeScreen(viewModel, onRequestConnect)
                    Tab.SERVERS -> ServersScreen(viewModel, onRequestConnect)
                    Tab.SOURCES -> SourcesScreen(viewModel)
                    Tab.SETTINGS -> SettingsScreen(viewModel)
                }
            }
        }
    }
}
