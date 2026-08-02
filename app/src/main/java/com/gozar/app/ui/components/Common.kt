package com.gozar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gozar.app.data.Latency
import com.gozar.app.ui.theme.Amber
import com.gozar.app.ui.theme.Mint
import com.gozar.app.ui.theme.Rose
import java.util.Locale
import kotlin.math.abs

/** Colour bands chosen so a glance at the list is enough to pick a server. */
fun latencyColor(latency: Int): Color = when {
    latency <= 0 -> Rose
    latency < 300 -> Mint
    latency < 800 -> Amber
    else -> Rose
}

@Composable
fun LatencyPill(
    latency: Int,
    untestedLabel: String,
    timeoutLabel: String,
    modifier: Modifier = Modifier,
) {
    val color = when (latency) {
        Latency.UNTESTED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> latencyColor(latency)
    }
    val label = when {
        latency > 0 -> "$latency ms"
        latency == Latency.UNTESTED -> untestedLabel
        else -> timeoutLabel
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
    ) {
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
fun ProtocolChip(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
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
fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(tint.copy(alpha = 0.15f), RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier.border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(22.dp),
            ),
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------- Formatting

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

fun formatDuration(startedAt: Long): String {
    if (startedAt <= 0) return "00:00"
    val seconds = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
}

fun formatRelativeTime(timestamp: Long, neverLabel: String): String {
    if (timestamp <= 0) return neverLabel
    val delta = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        delta < 60 -> "${delta}s"
        delta < 3600 -> "${delta / 60}m"
        delta < 86400 -> "${delta / 3600}h"
        else -> "${delta / 86400}d"
    }
}
