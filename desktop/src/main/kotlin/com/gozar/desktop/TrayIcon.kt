package com.gozar.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/**
 * The same mark as the launcher icon — an arrow breaking through a barrier —
 * drawn rather than loaded, so the tray works without shipping a bitmap at
 * every scale Windows might ask for.
 */
object TrayIcon : Painter() {

    override val intrinsicSize: Size = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        val w = size.width
        val h = size.height
        fun x(fraction: Float) = w * fraction
        fun y(fraction: Float) = h * fraction

        // Barrier segments, left and right of the shaft.
        drawRect(
            color = Color(0x88FFFFFF),
            topLeft = Offset(x(0.10f), y(0.55f)),
            size = Size(w * 0.24f, h * 0.08f),
        )
        drawRect(
            color = Color(0x88FFFFFF),
            topLeft = Offset(x(0.66f), y(0.55f)),
            size = Size(w * 0.24f, h * 0.08f),
        )

        val arrow = Path().apply {
            moveTo(x(0.50f), y(0.14f))
            lineTo(x(0.78f), y(0.48f))
            lineTo(x(0.61f), y(0.48f))
            lineTo(x(0.61f), y(0.86f))
            lineTo(x(0.39f), y(0.86f))
            lineTo(x(0.39f), y(0.48f))
            lineTo(x(0.22f), y(0.48f))
            close()
        }
        drawPath(arrow, Color(0xFF00E6B0))
    }
}
