package com.gozar.desktop.ui

import java.awt.Font
import java.awt.GraphicsEnvironment
import javax.swing.UIManager

/**
 * Makes Persian render in the system tray.
 *
 * The tray icon and its menu are AWT, not Compose: they are drawn by the OS
 * through Swing/AWT peers and never see the typeface the window uses. AWT falls
 * back to the "Dialog" logical font, which on a default Windows install carries
 * no Arabic-script glyphs, so every Persian label came out as empty boxes.
 *
 * The fix is to register the bundled Vazirmatn with the AWT graphics
 * environment and make it the default for menu components, which is the only
 * place this matters.
 */
object AwtFonts {

    /** Null when the font could not be loaded; callers then keep the default. */
    val persian: Font? by lazy { load() }

    private fun load(): Font? = runCatching {
        val stream = AwtFonts::class.java.classLoader
            .getResourceAsStream("fonts/vazirmatn_regular.ttf")
            ?: return@runCatching null
        val font = stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }
        // Registering makes it resolvable by name everywhere else in AWT.
        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font)
        font.deriveFont(Font.PLAIN, 12f)
    }.getOrNull()

    /**
     * Applies the font to every AWT/Swing menu surface. Called once before the
     * tray is created; the tray's own peer reads these defaults when it builds
     * the popup.
     */
    fun applyToMenus() {
        val font = persian ?: return
        val resource = javax.swing.plaf.FontUIResource(font)
        for (key in MENU_KEYS) {
            UIManager.put(key, resource)
        }
        // AWT's heavyweight PopupMenu, which is what the tray actually uses,
        // reads this desktop property rather than the Swing defaults.
        runCatching { System.setProperty("awt.useSystemAAFontSettings", "on") }
    }

    private val MENU_KEYS = listOf(
        "Menu.font",
        "MenuBar.font",
        "MenuItem.font",
        "PopupMenu.font",
        "CheckBoxMenuItem.font",
        "RadioButtonMenuItem.font",
        "Label.font",
        "ToolTip.font",
    )
}
