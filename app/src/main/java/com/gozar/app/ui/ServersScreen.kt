package com.gozar.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gozar.app.R
import com.gozar.app.data.ServerEntity
import com.gozar.app.model.Flags
import com.gozar.app.ui.components.LatencyPill
import com.gozar.app.ui.components.ProtocolChip
import com.gozar.app.ui.components.latencyColor
import com.gozar.app.ui.theme.Amber
import com.gozar.app.ui.theme.Mint
import com.gozar.app.ui.theme.MintDeep
import com.gozar.app.ui.theme.Violet
import com.gozar.app.vpn.ConnectionStatus

@Composable
fun ServersScreen(
    viewModel: MainViewModel,
    onRequestConnect: (serverId: Long) -> Unit,
) {
    val servers by viewModel.servers.collectAsState()
    val search by viewModel.search.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val serverCount by viewModel.serverCount.collectAsState()
    val workingCount by viewModel.workingCount.collectAsState()
    val status by viewModel.status.collectAsState()
    val activeId by viewModel.activeServerId.collectAsState()
    val domesticCount by viewModel.domesticCount.collectAsState()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = viewModel::setSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            placeholder = { Text(stringResource(R.string.search_servers)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
        )

        // Scrollable: four chips do not fit on a narrow screen in either language.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = filter == ServerFilter.ALL,
                onClick = { viewModel.setFilter(ServerFilter.ALL) },
                label = { Text(stringResource(R.string.filter_all)) },
            )
            FilterChip(
                selected = filter == ServerFilter.WORKING,
                onClick = { viewModel.setFilter(ServerFilter.WORKING) },
                label = { Text(stringResource(R.string.filter_working)) },
            )
            FilterChip(
                selected = filter == ServerFilter.FAVORITE,
                onClick = { viewModel.setFilter(ServerFilter.FAVORITE) },
                label = { Text(stringResource(R.string.filter_favorite)) },
            )
            FilterChip(
                selected = filter == ServerFilter.DOMESTIC,
                onClick = { viewModel.setFilter(ServerFilter.DOMESTIC) },
                label = {
                    Text(
                        if (domesticCount > 0) {
                            stringResource(R.string.filter_domestic_count, domesticCount)
                        } else {
                            stringResource(R.string.filter_domestic)
                        },
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.Home, null, Modifier.size(16.dp)) },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = viewModel::refreshSources,
                label = { Text(stringResource(R.string.fetch_configs)) },
                leadingIcon = { Icon(Icons.Rounded.CloudDownload, null, Modifier.size(16.dp)) },
                colors = AssistChipDefaults.assistChipColors(labelColor = Violet),
            )
            AssistChip(
                onClick = viewModel::testAll,
                label = { Text(stringResource(R.string.test_all)) },
                leadingIcon = { Icon(Icons.Rounded.Speed, null, Modifier.size(16.dp)) },
                colors = AssistChipDefaults.assistChipColors(labelColor = Mint),
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::deleteDeadServers) {
                Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.delete_dead))
            }
        }

        AnimatedVisibility(visible = busy != null) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val label: String
                val fraction: Float
                when (val state = busy) {
                    is BusyState.Refreshing -> {
                        label = "${stringResource(R.string.updating_sources)}  ${state.done}/${state.total}"
                        fraction = state.done.toFloat() / state.total.coerceAtLeast(1)
                    }

                    is BusyState.Testing -> {
                        label = "${stringResource(R.string.testing)}  ${state.done}/${state.total}  ·  ${state.alive} ✓"
                        fraction = state.done.toFloat() / state.total.coerceAtLeast(1)
                    }

                    null -> {
                        label = ""
                        fraction = 0f
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // A long sweep must always be escapable.
                    TextButton(onClick = viewModel::cancelBusy) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        if (servers.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.no_servers),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.no_servers_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.server_count, serverCount) +
                            if (workingCount > 0) "   ·   $workingCount ✓" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                items(servers, key = { it.id }) { server ->
                    val isActive = server.id == activeId && status != ConnectionStatus.DISCONNECTED
                    ServerRow(
                        server = server,
                        selected = server.id == settings.selectedServerId,
                        active = isActive,
                        connecting = isActive && status == ConnectionStatus.CONNECTING,
                        onClick = { onRequestConnect(server.id) },
                        onToggleFavorite = { viewModel.toggleFavorite(server.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerEntity,
    selected: Boolean,
    active: Boolean,
    connecting: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val accent = when {
        active -> Mint
        selected -> Violet
        else -> latencyColor(server.latency)
    }
    val borderColor by animateColorAsState(
        targetValue = if (active) Mint else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(280),
        label = "border",
    )
    // The connected row is the one thing on this screen worth finding at a
    // glance, so it gets a filled green card rather than a tint you have to
    // look for.
    val container by animateColorAsState(
        targetValue = if (active) Mint.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
        animationSpec = tween(280),
        label = "container",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = container,
    ) {
        Box(
            Modifier.border(
                width = if (active) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(18.dp),
            ),
        ) {
            Row(
                modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A thin accent rail reads faster than a background tint.
                Box(
                    Modifier
                        .width(3.dp)
                        .height(34.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (active || selected) accent else accent.copy(alpha = 0.35f)),
                )
                Spacer(Modifier.width(10.dp))

                Flags.forName(server.name)?.let { flag ->
                    Text(text = flag, fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        text = Flags.stripFlag(server.name),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (active || selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MintDeep else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (active) {
                            Text(
                                text = stringResource(R.string.state_connected),
                                style = MaterialTheme.typography.labelSmall,
                                color = MintDeep,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (server.domesticEntry) {
                            ProtocolChip(stringResource(R.string.badge_domestic))
                        }
                        ProtocolChip(server.protocolEnum?.label ?: server.protocol)
                        Text(
                            text = server.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                when {
                    connecting -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Mint,
                    )

                    active -> Icon(
                        Icons.Rounded.Bolt,
                        contentDescription = stringResource(R.string.state_connected),
                        tint = Mint,
                        modifier = Modifier.size(20.dp),
                    )

                    else -> LatencyPill(
                        latency = server.latency,
                        untestedLabel = stringResource(R.string.latency_untested),
                        timeoutLabel = stringResource(R.string.latency_timeout),
                    )
                }

                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (server.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = stringResource(
                            if (server.favorite) R.string.unmark_favorite else R.string.mark_favorite,
                        ),
                        tint = if (server.favorite) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
