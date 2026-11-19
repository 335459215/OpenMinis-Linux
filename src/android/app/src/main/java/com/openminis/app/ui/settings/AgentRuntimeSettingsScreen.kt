package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.AutoCompactPrefs
import com.openminis.app.data.RetryPrefs
import com.openminis.app.ui.components.MinisCenterTopBar
import com.openminis.app.ui.theme.IosTeal
import com.openminis.app.ui.theme.m3Icon

/**
 * Advanced Agent runtime knobs moved off the settings root so compaction and
 * retry tuning no longer competes with the ordinary feature entry points.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = { Text(stringResource(R.string.settings_section_agent_runtime)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            m3Icon(Icons.AutoMirrored.Filled.ArrowBack),
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(header = stringResource(R.string.settings_compact_threshold)) {
                SettingsRow(
                    icon = Icons.Outlined.Tune,
                    iconColor = IosTeal,
                    title = stringResource(R.string.settings_compact_threshold),
                    subtitle = stringResource(R.string.settings_compact_threshold_subtitle),
                    trailing = { Text("$compactThreshold%") },
                    onClick = { showCompactThresholdDialog = true },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_auto_new_session),
                    subtitle = stringResource(R.string.settings_auto_new_session_subtitle),
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
                        subtitle = stringResource(R.string.settings_auto_new_session_threshold_subtitle),
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
                    subtitle = stringResource(
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
    }

    if (showCompactThresholdDialog) {
        CompactThresholdDialog(
            value = compactThreshold,
            onValueChange = { compactThreshold = it },
            onDismiss = { showCompactThresholdDialog = false },
            onConfirm = {
                AutoCompactPrefs.setThresholdPercent(context, compactThreshold)
                showCompactThresholdDialog = false
            },
        )
    }
    if (showAutoNewSessionDialog) {
        AutoNewSessionThresholdDialog(
            value = autoNewSessionThreshold,
            onValueChange = { autoNewSessionThreshold = it },
            onDismiss = { showAutoNewSessionDialog = false },
            onConfirm = {
                AutoCompactPrefs.setAutoNewSessionThreshold(context, autoNewSessionThreshold)
                showAutoNewSessionDialog = false
            },
        )
    }
    if (showRetryAttemptsDialog) {
        RetryAttemptsDialog(
            value = retryAttempts,
            onValueChange = { retryAttempts = it },
            onDismiss = { showRetryAttemptsDialog = false },
            onConfirm = {
                RetryPrefs.setMaxAttempts(context, retryAttempts)
                showRetryAttemptsDialog = false
            },
        )
    }
    if (showRetryIntervalDialog) {
        RetryIntervalDialog(
            value = retryIntervalSec,
            onValueChange = { retryIntervalSec = it },
            onDismiss = { showRetryIntervalDialog = false },
            onConfirm = {
                RetryPrefs.setIntervalSec(context, retryIntervalSec)
                showRetryIntervalDialog = false
            },
        )
    }
}
