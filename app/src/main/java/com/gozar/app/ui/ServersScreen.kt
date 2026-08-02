package com.gozar.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gozar.app.R
import com.gozar.app.data.ServerEntity
import com.gozar.app.ui.components.LatencyPill
import com.gozar.app.ui.components.ProtocolChip
import com.gozar.app.ui.theme.Amber
import com.gozar.app.ui.theme.Violet

@Composable
fun ServersScreen(viewModel: MainViewModel) {
    val servers by viewModel.servers.collectAsState()
    val search by viewModel.search.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val serverCount by viewModel.serverCount.collectAsState()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = viewModel::setSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_servers)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            )
            AssistChip(
                onClick = viewModel::testAll,
                label = { Text(stringResource(R.string.test_all)) },
                leadingIcon = { Icon(Icons.Rounded.Speed, null, Modifier.size(16.dp)) },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::deleteDeadServers) {
                Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.delete_dead))
            }
        }

        AnimatedVisibility(visible = busy != null) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val (label, fraction) = when (val state = busy) {
                    is BusyState.Refreshing ->
                        "${stringResource(R.string.updating_sources)} ${state.done}/${state.total}" to
                            state.done.toFloat() / state.total.coerceAtLeast(1)

                    is BusyState.Testing ->
                        "${stringResource(R.string.testing)} ${state.done}/${state.total} · ${state.alive} ✓" to
                            state.done.toFloat() / state.total.coerceAtLeast(1)

                    null -> "" to 0f
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.server_count, serverCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(servers, key = { it.id }) { server ->
                    ServerRow(
                        server = server,
                        selected = server.id == settings.selectedServerId,
                        onClick = { viewModel.selectServer(server.id) },
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
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                Violet.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
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

            LatencyPill(
                latency = server.latency,
                untestedLabel = stringResource(R.string.latency_untested),
                timeoutLabel = stringResource(R.string.latency_timeout),
            )

            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (server.favorite) {
                        Icons.Rounded.Star
                    } else {
                        Icons.Rounded.StarBorder
                    },
                    contentDescription = stringResource(
                        if (server.favorite) R.string.unmark_favorite else R.string.mark_favorite,
                    ),
                    tint = if (server.favorite) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
