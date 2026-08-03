package com.gozar.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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
    val text = LocalStrings.current
    val color = if (latency == Latency.UNTESTED) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        latencyColor(latency)
    }
    val label = when {
        latency > 0 -> text.milliseconds.fill(latency)
        latency == Latency.UNTESTED -> text.untested
        else -> text.noReply
    }
    Surface(modifier, shape = RoundedCornerShape(50), color = color.copy(alpha = 0.13f)) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Text(
                text = label,
                color = color,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun Chip(
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(7.dp),
        color = tint.copy(alpha = 0.12f),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

/** The standard raised plane: one hairline, one radius, used everywhere. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: Int = 20,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shades = LocalElevations.current
    Surface(
        modifier,
        shape = RoundedCornerShape(20.dp),
        color = shades.card,
        border = BorderStroke(1.dp, shades.hairline),
    ) {
        Column(Modifier.padding(padding.dp), content = content)
    }
}

/** A section title above a card, in the accent colour. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.US),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
    )
}

@Composable
fun StatTile(
    label: String,
    value: String,
    tint: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val shades = LocalElevations.current
    Surface(
        modifier,
        shape = RoundedCornerShape(18.dp),
        color = shades.card,
        border = BorderStroke(1.dp, shades.hairline),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (icon != null) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/**
 * The primary control.
 *
 * Rings ripple outward while connected and a sweep spins while connecting, so
 * the state reads without the label — and while connecting the icon becomes a
 * cross, because that click is now the way to call the attempt off.
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
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.04f else 1f, tween(180), label = "scale")

    val connected = status == ConnectionStatus.CONNECTED
    val busy = status == ConnectionStatus.CONNECTING || status == ConnectionStatus.STOPPING
    val accent by animateColorAsState(
        when {
            connected -> Mint
            busy -> Violet
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        tween(400),
        label = "accent",
    )

    Box(modifier.size(248.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(248.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f

            // A soft halo so the control sits in light rather than on a flat
            // background; it is the whole reason this screen reads as a device.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            if (connected) {
                for (index in 0..2) {
                    val progress = (pulse + index / 3f) % 1f
                    drawCircle(
                        color = accent.copy(alpha = (1f - progress) * 0.30f),
                        radius = radius * (0.44f + 0.56f * progress),
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            if (busy) {
                drawArc(
                    color = accent.copy(alpha = 0.20f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx()),
                    topLeft = Offset(center.x - radius * 0.74f, center.y - radius * 0.74f),
                    size = Size(radius * 1.48f, radius * 1.48f),
                )
                drawArc(
                    color = accent,
                    startAngle = sweep,
                    sweepAngle = 80f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx()),
                    topLeft = Offset(center.x - radius * 0.74f, center.y - radius * 0.74f),
                    size = Size(radius * 1.48f, radius * 1.48f),
                )
            } else {
                drawCircle(
                    accent.copy(alpha = 0.22f),
                    radius * 0.74f,
                    center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        Box(
            Modifier
                .size((140 * scale).dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        when {
                            connected -> listOf(Mint, Sky)
                            busy -> listOf(Violet, VioletDeep)
                            else -> listOf(
                                accent.copy(alpha = 0.30f),
                                accent.copy(alpha = 0.16f),
                            )
                        },
                    ),
                )
                .hoverable(interaction)
                .clickable(interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when {
                    connected -> Icons.Rounded.Bolt
                    busy -> Icons.Rounded.Close
                    else -> Icons.Rounded.PowerSettingsNew
                },
                contentDescription = null,
                tint = if (connected || busy) Color.White else accent,
                modifier = Modifier.size(52.dp),
            )
        }
    }
}

/** A row that lifts on hover, which is what makes a desktop list feel alive. */
@Composable
fun HoverRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val shades = LocalElevations.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            accent != null -> accent.copy(alpha = if (hovered) 0.22f else 0.16f)
            hovered -> shades.cardHover
            else -> shades.card
        },
        tween(160),
        label = "rowBackground",
    )
    val outline by animateColorAsState(
        accent ?: if (hovered) shades.hairline.copy(alpha = 1f) else shades.hairline,
        tween(160),
        label = "rowOutline",
    )

    Surface(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = background,
    ) {
        Box(Modifier.border(1.dp, outline, RoundedCornerShape(16.dp))) { content() }
    }
}

/** A thin progress track used for both sweeps and subscription quotas. */
@Composable
fun ProgressTrack(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val shades = LocalElevations.current
    val animated by animateFloatAsState(
        fraction.coerceIn(0f, 1f),
        tween(240),
        label = "progress",
    )
    Box(
        modifier
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(shades.hairline),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color))),
        )
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

fun formatRelative(timestamp: Long, neverLabel: String): String {
    if (timestamp <= 0) return neverLabel
    val delta = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        delta < 60 -> "${delta}s"
        delta < 3600 -> "${delta / 60}m"
        delta < 86400 -> "${delta / 3600}h"
        else -> "${delta / 86400}d"
    }
}
