package com.gozar.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.gozar.app.R
import com.gozar.app.data.SourceEntity
import com.gozar.app.ui.components.SectionHeader
import com.gozar.app.ui.components.formatRelativeTime
import com.gozar.app.ui.theme.Rose

@Composable
fun SourcesScreen(viewModel: MainViewModel) {
    val sources by viewModel.sources.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    val builtIn = sources.filter { it.builtIn }
    val custom = sources.filterNot { it.builtIn }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = viewModel::refreshSources,
                    label = { Text(stringResource(R.string.update_all_sources)) },
                    leadingIcon = { Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)) },
                )
                AssistChip(
                    onClick = { showAddDialog = true },
                    label = { Text(stringResource(R.string.add_subscription)) },
                    leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = viewModel::importFromClipboard,
                    label = { Text(stringResource(R.string.import_from_clipboard)) },
                    leadingIcon = { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(16.dp)) },
                )
                AssistChip(
                    onClick = viewModel::restoreDefaultSources,
                    label = { Text(stringResource(R.string.restore_default_sources)) },
                    leadingIcon = { Icon(Icons.Rounded.Restore, null, Modifier.size(16.dp)) },
                )
            }
        }

        if (custom.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.custom_sources)) }
            items(custom, key = { it.id }) { source ->
                SourceRow(
                    source = source,
                    onToggle = { viewModel.setSourceEnabled(source.id, it) },
                    onDelete = { viewModel.deleteSource(source) },
                )
            }
        }

        item { SectionHeader(stringResource(R.string.built_in_sources)) }
        items(builtIn, key = { it.id }) { source ->
            SourceRow(
                source = source,
                onToggle = { viewModel.setSourceEnabled(source.id, it) },
                onDelete = { viewModel.deleteSource(source) },
            )
        }
    }

    if (showAddDialog) {
        AddSourceDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { input ->
                showAddDialog = false
                viewModel.addSource(input)
            },
        )
    }
}

@Composable
private fun SourceRow(
    source: SourceEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (source.isTelegram) {
                Icon(
                    Icons.Rounded.Send,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                // stringResource is @Composable, so every lookup happens here
                // rather than inside a plain builder lambda.
                val updatedLabel = stringResource(
                    R.string.last_updated,
                    formatRelativeTime(
                        source.lastUpdated,
                        stringResource(R.string.never_updated),
                    ),
                )
                val countLabel = stringResource(R.string.configs_from_source, source.configCount)
                val subtitle = if (source.configCount > 0) {
                    "$updatedLabel  •  $countLabel"
                } else {
                    updatedLabel
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                source.lastError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Rose,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(checked = source.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_subscription)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.subscription_url)) },
                    placeholder = { Text(stringResource(R.string.subscription_url_hint)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
