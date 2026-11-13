package com.openminis.app.ui.ssh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ssh.SshConfigStore
import com.openminis.app.ssh.SshHostConfig
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisCenterTopBar
import com.openminis.app.ui.theme.m3Icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-android-ssh-settings] Host management screen for the remote SSH backend.
 *
 * Before this screen existed the only way to add a host was the `SshTool`
 * `add_host` action, i.e. asking the agent. This gives the same operations a
 * touch surface: list / add / edit / delete.
 *
 * Credentials are never rendered. Row subtitles use [SshHostConfig.describe],
 * which is the sanitised form, and the editor writes secrets straight into the
 * keystore-backed store instead of keeping them in screen state longer than the
 * single save call.
 */
@Composable
fun SshHostsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { SshConfigStore.forContext(context) }

    var hosts by remember { mutableStateOf<List<SshHostConfig>>(emptyList()) }
    var editorTarget by remember { mutableStateOf<SshHostConfig?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SshHostConfig?>(null) }

    suspend fun reload() {
        hosts = withContext(Dispatchers.IO) { store.list() }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = { Text(stringResource(R.string.ssh_hosts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            m3Icon(Icons.AutoMirrored.Filled.ArrowBack),
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editorTarget = null
                        showEditor = true
                    }) {
                        Icon(
                            m3Icon(Icons.Default.Add),
                            contentDescription = stringResource(R.string.ssh_host_add),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.ssh_hosts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(hosts, key = { it.id }) { host ->
                    SshHostRow(
                        host = host,
                        onEdit = {
                            editorTarget = host
                            showEditor = true
                        },
                        onDelete = { pendingDelete = host },
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }

    if (showEditor) {
        SshHostEditDialog(
            initial = editorTarget,
            existingIds = hosts.map { it.id }.toSet(),
            onDismiss = { showEditor = false },
            onSave = { config, password, privateKey, passphrase ->
                label@ scope.launch {
                    withContext(Dispatchers.IO) {
                        store.put(
                            config,
                            com.openminis.app.ssh.SshCredentials(
                                password = password,
                                privateKeyPem = privateKey,
                                passphrase = passphrase,
                            ),
                        )
                    }
                    reload()
                    showEditor = false
                }
            },
        )
    }

    pendingDelete?.let { target ->
        MinisAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = target.name,
            text = stringResource(R.string.ssh_host_delete_confirm, target.name),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    withContext(Dispatchers.IO) { store.remove(target.id) }
                    reload()
                }
            },
        )
    }
}

/**
 * One host row. Shows only sanitised metadata: name, `user@host:port`, auth type
 * and the first 8 characters of the pinned fingerprint. The secret itself never
 * reaches the UI layer.
 */
@Composable
private fun SshHostRow(
    host: SshHostConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${host.username}@${host.host}:${host.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.ssh_host_pinned) +
                        ": " + host.hostKeyFingerprint.orEmpty().take(8).ifEmpty { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.ssh_host_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    m3Icon(Icons.Default.Delete),
                    contentDescription = stringResource(R.string.common_delete),
                )
            }
        }
    }
}
