package com.openminis.app.ui.ssh

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.openminis.app.R
import com.openminis.app.ssh.SshAuthType
import com.openminis.app.ssh.SshConfigStore
import com.openminis.app.ssh.SshHostConfig

/**
 * [T-android-ssh-settings] Create / edit one SSH host.
 *
 * The secret field accepts either form:
 *  - a literal password or PEM block, or
 *  - a `$$ENV_NAME` (or `env:NAME`) reference to an app environment variable.
 *
 * The reference form is the recommended one and is stated in [R.string.ssh_secret_ref_hint]:
 * the stored value stays a pointer, so the raw secret never enters the host store and never
 * appears in this dialog's state longer than the single save call.
 *
 * @param initial host being edited, or null when creating.
 * @param existingIds ids already in use, so a new id does not collide.
 * @param onSave receives the config plus the raw secret arguments; null means "unchanged"
 *        for an edit (the caller keeps whatever is already stored).
 */
@Composable
fun SshHostEditDialog(
    initial: SshHostConfig?,
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    onSave: (config: SshHostConfig, password: String?, privateKey: String?, passphrase: String?) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var portText by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var authType by remember { mutableStateOf(initial?.authType ?: SshAuthType.PASSWORD) }
    var secret by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    val port = portText.trim().toIntOrNull()
    val valid = name.isNotBlank() && host.isNotBlank() && username.isNotBlank() &&
        port != null && port in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.ssh_host_add else R.string.ssh_host_edit,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ssh_host_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.ssh_host_addr)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.ssh_host_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.ssh_host_user)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = authType == SshAuthType.PASSWORD,
                        onClick = { authType = SshAuthType.PASSWORD },
                        label = { Text(stringResource(R.string.ssh_host_auth_password)) },
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = authType == SshAuthType.PRIVATE_KEY,
                        onClick = { authType = SshAuthType.PRIVATE_KEY },
                        label = { Text(stringResource(R.string.ssh_host_auth_key)) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(stringResource(R.string.ssh_host_secret)) },
                    minLines = if (authType == SshAuthType.PRIVATE_KEY) 3 else 1,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    supportingText = {
                        Text(
                            text = stringResource(R.string.ssh_secret_ref_hint),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                if (authType == SshAuthType.PRIVATE_KEY) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (showError) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "name / host / username / port 1-65535 required",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!valid) {
                        showError = true
                        return@TextButton
                    }
                    val id = initial?.id ?: SshConfigStore.newId(name.trim(), existingIds)
                    val config = SshHostConfig(
                        id = id,
                        name = name.trim(),
                        host = host.trim(),
                        port = port ?: 22,
                        username = username.trim(),
                        authType = authType,
                        hostKeyFingerprint = initial?.hostKeyFingerprint,
                        createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                    )
                    // Blank secret on an edit means "keep what is stored".
                    val blank = secret.isBlank()
                    onSave(
                        config,
                        if (blank || authType != SshAuthType.PASSWORD) null else secret,
                        if (blank || authType != SshAuthType.PRIVATE_KEY) null else secret,
                        passphrase.ifBlank { null },
                    )
                },
            ) { Text(stringResource(R.string.ssh_host_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
