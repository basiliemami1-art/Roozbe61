package com.gozar.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gozar.app.data.Latency
import com.gozar.desktop.ConnectionStatus
import java.util.Locale
import kotlin.math.abs

fun latencyColor(latency: Int): Color = when {
    latency <= 0 -> Rose
    latency < 300 -> Mint
    latency < 800 -> Amber
    else -> Rose
}

@Composable
fun LatencyPill(latency: Int, modifier: Modifier = Modifier) {
    val color = if (latency == Latency.UNTESTED) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        latencyColor(latency)
    }
    val label = when {
        latency > 0 -> "$latency ms"
        latency == Latency.UNTESTED -> "untested"
        else -> "no reply"
    }
    Surface(modifier, shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun Chip(label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Box(
            Modifier.border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(22.dp),
            ),
        ) { content() }
    }
}

@Composable
fun StatTile(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The primary control, matching the phone: rings ripple outward while
 * connected, a sweep spins while connecting, so state reads without the label.
 */
@Composable
fun ConnectButton(status: ConnectionStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "connect")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )

    val connected = status == ConnectionStatus.CONNECTED
    val accent = when (status) {
        ConnectionStatus.CONNECTED -> Mint
        ConnectionStatus.CONNECTING, ConnectionStatus.STOPPING -> Violet
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(modifier.size(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(230.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            if (connected) {
                for (index in 0..2) {
                    val progress = (pulse + index / 3f) % 1f
                    drawCircle(
                        color = accent.copy(alpha = (1f - progress) * 0.35f),
                        radius = radius * (0.42f + 0.58f * progress),
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            drawCircle(accent.copy(alpha = 0.08f), radius * 0.62f, center)
            if (status == ConnectionStatus.CONNECTING || status == ConnectionStatus.STOPPING) {
                drawArc(
                    color = accent,
                    startAngle = sweep,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx()),
                    topLeft = Offset(center.x - radius * 0.72f, center.y - radius * 0.72f),
                    size = Size(radius * 1.44f, radius * 1.44f),
                )
            } else {
                drawCircle(
                    accent.copy(alpha = 0.25f),
                    radius * 0.72f,
                    center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        Surface(
            modifier = Modifier.size(136.dp).clickable(onClick = onClick),
            shape = CircleShape,
            color = Color.Transparent,
        ) {
            Box(Modifier.size(136.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(136.dp)) {
                    drawCircle(
                        brush = Brush.linearGradient(
                            if (connected) listOf(Mint, Violet)
                            else listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.18f)),
                        ),
                    )
                }
                Icon(
                    imageVector = if (connected) Icons.Rounded.Bolt else Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = if (connected) Color.White else accent,
                    modifier = Modifier.size(50.dp),
                )
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    val value = abs(bytes).toDouble()
    return when {
        value < 1024 -> "$bytes B"
        value < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", value / 1024)
        value < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", value / (1024 * 1024))
        else -> String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024 * 1024))
    }
}

fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

fun formatRelative(timestamp: Long): String {
    if (timestamp <= 0) return "never"
    val delta = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        delta < 60 -> "${delta}s"
        delta < 3600 -> "${delta / 60}m"
        delta < 86400 -> "${delta / 3600}h"
        else -> "${delta / 86400}d"
    }
}
