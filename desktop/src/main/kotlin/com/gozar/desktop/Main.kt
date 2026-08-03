package com.gozar.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
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

    // The system proxy is a machine-wide setting. If the process dies without
    // clearing it, every browser on the machine keeps pointing at a port that
    // is no longer listening — so the hook runs even on an abrupt exit.
    remember {
        Runtime.getRuntime().addShutdownHook(Thread { state.shutdown() })
        true
    }

    // The tray lives outside the app's CompositionLocals, so it reads the
    // language straight from settings.
    val settings by state.settings.collectAsState()
    val text = Strings.forLanguage(settings.language)

    Tray(
        icon = TrayIcon,
        tooltip = "${text.appName} — " + when (status) {
            ConnectionStatus.CONNECTED -> text.statusConnected
            ConnectionStatus.CONNECTING -> text.statusConnecting
            ConnectionStatus.STOPPING -> text.statusStopping
            ConnectionStatus.DISCONNECTED -> text.statusDisconnected
        },
        onAction = { visible = true },
        menu = {
            Item(if (visible) text.trayHide else text.trayShow) { visible = !visible }
            Item(
                if (status == ConnectionStatus.CONNECTED) text.trayDisconnect else text.trayConnect,
            ) {
                if (status == ConnectionStatus.CONNECTED) state.stop() else state.connect()
            }
            Separator()
            Item(text.trayQuit) {
                state.shutdown()
                exitApplication()
            }
        },
    )

    Window(
        // Closing the window leaves the tunnel running and the app in the tray,
        // which is what a VPN client is expected to do.
        onCloseRequest = { visible = false },
        visible = visible,
        title = text.appName,
        state = rememberWindowState(width = 1040.dp, height = 720.dp),
    ) {
        window.minimumSize = Dimension(880, 620)
        GozarApp(state)
    }
}
