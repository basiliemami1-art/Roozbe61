package com.gozar.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gozar.app.R
import com.gozar.app.ui.components.ConnectButton
import com.gozar.app.model.Flags
import com.gozar.app.ui.components.GlassCard
import com.gozar.app.ui.components.LatencyPill
import com.gozar.app.ui.components.ProtocolChip
import com.gozar.app.ui.components.StatTile
import com.gozar.app.ui.components.formatBytes
import com.gozar.app.ui.components.formatDuration
import com.gozar.app.ui.components.formatSpeed
import com.gozar.app.ui.theme.Mint
import com.gozar.app.ui.theme.Violet
import com.gozar.app.vpn.ConnectionStatus
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onRequestConnect: (serverId: Long) -> Unit,
) {
    val status by viewModel.status.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val activeServer by viewModel.activeServer.collectAsState()
    val connectProgress by viewModel.connectProgress.collectAsState()
    val serverCount by viewModel.serverCount.collectAsState()
    val workingCount by viewModel.workingCount.collectAsState()

    // Uptime is derived from a timestamp, so it needs its own tick to redraw.
    val uptime by produceState(initialValue = "00:00", stats.connectedSince, status) {
        while (true) {
            value = if (status == ConnectionStatus.CONNECTED) {
                formatDuration(stats.connectedSince)
            } else {
                "00:00"
            }
            delay(1_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        ConnectButton(
            status = status,
            onClick = {
                if (status == ConnectionStatus.CONNECTED ||
                    status == ConnectionStatus.CONNECTING
                ) {
                    viewModel.disconnect()
                } else {
                    onRequestConnect(0)
                }
            },
        )

        Text(
            text = stringResource(
                when (status) {
                    ConnectionStatus.CONNECTED -> R.string.state_connected
                    ConnectionStatus.CONNECTING -> R.string.state_connecting
                    ConnectionStatus.STOPPING -> R.string.state_stopping
                    ConnectionStatus.DISCONNECTED -> R.string.state_disconnected
                },
            ),
            style = MaterialTheme.typography.titleLarge,
            color = when (status) {
                ConnectionStatus.CONNECTED -> Mint
                ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> Violet
            },
        )
        // While connecting, the failover loop reports which candidate it is on.
        // Silence here is what made a dead server feel like a frozen app.
        val subtitle = connectProgress ?: stringResource(
            if (status == ConnectionStatus.CONNECTED) {
                R.string.tap_to_disconnect
            } else {
                R.string.tap_to_connect
            },
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(Modifier.height(22.dp))

        GlassCard {
            Column(Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = Violet,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.current_server),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::selectFastest) {
                        Text(stringResource(R.string.select_fastest))
                    }
                }

                val server = activeServer
                if (server == null) {
                    Text(
                        text = stringResource(R.string.no_server_selected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Flags.forName(server.name)?.let { flag ->
                            Text(text = flag, fontSize = 22.sp)
                            Spacer(Modifier.size(10.dp))
                        }
                        Text(
                            text = Flags.stripFlag(server.name),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProtocolChip(server.protocolEnum?.label ?: server.protocol)
                        LatencyPill(
                            latency = server.shownLatency,
                            untestedLabel = stringResource(R.string.latency_untested),
                            timeoutLabel = stringResource(R.string.latency_timeout),
                        )
                        Text(
                            text = "${server.address}:${server.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                icon = Icons.Rounded.ArrowDownward,
                label = stringResource(R.string.download),
                value = formatSpeed(stats.downlinkSpeed),
                tint = Mint,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = Icons.Rounded.ArrowUpward,
                label = stringResource(R.string.upload),
                value = formatSpeed(stats.uplinkSpeed),
                tint = Violet,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                icon = Icons.Rounded.Schedule,
                label = stringResource(R.string.duration),
                value = uptime,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = Icons.Rounded.NetworkCheck,
                label = stringResource(R.string.total_traffic),
                value = formatBytes(stats.downlinkBytes + stats.uplinkBytes),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        AnimatedVisibility(visible = stats.delayMs > 0) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "${stringResource(R.string.latency_ms, stats.delayMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mint,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.server_count, serverCount) +
                if (workingCount > 0) "  •  $workingCount ✓" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))
    }
}
