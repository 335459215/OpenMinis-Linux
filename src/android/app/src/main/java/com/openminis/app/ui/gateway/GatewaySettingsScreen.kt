package com.openminis.app.ui.gateway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.R
import com.openminis.app.gateway.store.*
import com.openminis.app.ui.components.MinisCenterTopBar
import com.openminis.app.ui.theme.m3Icon
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewaySettingsScreen(onBack: () -> Unit) {
    val vm: GatewaySettingsViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var channelDialog by remember { mutableStateOf<ChannelEntity?>(null) }
    var newChannel by remember { mutableStateOf(false) }
    var accountDialog by remember { mutableStateOf<ChannelAccountEntity?>(null) }
    var newAccount by remember { mutableStateOf(false) }
    var mappingDialog by remember { mutableStateOf<ChannelMappingEntity?>(null) }
    var newMapping by remember { mutableStateOf(false) }
    val selected = state.channels.firstOrNull { it.id == state.selectedChannelId }

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = { Text(stringResource(R.string.gw_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(m3Icon(Icons.AutoMirrored.Filled.ArrowBack), stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(enabled = !state.loading && (tab == 0 || selected != null), onClick = {
                        when (tab) {
                            0 -> { channelDialog = null; newChannel = true }
                            1 -> { accountDialog = null; newAccount = true }
                            else -> { mappingDialog = null; newMapping = true }
                        }
                    }) { Icon(m3Icon(Icons.Default.Add), stringResource(R.string.gw_add)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf(R.string.gw_channels, R.string.gw_accounts, R.string.gw_mappings).forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label)) })
                }
            }
            if (tab != 0 && selected != null) {
                ChannelSelector(state.channels, selected, vm::selectChannel)
            }
            state.error?.let { ErrorBanner(it, vm::refresh) }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                tab == 0 -> ChannelList(state.channels, { channelDialog = it; newChannel = false }, vm::deleteChannel)
                selected == null -> EmptyState(R.string.gw_no_channels)
                tab == 1 -> AccountList(state.accounts, state.health, { accountDialog = it; newAccount = false }, vm::deleteAccount)
                else -> MappingList(state.mappings, { mappingDialog = it; newMapping = false }, vm::deleteMapping)
            }
        }
    }
    if (newChannel || channelDialog != null) {
        ChannelDialog(channelDialog, { newChannel = false; channelDialog = null }, vm::saveChannel)
    }
    if (selected != null && (newAccount || accountDialog != null)) {
        AccountDialog(accountDialog, selected.id, { newAccount = false; accountDialog = null }, vm::saveAccount)
    }
    if (selected != null && (newMapping || mappingDialog != null)) {
        MappingDialog(mappingDialog, selected.id, { newMapping = false; mappingDialog = null }, vm::saveMapping)
    }
}

@Composable
private fun ChannelSelector(channels: List<ChannelEntity>, selected: ChannelEntity, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.name)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            channels.forEach { channel ->
                DropdownMenuItem(text = { Text(channel.name) }, onClick = { onSelect(channel.id); expanded = false })
            }
        }
    }
}

@Composable
private fun ChannelList(items: List<ChannelEntity>, onEdit: (ChannelEntity) -> Unit, onDelete: (String) -> Unit) {
    if (items.isEmpty()) return EmptyState(R.string.gw_empty_channels)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.id }) { channel ->
            ItemCard(channel.name, channel.protocol + "  |  " + channel.baseUrl,
                stringResource(R.string.gw_sort_order) + ": " + channel.sortOrder,
                { onEdit(channel) }, { onDelete(channel.id) })
        }
    }
}

@Composable
private fun AccountList(items: List<ChannelAccountEntity>, health: Map<String, GatewayAccountHealth>,
    onEdit: (ChannelAccountEntity) -> Unit, onDelete: (String) -> Unit) {
    if (items.isEmpty()) return EmptyState(R.string.gw_empty_accounts)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.id }) { account ->
            ItemCard(account.name,
                stringResource(R.string.gw_priority) + ": " + account.priority + "  |  " +
                    stringResource(R.string.gw_max_concurrent) + ": " + account.maxConcurrent,
                stringResource(R.string.gw_key_prefix) + " " + (account.keyTail.ifEmpty { "----" }),
                { onEdit(account) }, { onDelete(account.id) },
                badge = { health[account.id]?.let { HealthBadge(it) } })
        }
    }
}

@Composable
private fun MappingList(items: List<ChannelMappingEntity>, onEdit: (ChannelMappingEntity) -> Unit, onDelete: (String) -> Unit) {
    if (items.isEmpty()) return EmptyState(R.string.gw_empty_mappings)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.id }) { mapping ->
            ItemCard(mapping.alias, stringResource(R.string.gw_target) + ": " + mapping.target, null,
                { onEdit(mapping) }, { onDelete(mapping.id) })
        }
    }
}

@Composable
private fun ItemCard(title: String, detail: String, footer: String?, onEdit: () -> Unit,
    onDelete: () -> Unit, badge: (@Composable () -> Unit)? = null) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    badge?.invoke()
                }
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                footer?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            IconButton(onClick = onEdit) { Icon(m3Icon(Icons.Default.Edit), stringResource(R.string.common_edit)) }
            IconButton(onClick = onDelete) {
                Icon(m3Icon(Icons.Default.Delete), stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun HealthBadge(health: GatewayAccountHealth) {
    val (label, color) = when (health.state) {
        GatewayHealthState.HEALTHY -> R.string.gw_health_healthy to Color(0xFF2E7D32)
        GatewayHealthState.BACKOFF -> R.string.gw_health_backoff to Color(0xFFED6C02)
        GatewayHealthState.COOLDOWN -> R.string.gw_health_cooldown to Color(0xFFED6C02)
        GatewayHealthState.BREAKER_OPEN -> R.string.gw_health_breaker to MaterialTheme.colorScheme.error
        GatewayHealthState.RATE_LIMIT_SINK -> R.string.gw_health_rate_limit to MaterialTheme.colorScheme.error
    }
    Column(horizontalAlignment = Alignment.End) {
        AssistChip(onClick = {}, label = { Text(stringResource(label)) }, colors = AssistChipDefaults.assistChipColors(labelColor = color))
        health.retryAtMs?.let {
            Text(stringResource(R.string.gw_retry_at) + " " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyState(message: Int) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorBanner(error: String, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.gw_retry)) }
        }
    }
}

@Composable
private fun FormDialog(title: Int, onDismiss: () -> Unit, onSave: () -> Unit, fields: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = fields) },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: Int, secret: Boolean = false, number: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(stringResource(label)) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Text))
}

@Composable
private fun ChannelDialog(existing: ChannelEntity?, onDismiss: () -> Unit,
    onSave: (ChannelEntity?, String, String, String, Int) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var protocol by remember { mutableStateOf(existing?.protocol ?: "openai") }
    var url by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var order by remember { mutableStateOf(existing?.sortOrder?.toString() ?: "0") }
    FormDialog(if (existing == null) R.string.gw_add_channel else R.string.gw_edit_channel, onDismiss,
        { if (name.isNotBlank() && url.isNotBlank()) { onSave(existing, name.trim(), protocol, url.trim(), order.toIntOrNull() ?: 0); onDismiss() } }) {
        Field(name, { name = it }, R.string.gw_name)
        Field(protocol, { protocol = it }, R.string.gw_protocol)
        Field(url, { url = it }, R.string.gw_base_url)
        Field(order, { order = it }, R.string.gw_sort_order, number = true)
    }
}

@Composable
private fun AccountDialog(existing: ChannelAccountEntity?, channelId: String, onDismiss: () -> Unit, onSave: (GatewayAccountDraft) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var priority by remember { mutableStateOf(existing?.priority?.toString() ?: "0") }
    var concurrent by remember { mutableStateOf(existing?.maxConcurrent?.toString() ?: "0") }
    var key by remember { mutableStateOf("") }
    FormDialog(if (existing == null) R.string.gw_add_account else R.string.gw_edit_account, onDismiss, {
        if (name.isNotBlank() && (existing != null || key.isNotBlank())) {
            onSave(GatewayAccountDraft(existing?.id, channelId, name.trim(), priority.toIntOrNull() ?: 0,
                concurrent.toIntOrNull() ?: 0, key)); onDismiss()
        }
    }) {
        Field(name, { name = it }, R.string.gw_name)
        Field(priority, { priority = it }, R.string.gw_priority, number = true)
        Field(concurrent, { concurrent = it }, R.string.gw_max_concurrent, number = true)
        Field(key, { key = it }, if (existing == null) R.string.gw_api_key else R.string.gw_replace_key, secret = true)
        Text(stringResource(R.string.gw_key_privacy), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MappingDialog(existing: ChannelMappingEntity?, channelId: String, onDismiss: () -> Unit,
    onSave: (ChannelMappingEntity?, String, String, String) -> Unit) {
    var alias by remember { mutableStateOf(existing?.alias.orEmpty()) }
    var target by remember { mutableStateOf(existing?.target.orEmpty()) }
    FormDialog(if (existing == null) R.string.gw_add_mapping else R.string.gw_edit_mapping, onDismiss, {
        if (alias.isNotBlank() && target.isNotBlank()) { onSave(existing, channelId, alias.trim(), target.trim()); onDismiss() }
    }) {
        Field(alias, { alias = it }, R.string.gw_alias)
        Field(target, { target = it }, R.string.gw_target)
    }
}
