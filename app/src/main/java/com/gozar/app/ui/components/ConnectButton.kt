package com.gozar.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gozar.app.ui.theme.Mint
import com.gozar.app.ui.theme.Violet
import com.gozar.app.vpn.ConnectionStatus

/**
 * The primary control. Rings expand outward while connected and a sweep spins
 * while connecting, so the state is legible without reading the label.
 */
@Composable
fun ConnectButton(
    status: ConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "connect")

    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )

    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    val breathe by transition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    val accent = when (status) {
        ConnectionStatus.CONNECTED -> Mint
        ConnectionStatus.CONNECTING, ConnectionStatus.STOPPING -> Violet
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val connected = status == ConnectionStatus.CONNECTED

    Box(
        modifier = modifier.size(260.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(260.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension / 2f

            if (connected) {
                // Three ripples, evenly offset in phase.
                for (index in 0..2) {
                    val progress = (pulse + index / 3f) % 1f
                    val radius = baseRadius * (0.42f + 0.58f * progress)
                    drawCircle(
                        color = accent.copy(alpha = (1f - progress) * 0.35f),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            drawCircle(
                color = accent.copy(alpha = 0.08f),
                radius = baseRadius * 0.62f,
                center = center,
            )

            if (status == ConnectionStatus.CONNECTING || status == ConnectionStatus.STOPPING) {
                drawArc(
                    color = accent,
                    startAngle = sweep,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx()),
                    topLeft = Offset(
                        center.x - baseRadius * 0.72f,
                        center.y - baseRadius * 0.72f,
                    ),
                    size = androidx.compose.ui.geometry.Size(
                        baseRadius * 1.44f,
                        baseRadius * 1.44f,
                    ),
                )
            } else {
                drawCircle(
                    color = accent.copy(alpha = 0.25f),
                    radius = baseRadius * 0.72f,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        Surface(
            modifier = Modifier
                .size(150.dp)
                .scale(if (connected) breathe else 1f),
            shape = CircleShape,
            color = Color.Transparent,
            onClick = onClick,
        ) {
            Box(
                modifier = Modifier.size(150.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(150.dp)) {
                    drawCircle(
                        brush = Brush.linearGradient(
                            colors = if (connected) {
                                listOf(Mint, Violet)
                            } else {
                                listOf(
                                    accent.copy(alpha = 0.35f),
                                    accent.copy(alpha = 0.18f),
                                )
                            },
                        ),
                    )
                }
                Icon(
                    imageVector = if (connected) {
                        Icons.Rounded.Bolt
                    } else {
                        Icons.Rounded.PowerSettingsNew
                    },
                    contentDescription = null,
                    tint = if (connected) Color.White else accent,
                    modifier = Modifier.size(54.dp),
                )
            }
        }
    }
}
