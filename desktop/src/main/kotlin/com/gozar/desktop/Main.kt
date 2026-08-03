package com.gozar.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.gozar.desktop.ui.GozarApp
import com.gozar.desktop.ui.Strings
import java.awt.Dimension

fun main() = application {
    val state = remember { AppState() }
    var visible by remember { mutableStateOf(true) }
    val status by state.status.collectAsState()
    val settings by state.settings.collectAsState()
    val text = Strings.forLanguage(settings.language)

    // The system proxy is a machine-wide setting. If the process dies without
    // clearing it, every browser on the machine keeps pointing at a port that
    // is no longer listening — so the hook runs even on an abrupt exit.
    remember {
        Runtime.getRuntime().addShutdownHook(Thread { state.shutdown() })
        true
    }

    // The listeners are read through rememberUpdatedState so the tray, which is
    // installed once, always calls the current lambdas rather than the ones
    // captured on the first composition.
    val currentStatus by rememberUpdatedState(status)
    val currentVisible by rememberUpdatedState(visible)
    val tray = remember {
        AppTray(
            onShowHide = { visible = !currentVisible },
            onToggleConnection = {
                if (currentStatus == ConnectionStatus.CONNECTED) state.stop() else state.connect()
            },
            onQuit = {
                state.shutdown()
                exitApplication()
            },
        )
    }
    DisposableEffect(Unit) {
        tray.install()
        onDispose { tray.remove() }
    }
    LaunchedEffect(text, status, visible) {
        tray.update(text, status, visible)
    }

    Window(
        // Closing the window leaves the tunnel running and the app in the tray,
        // which is what a VPN client is expected to do.
        onCloseRequest = { visible = false },
        visible = visible,
        title = text.appName,
        state = rememberWindowState(width = 1120.dp, height = 760.dp),
    ) {
        window.minimumSize = Dimension(960, 660)
        GozarApp(state)
    }
}
