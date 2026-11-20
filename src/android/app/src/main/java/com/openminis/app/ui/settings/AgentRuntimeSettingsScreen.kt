package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.AutoCompactPrefs
import com.openminis.app.data.RetryPrefs
import com.openminis.app.ui.theme.IosTeal

/**
 * Advanced Agent runtime knobs moved off the settings root so compaction and
 * retry tuning no longer competes with the ordinary feature entry points.
 */
@Composable
fun AgentRuntimeSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var compactThreshold by remember { mutableStateOf(AutoCompactPrefs.thresholdPercent()) }
    var autoNewSessionEnabled by remember {
        mutableStateOf(AutoCompactPrefs.autoNewSessionEnabled())
    }
    var autoNewSessionThreshold by remember {
        mutableStateOf(AutoCompactPrefs.autoNewSessionThreshold())
    }
    var retryEnabled by remember { mutableStateOf(RetryPrefs.isEnabled()) }
    var retryAttempts by remember { mutableStateOf(RetryPrefs.maxAttempts()) }
    var retryIntervalSec by remember { mutableStateOf(RetryPrefs.intervalSec()) }

    var showCompactThresholdDialog by remember { mutableStateOf(false) }
    var showAutoNewSessionDialog by remember { mutableStateOf(false) }
    var showRetryAttemptsDialog by remember { mutableStateOf(false) }
    var showRetryIntervalDialog by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.settings_section_agent_runtime),
        onBack = onBack,
    ) {
        SettingsSection(header = stringResource(R.string.settings_compact_threshold)) {
            SettingsRow(
                icon = Icons.Outlined.Tune,
                iconColor = IosTeal,
                title = stringResource(R.string.settings_compact_threshold),
                helpText = stringResource(R.string.settings_compact_threshold_subtitle),
                trailing = { Text("$compactThreshold%") },
                onClick = { showCompactThresholdDialog = true },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_auto_new_session),
                helpText = stringResource(R.string.settings_auto_new_session_subtitle),
                checked = autoNewSessionEnabled,
                onCheckedChange = { enabled ->
                    autoNewSessionEnabled = enabled
                    AutoCompactPrefs.setAutoNewSessionEnabled(context, enabled)
                },
                icon = Icons.Outlined.Repeat,
                iconColor = IosTeal,
            )
            if (autoNewSessionEnabled) {
                SettingsRow(
                    icon = Icons.Outlined.Repeat,
                    iconColor = IosTeal,
                    title = stringResource(R.string.settings_auto_new_session_threshold),
                    helpText = stringResource(R.string.settings_auto_new_session_threshold_subtitle),
                    trailing = {
                        Text(
                            stringResource(
                                R.string.settings_auto_new_session_times,
                                autoNewSessionThreshold,
                            ),
                        )
                    },
                    onClick = { showAutoNewSessionDialog = true },
                    showDivider = false,
                )
            }
        }

        SettingsSection(header = stringResource(R.string.settings_auto_retry)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_auto_retry),
                helpText = stringResource(
                    if (retryEnabled) R.string.settings_auto_retry_subtitle
                    else R.string.settings_auto_retry_off_subtitle,
                ),
                checked = retryEnabled,
                onCheckedChange = { enabled ->
                    retryEnabled = enabled
                    RetryPrefs.setEnabled(context, enabled)
                },
                icon = Icons.Outlined.Autorenew,
                iconColor = IosTeal,
            )
            if (retryEnabled) {
                SettingsRow(
                    icon = Icons.Outlined.Replay,
                    iconColor = IosTeal,
                    title = stringResource(R.string.settings_auto_retry_attempts),
                    subtitle = null,
                    trailing = {
                        Text(
                            stringResource(
                                R.string.settings_auto_retry_attempts_value,
                                retryAttempts,
                            ),
                        )
                    },
                    onClick = { showRetryAttemptsDialog = true },
                )
                SettingsRow(
                    icon = Icons.Outlined.Timer,
                    iconColor = IosTeal,
                    title = stringResource(R.string.settings_auto_retry_interval),
                    subtitle = null,
                    trailing = {
                        Text(
                            stringResource(
                                R.string.settings_auto_retry_interval_value,
                                retryIntervalSec,
                            ),
                        )
                    },
                    onClick = { showRetryIntervalDialog = true },
                    showDivider = false,
                )
            }
        }
    }

    if (showCompactThresholdDialog) {
        var draft by remember { mutableStateOf(compactThreshold) }
        CompactThresholdDialog(
            value = draft,
            onValueChange = { draft = it },
            onDismiss = { showCompactThresholdDialog = false },
            onConfirm = {
                AutoCompactPrefs.setThresholdPercent(context, draft)
                compactThreshold = draft
                showCompactThresholdDialog = false
            },
        )
    }
    if (showAutoNewSessionDialog) {
        var draft by remember { mutableStateOf(autoNewSessionThreshold) }
        AutoNewSessionThresholdDialog(
            value = draft,
            onValueChange = { draft = it },
            onDismiss = { showAutoNewSessionDialog = false },
            onConfirm = {
                AutoCompactPrefs.setAutoNewSessionThreshold(context, draft)
                autoNewSessionThreshold = draft
                showAutoNewSessionDialog = false
            },
        )
    }
    if (showRetryAttemptsDialog) {
        var draft by remember { mutableStateOf(retryAttempts) }
        RetryAttemptsDialog(
            value = draft,
            onValueChange = { draft = it },
            onDismiss = { showRetryAttemptsDialog = false },
            onConfirm = {
                RetryPrefs.setMaxAttempts(context, draft)
                retryAttempts = draft
                showRetryAttemptsDialog = false
            },
        )
    }
    if (showRetryIntervalDialog) {
        var draft by remember { mutableStateOf(retryIntervalSec) }
        RetryIntervalDialog(
            value = draft,
            onValueChange = { draft = it },
            onDismiss = { showRetryIntervalDialog = false },
            onConfirm = {
                RetryPrefs.setIntervalSec(context, draft)
                retryIntervalSec = draft
                showRetryIntervalDialog = false
            },
        )
    }
}
