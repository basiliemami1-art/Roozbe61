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

    Tray(
        icon = TrayIcon,
        tooltip = when (status) {
            ConnectionStatus.CONNECTED -> "Gozar — connected"
            ConnectionStatus.CONNECTING -> "Gozar — connecting"
            ConnectionStatus.STOPPING -> "Gozar — stopping"
            ConnectionStatus.DISCONNECTED -> "Gozar — disconnected"
        },
        onAction = { visible = true },
        menu = {
            Item(if (visible) "Hide window" else "Show window") { visible = !visible }
            Item(if (status == ConnectionStatus.CONNECTED) "Disconnect" else "Connect") {
                if (status == ConnectionStatus.CONNECTED) state.stop() else state.connect()
            }
            Separator()
            Item("Quit") {
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
        title = "Gozar",
        state = rememberWindowState(width = 1040.dp, height = 720.dp),
    ) {
        window.minimumSize = Dimension(880, 620)
        GozarApp(state)
    }
}
