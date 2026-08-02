package com.gozar.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gozar.app.BuildConfig
import com.gozar.app.R
import com.gozar.app.data.PerAppMode
import com.gozar.app.data.ThemeMode
import com.gozar.app.ui.components.GlassCard
import com.gozar.app.ui.components.SectionHeader

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    var showAppPicker by remember { mutableStateOf(false) }

    if (showAppPicker) {
        AppPickerDialog(
            selected = settings.perAppList,
            onDismiss = { showAppPicker = false },
            onConfirm = { packages ->
                showAppPicker = false
                viewModel.setPerAppList(packages)
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader(stringResource(R.string.settings_general))
        GlassCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(vertical = 6.dp)) {
                ChipRow(
                    title = stringResource(R.string.settings_language),
                    options = listOf(
                        "fa" to stringResource(R.string.settings_language_fa),
                        "en" to stringResource(R.string.settings_language_en),
                    ),
                    selected = settings.language,
                    onSelect = viewModel::setLanguage,
                )
                ChipRow(
                    title = stringResource(R.string.settings_theme),
                    options = listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                        ThemeMode.LIGHT to stringResource(R.string.theme_light),
                        ThemeMode.DARK to stringResource(R.string.theme_dark),
                    ),
                    selected = settings.theme,
                    onSelect = viewModel::setTheme,
                )
            }
        }

        SectionHeader(stringResource(R.string.settings_core))
        GlassCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.core_singbox),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.core_singbox_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionHeader(stringResource(R.string.settings_network))
        GlassCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(vertical = 6.dp)) {
                SwitchRow(
                    title = stringResource(R.string.bypass_iran),
                    subtitle = stringResource(R.string.bypass_iran_desc),
                    checked = settings.bypassIran,
                    onCheckedChange = viewModel::setBypassIran,
                )
                SwitchRow(
                    title = stringResource(R.string.bypass_lan),
                    checked = settings.bypassLan,
                    onCheckedChange = viewModel::setBypassLan,
                )
                SwitchRow(
                    title = stringResource(R.string.block_ads),
                    checked = settings.blockAds,
                    onCheckedChange = viewModel::setBlockAds,
                )
                SwitchRow(
                    title = stringResource(R.string.ipv6_support),
                    checked = settings.ipv6,
                    onCheckedChange = viewModel::setIpv6,
                )
                ChipRow(
                    title = stringResource(R.string.tun_stack),
                    options = listOf(
                        "mixed" to "mixed",
                        "gvisor" to "gVisor",
                        "system" to "system",
                    ),
                    selected = settings.tunStack,
                    onSelect = viewModel::setTunStack,
                )
                ChipRow(
                    title = stringResource(R.string.remote_dns),
                    options = listOf(
                        "https://1.1.1.1/dns-query" to "Cloudflare",
                        "https://8.8.8.8/dns-query" to "Google",
                        "https://dns.quad9.net/dns-query" to "Quad9",
                    ),
                    selected = settings.remoteDns,
                    onSelect = viewModel::setRemoteDns,
                )
            }
        }

        SectionHeader(stringResource(R.string.settings_apps))
        GlassCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(vertical = 6.dp)) {
                ChipRow(
                    title = stringResource(R.string.per_app_proxy),
                    options = listOf(
                        PerAppMode.OFF to stringResource(R.string.per_app_mode_off),
                        PerAppMode.INCLUDE to stringResource(R.string.per_app_mode_include),
                        PerAppMode.EXCLUDE to stringResource(R.string.per_app_mode_exclude),
                    ),
                    selected = settings.perAppMode,
                    onSelect = viewModel::setPerAppMode,
                )
                if (settings.perAppMode != PerAppMode.OFF) {
                    Text(
                        text = stringResource(R.string.selected_apps, settings.perAppList.size),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAppPicker = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        SectionHeader(stringResource(R.string.settings_behavior))
        GlassCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(vertical = 6.dp)) {
                SwitchRow(
                    title = stringResource(R.string.auto_select_fastest),
                    subtitle = stringResource(R.string.auto_select_fastest_desc),
                    checked = settings.autoSelectFastest,
                    onCheckedChange = viewModel::setAutoSelectFastest,
                )
                SwitchRow(
                    title = stringResource(R.string.auto_update_sources),
                    subtitle = stringResource(R.string.auto_update_desc),
                    checked = settings.autoUpdateSources,
                    onCheckedChange = viewModel::setAutoUpdateSources,
                )
                SwitchRow(
                    title = stringResource(R.string.auto_reconnect),
                    checked = settings.autoReconnect,
                    onCheckedChange = viewModel::setAutoReconnect,
                )
                SwitchRow(
                    title = stringResource(R.string.connect_on_boot),
                    checked = settings.connectOnBoot,
                    onCheckedChange = viewModel::setConnectOnBoot,
                )
                ChipRow(
                    title = stringResource(R.string.test_concurrency),
                    options = listOf(8 to "8", 16 to "16", 24 to "24", 48 to "48"),
                    selected = settings.testConcurrency,
                    onSelect = viewModel::setTestConcurrency,
                )
                ChipRow(
                    title = stringResource(R.string.test_timeout),
                    options = listOf(3 to "3", 5 to "5", 8 to "8", 12 to "12"),
                    selected = settings.testTimeoutSeconds,
                    onSelect = viewModel::setTestTimeout,
                )
            }
        }

        SectionHeader(stringResource(R.string.settings_about))
        GlassCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.about_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> ChipRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}
