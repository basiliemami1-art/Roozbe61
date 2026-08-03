package com.gozar.desktop

import com.gozar.desktop.ui.AwtFonts
import com.gozar.desktop.ui.Strings
import java.awt.Color
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/**
 * The tray icon, built on AWT directly rather than through Compose's `Tray`.
 *
 * Compose's version gives no access to the menu items it creates, and those are
 * heavyweight AWT components that never see the typeface the window uses. With
 * the platform default they rendered Persian labels as empty boxes; owning the
 * `MenuItem`s is the only way to set a font on them.
 */
class AppTray(
    private val onShowHide: () -> Unit,
    private val onToggleConnection: () -> Unit,
    private val onQuit: () -> Unit,
) {

    private var icon: TrayIcon? = null
    private val showHide = MenuItem()
    private val toggle = MenuItem()
    private val quit = MenuItem()

    fun install(): Boolean {
        if (!SystemTray.isSupported()) return false
        return runCatching {
            AwtFonts.applyToMenus()
            AwtFonts.persian?.let { font ->
                showHide.font = font
                toggle.font = font
                quit.font = font
            }
            showHide.addActionListener { onShowHide() }
            toggle.addActionListener { onToggleConnection() }
            quit.addActionListener { onQuit() }

            val menu = PopupMenu().apply {
                AwtFonts.persian?.let { font = it }
                add(showHide)
                add(toggle)
                addSeparator()
                add(quit)
            }
            val tray = TrayIcon(render(SystemTray.getSystemTray().trayIconSize.width), "Gozar", menu)
            tray.isImageAutoSize = true
            tray.addActionListener { onShowHide() }
            SystemTray.getSystemTray().add(tray)
            icon = tray
            true
        }.getOrDefault(false)
    }

    fun update(text: Strings, status: ConnectionStatus, windowVisible: Boolean) {
        val tray = icon ?: return
        showHide.label = if (windowVisible) text.trayHide else text.trayShow
        toggle.label = when (status) {
            ConnectionStatus.CONNECTED -> text.trayDisconnect
            ConnectionStatus.CONNECTING -> text.cancel
            else -> text.trayConnect
        }
        quit.label = text.trayQuit
        tray.toolTip = "${text.appName} — " + when (status) {
            ConnectionStatus.CONNECTED -> text.statusConnected
            ConnectionStatus.CONNECTING -> text.statusConnecting
            ConnectionStatus.STOPPING -> text.statusStopping
            ConnectionStatus.DISCONNECTED -> text.statusDisconnected
        }
    }

    fun remove() {
        icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        icon = null
    }

    /**
     * The same mark as the launcher icon — an arrow breaking through a barrier —
     * drawn rather than loaded, so it stays sharp at whatever size the tray asks
     * for and no bitmap has to ship for each one.
     */
    private fun render(size: Int): BufferedImage {
        val side = size.coerceIn(16, 64)
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        fun x(fraction: Float) = side * fraction
        fun y(fraction: Float) = side * fraction

        g.color = Color(255, 255, 255, 136)
        g.fillRect(x(0.10f).toInt(), y(0.55f).toInt(), (side * 0.24f).toInt(), (side * 0.10f).toInt())
        g.fillRect(x(0.66f).toInt(), y(0.55f).toInt(), (side * 0.24f).toInt(), (side * 0.10f).toInt())

        val arrow = Path2D.Float().apply {
            moveTo(x(0.50f), y(0.14f))
            lineTo(x(0.78f), y(0.48f))
            lineTo(x(0.61f), y(0.48f))
            lineTo(x(0.61f), y(0.86f))
            lineTo(x(0.39f), y(0.86f))
            lineTo(x(0.39f), y(0.48f))
            lineTo(x(0.22f), y(0.48f))
            closePath()
        }
        g.color = Color(0x00, 0xE6, 0xB0)
        g.fill(arrow)
        g.dispose()
        return image
    }
}
