package com.gozar.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gozar.app.data.SubscriptionInfo
import com.gozar.app.data.ThemeMode
import com.gozar.app.model.Flags
import com.gozar.app.model.Protocol
import com.gozar.desktop.AppState
import com.gozar.desktop.Busy
import com.gozar.desktop.ConnectionStatus
import com.gozar.desktop.ServerFilter
import com.gozar.desktop.data.ServerRecord

private enum class Screen { CONNECT, SERVERS, SOURCES, SETTINGS }

@Composable
fun GozarApp(state: AppState) {
    val settings by state.settings.collectAsState()
    val status by state.status.collectAsState()
    var screen by remember { mutableStateOf(Screen.CONNECT) }
    val strings = Strings.forLanguage(settings.language)
    val rtl = settings.language.startsWith("fa")

    GozarTheme(settings.theme) {
        // Persian flips the whole window, so the sidebar lands on the right and
        // every row reads the way the text does.
        CompositionLocalProvider(
            LocalStrings provides strings,
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            val shades = LocalElevations.current
            Surface(Modifier.fillMaxSize(), color = shades.canvas) {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(screen, status, strings) { screen = it }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                // A single wash of accent behind the content so
                                // the plane reads as lit rather than painted.
                                Brush.radialGradient(
                                    colors = listOf(
                                        shades.glow.copy(alpha = 0.10f),
                                        shades.canvas,
                                    ),
                                    radius = 1400f,
                                ),
                            ),
                    ) {
                        when (screen) {
                            Screen.CONNECT -> ConnectScreen(state)
                            Screen.SERVERS -> ServersScreen(state)
                            Screen.SOURCES -> SourcesScreen(state)
                            Screen.SETTINGS -> SettingsScreen(state)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- Sidebar

@Composable
private fun Sidebar(
    current: Screen,
    status: ConnectionStatus,
    text: Strings,
    onSelect: (Screen) -> Unit,
) {
    val shades = LocalElevations.current
    Column(
        Modifier
            .width(226.dp)
            .fillMaxHeight()
            .background(shades.sidebar)
            .padding(horizontal = 16.dp, vertical = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Brush.linearGradient(listOf(Violet, Sky))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Shield, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text(
                    text.appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text.tagline,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        StatusBadge(status, text)
        Spacer(Modifier.height(22.dp))

        NavItem(current == Screen.CONNECT, Icons.Rounded.Shield, text.connect) {
            onSelect(Screen.CONNECT)
        }
        NavItem(current == Screen.SERVERS, Icons.Rounded.Language, text.servers) {
            onSelect(Screen.SERVERS)
        }
        NavItem(current == Screen.SOURCES, Icons.Rounded.Dns, text.sources) {
            onSelect(Screen.SOURCES)
        }
        NavItem(current == Screen.SETTINGS, Icons.Rounded.Settings, text.settings) {
            onSelect(Screen.SETTINGS)
        }

        Spacer(Modifier.weight(1f))
        Text(
            "v1.0.0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun StatusBadge(status: ConnectionStatus, text: Strings) {
    val tint = when (status) {
        ConnectionStatus.CONNECTED -> Mint
        ConnectionStatus.CONNECTING, ConnectionStatus.STOPPING -> Violet
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (status) {
        ConnectionStatus.CONNECTED -> text.statusConnected
        ConnectionStatus.CONNECTING -> text.statusConnecting
        ConnectionStatus.STOPPING -> text.statusStopping
        ConnectionStatus.DISCONNECTED -> text.statusDisconnected
    }
    Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = 0.12f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NavItem(selected: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            selected -> Violet.copy(alpha = 0.18f)
            hovered -> LocalElevations.current.cardHover
            else -> Color.Transparent
        },
        tween(150),
        label = "navBackground",
    )
    val tint by animateColorAsState(
        if (selected) Violet else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(150),
        label = "navTint",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(background)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** The heading every content screen starts with. */
@Composable
private fun PageHeader(title: String, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

// ------------------------------------------------------------------- Connect

@Composable
private fun ConnectScreen(state: AppState) {
    val status by state.status.collectAsState()
    val progress by state.progress.collectAsState()
    val delay by state.delayMs.collectAsState()
    val servers by state.servers.collectAsState()
    val activeId by state.activeServerId.collectAsState()
    val traffic by state.throughput.collectAsState()
    val active = servers.firstOrNull { it.id == activeId }
    val connected = status == ConnectionStatus.CONNECTED
    val connecting = status == ConnectionStatus.CONNECTING
    val text = LocalStrings.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ConnectButton(
            status = status,
            onClick = {
                when (status) {
                    // While an attempt is in flight the control is the way to
                    // call it off — nothing else stops the loop.
                    ConnectionStatus.CONNECTING -> state.cancelConnect()
                    ConnectionStatus.CONNECTED -> state.stop()
                    else -> state.connect()
                }
            },
        )

        Text(
            text = when (status) {
                ConnectionStatus.CONNECTED -> text.statusConnected
                ConnectionStatus.CONNECTING -> text.statusConnecting
                ConnectionStatus.STOPPING -> text.statusStopping
                ConnectionStatus.DISCONNECTED -> text.statusDisconnected
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = when (status) {
                ConnectionStatus.CONNECTED -> Mint
                ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurface
                else -> Violet
            },
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = progress?.let {
                text.tryingServer.fill(it.index, it.total, it.round, it.server)
            } ?: when {
                connected -> text.routedThroughProxy
                else -> text.clickToConnect
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        // The loop no longer gives up, so the reason the last one failed is the
        // only thing distinguishing "still working" from "never going to work".
        progress?.lastError?.let { reason ->
            Spacer(Modifier.height(3.dp))
            Text(
                text = text.lastFailure.fill(reason),
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(connecting, enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = state::cancelConnect) { Text(text.cancel) }
            }
        }

        Spacer(Modifier.height(26.dp))

        Card(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, null, tint = Violet, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                SectionLabel(text.activeServer)
            }
            Spacer(Modifier.height(12.dp))
            if (active == null) {
                Text(
                    text.noServerSelected,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Flags.forName(active.name)?.let {
                        Text(it, fontSize = 26.sp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        Flags.stripFlag(active.name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(11.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active.measured) {
                        Chip(formatSpeed(active.speedKb * 1024L), tint = Sky)
                    }
                    Chip(active.protocol, tint = Violet)
                    if (active.domesticEntry) Chip(text.domesticEntry, tint = Amber)
                    LatencyPill(active.shownLatency, proven = active.proven)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                text.tileServers,
                servers.size.toString(),
                Violet,
                Icons.Rounded.Storage,
                Modifier.weight(1f),
            )
            StatTile(
                text.tileWorking,
                servers.count { it.proven }.toString(),
                Mint,
                Icons.Rounded.Bolt,
                Modifier.weight(1f),
            )
            StatTile(
                text.tileDelay,
                if (delay > 0) text.milliseconds.fill(delay) else "—",
                Sky,
                Icons.Rounded.Timer,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                text.tileDownload,
                if (connected) formatSpeed(traffic.downBytesPerSecond) else "—",
                Mint,
                Icons.Rounded.Download,
                Modifier.weight(1f),
            )
            StatTile(
                text.tileUpload,
                if (connected) formatSpeed(traffic.upBytesPerSecond) else "—",
                Violet,
                Icons.Rounded.Upload,
                Modifier.weight(1f),
            )
            StatTile(
                text.tileSession,
                if (connected) formatBytes(traffic.downTotal + traffic.upTotal) else "—",
                Amber,
                Icons.Rounded.Speed,
                Modifier.weight(1f),
            )
        }
    }
}

// ------------------------------------------------------------------- Servers

@Composable
private fun ServersScreen(state: AppState) {
    val all by state.servers.collectAsState()
    val query by state.search.collectAsState()
    val filter by state.filter.collectAsState()
    val busy by state.busy.collectAsState()
    val status by state.status.collectAsState()
    val activeId by state.activeServerId.collectAsState()
    val settings by state.settings.collectAsState()
    // Filtering and sorting thousands of rows is not something to do on every
    // recomposition — and a recomposition happens on every keystroke, every
    // hover and every progress tick. Recomputed only when an input really
    // changes, which is what made typing in the search box feel heavy.
    val visible = remember(all, query, filter, activeId) {
        state.visibleServers(all, query, filter, activeId)
    }
    val text = LocalStrings.current

    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 22.dp)) {
        PageHeader(
            title = text.servers,
            subtitle = text.serverCount.fill(all.size, all.count { it.proven }),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = state::refreshSources,
                    label = { Text(text.fetchConfigs) },
                    leadingIcon = { Icon(Icons.Rounded.CloudDownload, null, Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = Violet),
                )
                AssistChip(
                    onClick = state::testAll,
                    label = { Text(text.testAll) },
                    leadingIcon = { Icon(Icons.Rounded.Speed, null, Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = Mint),
                )
                IconButton(onClick = state::removeDead) {
                    Icon(Icons.Rounded.DeleteSweep, text.removeDead)
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = state::setSearch,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(text.searchServers) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServerFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { state.setFilter(option) },
                    label = {
                        Text(
                            when (option) {
                                ServerFilter.ALL -> text.filterAll
                                ServerFilter.WORKING -> text.filterWorking
                                ServerFilter.FAVORITE -> text.filterStarred
                                ServerFilter.DOMESTIC ->
                                    "${text.filterDomestic} (${all.count { it.domesticEntry }})"
                            },
                        )
                    },
                    shape = RoundedCornerShape(11.dp),
                    leadingIcon = if (option == ServerFilter.DOMESTIC) {
                        { Icon(Icons.Rounded.Home, null, Modifier.size(15.dp)) }
                    } else {
                        null
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Violet.copy(alpha = 0.20f),
                        selectedLabelColor = Violet,
                    ),
                )
            }
        }

        AnimatedVisibility(busy != null, enter = fadeIn(), exit = fadeOut()) {
            Column {
                Spacer(Modifier.height(12.dp))
                val (label, fraction, tint) = when (val current = busy) {
                    is Busy.Refreshing -> Triple(
                        "${text.updatingSources}  ${current.done}/${current.total}",
                        current.done.toFloat() / current.total.coerceAtLeast(1),
                        Violet,
                    )

                    is Busy.Testing -> Triple(
                        "${text.testing}  ${current.done}/${current.total}  ·  ${current.alive} ✓",
                        current.done.toFloat() / current.total.coerceAtLeast(1),
                        Mint,
                    )

                    is Busy.Measuring -> Triple(
                        "${text.measuring}  ${current.done}/${current.total}  ·  ${current.alive} ✓",
                        current.done.toFloat() / current.total.coerceAtLeast(1),
                        Sky,
                    )

                    is Busy.Speed -> Triple(
                        "${text.speedTesting}  ${current.done}/${current.total}" +
                            if (current.bestKb > 0) {
                                "  ·  ${formatSpeed(current.bestKb * 1024L)}"
                            } else {
                                ""
                            },
                        current.done.toFloat() / current.total.coerceAtLeast(1),
                        Amber,
                    )

                    null -> Triple("", 0f, Violet)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = state::cancelBusy) { Text(text.cancel) }
                }
                Spacer(Modifier.height(4.dp))
                ProgressTrack(fraction, tint, Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(14.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(visible, key = { it.id }) { server ->
                ServerRow(
                    server = server,
                    active = server.id == activeId && status != ConnectionStatus.DISCONNECTED,
                    dimmed = !settings.allowsProtocol(server.protocol),
                    onClick = { state.connect(server.id) },
                    onToggleFavorite = { state.toggleFavorite(server.id) },
                )
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerRecord,
    active: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val text = LocalStrings.current
    HoverRow(onClick = onClick, accent = if (active) Mint else null) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp).alphaIf(dimmed),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Flags.forName(server.name)?.let {
                Text(it, fontSize = 19.sp)
                Spacer(Modifier.width(11.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    Flags.stripFlag(server.name),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (active) MintDeep else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) {
                        Chip(text.statusConnected, tint = MintDeep)
                    }
                    // The measured figure leads: it is what the choice is
                    // actually made on, and the only one that says whether the
                    // server has any bandwidth left to give.
                    if (server.measured) Chip(formatSpeed(server.speedKb * 1024L), tint = Sky)
                    if (server.domesticEntry) Chip(text.domestic, tint = Amber)
                    Chip(server.protocol, tint = Violet)
                    Text(
                        server.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            LatencyPill(server.shownLatency, proven = server.proven)
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (server.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    text.star,
                    tint = if (server.favorite) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

/** Excluded by the protocol filter: still listed, visibly not in play. */
private fun Modifier.alphaIf(dimmed: Boolean): Modifier =
    if (dimmed) this.then(Modifier.alpha(0.42f)) else this

// ------------------------------------------------------------------- Sources

@Composable
private fun SourcesScreen(state: AppState) {
    val sources by state.sources.collectAsState()
    var input by remember { mutableStateOf("") }
    val text = LocalStrings.current

    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 22.dp)) {
        PageHeader(
            title = text.sources,
            subtitle = text.sourceCount.fill(sources.size, sources.count { it.enabled }),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = state::refreshSources,
                    label = { Text(text.updateAll) },
                    leadingIcon = { Icon(Icons.Rounded.CloudDownload, null, Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = Violet),
                )
                AssistChip(
                    onClick = state::restoreDefaultSources,
                    label = { Text(text.restoreDefaults) },
                    leadingIcon = { Icon(Icons.Rounded.Restore, null, Modifier.size(16.dp)) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(text.pasteHint) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(10.dp))
            TextButton(
                onClick = {
                    state.addPasted(input)
                    input = ""
                },
                enabled = input.isNotBlank(),
            ) { Text(text.add) }
        }

        Spacer(Modifier.height(14.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(sources, key = { it.url }) { source ->
                HoverRow(onClick = { state.setSourceEnabled(source.url, !source.enabled) }) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                source.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                listOfNotNull(
                                    text.updatedAgo.fill(
                                        formatRelative(source.lastUpdated, text.never),
                                    ),
                                    text.configCount.fill(source.configCount)
                                        .takeIf { source.configCount > 0 },
                                    source.lastError,
                                ).joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (source.lastError != null) {
                                    Rose
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            source.subscription?.let { SubscriptionStrip(it) }
                        }
                        Switch(
                            checked = source.enabled,
                            onCheckedChange = { state.setSourceEnabled(source.url, it) },
                        )
                        IconButton(onClick = { state.deleteSource(source.url) }) {
                            Icon(Icons.Rounded.DeleteSweep, text.delete)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Traffic and expiry for a paid subscription, from the `subscription-userinfo`
 * header. Only drawn when the panel reported something — a free link sends no
 * header, and a bar reading zero would be worse than none at all.
 */
@Composable
private fun SubscriptionStrip(info: SubscriptionInfo) {
    val text = LocalStrings.current
    val days = info.daysLeft()
    val fraction = info.fractionUsed
    val tint = when {
        days != null && days < 0 -> Rose
        fraction != null && fraction > 0.9f -> Rose
        (days != null && days <= 3) || (fraction != null && fraction > 0.75f) -> Amber
        else -> MaterialTheme.colorScheme.primary
    }

    Spacer(Modifier.height(7.dp))
    if (fraction != null) {
        ProgressTrack(fraction, tint, Modifier.fillMaxWidth())
        Spacer(Modifier.height(5.dp))
    }
    val quota = when {
        info.total > 0 -> text.quotaUsed.fill(formatBytes(info.used), formatBytes(info.total))
        info.used > 0 -> text.quotaUsedOnly.fill(formatBytes(info.used))
        else -> null
    }
    val expiry = when {
        days == null -> null
        days < 0 -> text.expired
        else -> text.daysLeft.fill(days)
    }
    val label = listOfNotNull(quota, expiry).joinToString("  •  ")
    if (label.isNotEmpty()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    val log by state.log.collectAsState()
    val text = LocalStrings.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 22.dp),
    ) {
        PageHeader(title = text.settings)

        SectionLabel(text.sectionRouting)
        Spacer(Modifier.height(9.dp))
        Card(Modifier.fillMaxWidth(), padding = 6) {
            SwitchRow(text.bypassIran, settings.bypassIran) { value ->
                state.update { it.copy(bypassIran = value) }
            }
            SwitchRow(text.bypassLan, settings.bypassLan) { value ->
                state.update { it.copy(bypassLan = value) }
            }
            SwitchRow(text.blockAds, settings.blockAds) { value ->
                state.update { it.copy(blockAds = value) }
            }
            SwitchRow(text.autoFastest, settings.autoSelectFastest) { value ->
                state.update { it.copy(autoSelectFastest = value) }
            }
            SwitchRow(text.pruneDead, settings.pruneDead) { value ->
                state.update { it.copy(pruneDead = value) }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel(text.sectionProtocols)
        Spacer(Modifier.height(9.dp))
        Card(Modifier.fillMaxWidth()) {
            Text(
                text.protocolsHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(11.dp))
            ProtocolPicker(settings.allowedProtocols) { chosen ->
                state.update { it.copy(allowedProtocols = chosen) }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel(text.sectionLanguage)
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Written in their own script, so the choice is legible whichever
            // language is currently active.
            listOf("fa" to "فارسی", "en" to "English").forEach { (tag, label) ->
                ChoiceChip(settings.language.startsWith(tag), label) {
                    state.update { it.copy(language = tag) }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel(text.sectionAppearance)
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                ChoiceChip(
                    settings.theme == mode,
                    when (mode) {
                        ThemeMode.SYSTEM -> text.themeSystem
                        ThemeMode.LIGHT -> text.themeLight
                        ThemeMode.DARK -> text.themeDark
                    },
                ) { state.update { it.copy(theme = mode) } }
            }
        }

        Spacer(Modifier.height(20.dp))
        val clipboard = LocalClipboardManager.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(text.sectionDiagnostics, Modifier.weight(1f))
            // Reading this off the screen and retyping it is the difference
            // between a reproducible report and a guess.
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) },
                enabled = log.isNotEmpty(),
            ) { Text(text.copy) }
            TextButton(onClick = state::clearLog) { Text(text.clear) }
        }
        Spacer(Modifier.height(9.dp))
        Card(Modifier.fillMaxWidth(), padding = 14) {
            Text(
                state.coreLocation,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (state.coreLocation.contains("NOT FOUND")) Rose else MintDeep,
            )
            Spacer(Modifier.height(8.dp))
            if (log.isEmpty()) {
                Text(
                    text.nothingLogged,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // The log is English and machine-readable; forcing LTR keeps
                // lines like "core: inbound/mixed[local-in]" from being
                // reordered by the surrounding RTL layout.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column {
                        // Newest first: the last attempt is what matters.
                        log.asReversed().take(60).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Left,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Which protocols the connect loop may use. An empty selection means all of
 * them, so a protocol added in a later version is not silently excluded by a
 * choice made today.
 */
@Composable
private fun ProtocolPicker(chosen: Set<String>, onChange: (Set<String>) -> Unit) {
    val text = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(chosen.isEmpty(), text.protocolsAll) { onChange(emptySet()) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Protocol.entries.forEach { protocol ->
                ChoiceChip(protocol.name in chosen, protocol.label) {
                    onChange(
                        if (protocol.name in chosen) chosen - protocol.name
                        else chosen + protocol.name,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(11.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Violet.copy(alpha = 0.20f),
            selectedLabelColor = Violet,
        ),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .hoverable(interaction)
            .clickable(interaction, indication = null) { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
