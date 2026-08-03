package com.gozar.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
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
import com.gozar.desktop.AppState
import com.gozar.desktop.Busy
import com.gozar.desktop.ConnectionStatus
import com.gozar.desktop.ServerFilter
import com.gozar.desktop.data.ServerRecord

private enum class Screen { CONNECT, SERVERS, SOURCES, SETTINGS }

@Composable
fun GozarApp(state: AppState) {
    val settings by state.settings.collectAsState()
    var screen by remember { mutableStateOf(Screen.CONNECT) }
    val strings = Strings.forLanguage(settings.language)

    GozarTheme(settings.theme) {
        // Persian flips the whole window, so the navigation rail lands on the
        // right and every row reads the way the text does.
        CompositionLocalProvider(
            LocalStrings provides strings,
            LocalLayoutDirection provides
                if (settings.language.startsWith("fa")) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        Spacer(Modifier.height(12.dp))
                        RailItem(screen, Screen.CONNECT, Icons.Rounded.Shield, strings.connect) {
                            screen = it
                        }
                        RailItem(screen, Screen.SERVERS, Icons.Rounded.Language, strings.servers) {
                            screen = it
                        }
                        RailItem(screen, Screen.SOURCES, Icons.Rounded.Dns, strings.sources) {
                            screen = it
                        }
                        RailItem(screen, Screen.SETTINGS, Icons.Rounded.Settings, strings.settings) {
                            screen = it
                        }
                    }

                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(
                                    Violet.copy(alpha = 0.10f),
                                    MaterialTheme.colorScheme.background,
                                ),
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

@Composable
private fun ColumnScope.RailItem(
    current: Screen,
    target: Screen,
    icon: ImageVector,
    label: String,
    onSelect: (Screen) -> Unit,
) {
    NavigationRailItem(
        selected = current == target,
        onClick = { onSelect(target) },
        icon = { Icon(icon, null) },
        label = { Text(label) },
    )
}

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
    val text = LocalStrings.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text.appName, style = MaterialTheme.typography.headlineMedium)
        Text(
            text.tagline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        ConnectButton(
            status = status,
            onClick = {
                if (status == ConnectionStatus.CONNECTED) state.stop() else state.connect()
            },
        )

        Text(
            text = when (status) {
                ConnectionStatus.CONNECTED -> text.statusConnected
                ConnectionStatus.CONNECTING -> text.statusConnecting
                ConnectionStatus.STOPPING -> text.statusStopping
                ConnectionStatus.DISCONNECTED -> text.statusDisconnected
            },
            style = MaterialTheme.typography.titleLarge,
            color = when (status) {
                ConnectionStatus.CONNECTED -> Mint
                ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> Violet
            },
        )
        Text(
            text = progress ?: if (connected) text.routedThroughProxy else text.clickToConnect,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(22.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = Violet, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text.activeServer,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (active == null) {
                    Text(
                        text.noServerSelected,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Flags.forName(active.name)?.let {
                            Text(it, fontSize = 22.sp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            Flags.stripFlag(active.name),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(active.protocol)
                        if (active.domesticEntry) Chip(text.domesticEntry)
                        LatencyPill(active.latency)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(text.tileServers, servers.size.toString(), Violet, Modifier.weight(1f))
            StatTile(
                text.tileWorking,
                servers.count { it.latency > 0 }.toString(),
                Mint,
                Modifier.weight(1f),
            )
            StatTile(
                text.tileDelay,
                if (delay > 0) text.milliseconds.fill(delay) else "—",
                MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                text.tileDownload,
                if (connected) formatSpeed(traffic.downBytesPerSecond) else "—",
                Mint,
                Modifier.weight(1f),
            )
            StatTile(
                text.tileUpload,
                if (connected) formatSpeed(traffic.upBytesPerSecond) else "—",
                Violet,
                Modifier.weight(1f),
            )
            StatTile(
                text.tileSession,
                if (connected) formatBytes(traffic.downTotal + traffic.upTotal) else "—",
                MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ServersScreen(state: AppState) {
    val all by state.servers.collectAsState()
    val query by state.search.collectAsState()
    val filter by state.filter.collectAsState()
    val busy by state.busy.collectAsState()
    val status by state.status.collectAsState()
    val activeId by state.activeServerId.collectAsState()
    val visible = state.visibleServers(all, query, filter, activeId)
    val text = LocalStrings.current

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = state::setSearch,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(text.searchServers) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
        )
        Spacer(Modifier.height(10.dp))

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
                    leadingIcon = if (option == ServerFilter.DOMESTIC) {
                        { Icon(Icons.Rounded.Home, null, Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = state::refreshSources,
                label = { Text(text.fetchConfigs) },
                leadingIcon = { Icon(Icons.Rounded.CloudDownload, null, Modifier.size(16.dp)) },
            )
            AssistChip(
                onClick = state::testAll,
                label = { Text(text.testAll) },
                leadingIcon = { Icon(Icons.Rounded.Speed, null, Modifier.size(16.dp)) },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = state::removeDead) {
                Icon(Icons.Rounded.DeleteSweep, text.removeDead)
            }
        }

        AnimatedVisibility(busy != null) {
            Column {
                Spacer(Modifier.height(8.dp))
                val (label, fraction) = when (val current = busy) {
                    is Busy.Refreshing ->
                        "${text.updatingSources}  ${current.done}/${current.total}" to
                            current.done.toFloat() / current.total.coerceAtLeast(1)

                    is Busy.Testing ->
                        "${text.testing}  ${current.done}/${current.total}  ·  ${current.alive} ✓" to
                            current.done.toFloat() / current.total.coerceAtLeast(1)

                    null -> "" to 0f
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = state::cancelBusy) { Text(text.cancel) }
                }
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = { it.id }) { server ->
                ServerRow(
                    server = server,
                    active = server.id == activeId && status != ConnectionStatus.DISCONNECTED,
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
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val container by animateColorAsState(
        if (active) Mint.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
        tween(280),
        label = "container",
    )
    val borderColor by animateColorAsState(
        if (active) Mint else MaterialTheme.colorScheme.outlineVariant,
        tween(280),
        label = "border",
    )
    val text = LocalStrings.current

    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = container,
    ) {
        Box(
            Modifier.border(
                if (active) 1.5.dp else 1.dp,
                borderColor,
                RoundedCornerShape(16.dp),
            ),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Flags.forName(server.name)?.let {
                    Text(it, fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
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
                            Text(
                                text.statusConnected,
                                style = MaterialTheme.typography.labelSmall,
                                color = MintDeep,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (server.domesticEntry) Chip(text.domestic)
                        Chip(server.protocol)
                        Text(
                            server.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                LatencyPill(server.latency)
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (server.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        text.star,
                        tint = if (server.favorite) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SourcesScreen(state: AppState) {
    val sources by state.sources.collectAsState()
    var input by remember { mutableStateOf("") }
    val text = LocalStrings.current

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(text.sourceHint) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            TextButton(
                onClick = {
                    state.addSource(input)
                    input = ""
                },
                enabled = input.isNotBlank(),
            ) { Text(text.add) }
            AssistChip(
                onClick = state::refreshSources,
                label = { Text(text.updateAll) },
                leadingIcon = { Icon(Icons.Rounded.CloudDownload, null, Modifier.size(16.dp)) },
            )
            Spacer(Modifier.width(8.dp))
            AssistChip(
                onClick = state::restoreDefaultSources,
                label = { Text(text.restoreDefaults) },
                leadingIcon = { Icon(Icons.Rounded.Restore, null, Modifier.size(16.dp)) },
            )
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources, key = { it.url }) { source ->
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(source.name, style = MaterialTheme.typography.bodyLarge)
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

    Spacer(Modifier.height(6.dp))
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            color = tint,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(4.dp))
    }
    val quota = when {
        info.total > 0 ->
            text.quotaUsed.fill(formatBytes(info.used), formatBytes(info.total))

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

@Composable
private fun SettingsScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    val log by state.log.collectAsState()
    val text = LocalStrings.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(text.sectionRouting, style = MaterialTheme.typography.labelLarge, color = Violet)
        Spacer(Modifier.height(8.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
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
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(text.sectionLanguage, style = MaterialTheme.typography.labelLarge, color = Violet)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Written in their own script, so the choice is legible whichever
            // language is currently active.
            listOf("fa" to "فارسی", "en" to "English").forEach { (tag, label) ->
                FilterChip(
                    selected = settings.language.startsWith(tag),
                    onClick = { state.update { it.copy(language = tag) } },
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(text.sectionAppearance, style = MaterialTheme.typography.labelLarge, color = Violet)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.theme == mode,
                    onClick = { state.update { it.copy(theme = mode) } },
                    label = {
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> text.themeSystem
                                ThemeMode.LIGHT -> text.themeLight
                                ThemeMode.DARK -> text.themeDark
                            },
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text.sectionDiagnostics,
                style = MaterialTheme.typography.labelLarge,
                color = Violet,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = state::clearLog) { Text(text.clear) }
        }
        Spacer(Modifier.height(8.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
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
                            log.asReversed().take(40).forEach { line ->
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
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
