package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * [T-arch-extract-retry-ui] The two retry dialogs, extracted out of
 * `SettingsScreen.kt` — that file is capped at 800 lines and adding both inline
 * pushed it to 917.
 *
 * Both dialogs edit [com.openminis.app.data.RetryPrefs] through callbacks so the
 * caller keeps ownership of its `remember` state (and of the save semantics:
 * the value is committed on 保存, not on every slider tick).
 */
@Composable
internal fun RetryAttemptsDialog(
    value: Int,
    onValueChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_retry_attempts)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_auto_retry_attempts_dialog_body))
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.settings_auto_retry_attempts_value,
                        value,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = value.toFloat(),
                    onValueChange = {
                        onValueChange(
                            it.toInt().coerceIn(
                                com.openminis.app.data.RetryPrefs.MIN_ATTEMPTS,
                                com.openminis.app.data.RetryPrefs.MAX_ATTEMPTS,
                            ),
                        )
                    },
                    valueRange = com.openminis.app.data.RetryPrefs.MIN_ATTEMPTS
                        .toFloat()..com.openminis.app.data.RetryPrefs.MAX_ATTEMPTS.toFloat(),
                    steps = com.openminis.app.data.RetryPrefs.MAX_ATTEMPTS -
                        com.openminis.app.data.RetryPrefs.MIN_ATTEMPTS - 1,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun RetryIntervalDialog(
    value: Int,
    onValueChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_retry_interval)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_auto_retry_interval_dialog_body))
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.settings_auto_retry_interval_value,
                        value,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = value.toFloat(),
                    onValueChange = {
                        onValueChange(
                            it.toInt().coerceIn(
                                com.openminis.app.data.RetryPrefs.MIN_INTERVAL_SEC,
                                com.openminis.app.data.RetryPrefs.MAX_INTERVAL_SEC,
                            ),
                        )
                    },
                    valueRange = com.openminis.app.data.RetryPrefs.MIN_INTERVAL_SEC
                        .toFloat()..com.openminis.app.data.RetryPrefs.MAX_INTERVAL_SEC.toFloat(),
                    steps = 28,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * [T-arch-extract-retry-ui] Compaction threshold dialog.
 *
 * Moved out of `SettingsScreen.kt` (800-line cap). Same 50–95% range and the
 * same commit-on-save semantics as before.
 */
@Composable
internal fun CompactThresholdDialog(
    value: Int,
    onValueChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_compact_threshold)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_compact_threshold_dialog_body))
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "$value%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = value.toFloat(),
                    onValueChange = { onValueChange(it.toInt().coerceIn(50, 95)) },
                    valueRange = 50f..95f,
                    steps = 44,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * [T-arch-extract-retry-ui] "Compactions before auto-opening a new session"
 * dialog, moved out of `SettingsScreen.kt` for the same line-budget reason.
 */
@Composable
internal fun AutoNewSessionThresholdDialog(
    value: Int,
    onValueChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val min = com.openminis.app.data.AutoCompactPrefs.MIN_AUTO_NEW_SESSION_THRESHOLD
    val max = com.openminis.app.data.AutoCompactPrefs.MAX_AUTO_NEW_SESSION_THRESHOLD
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_new_session_threshold)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_auto_new_session_dialog_body))
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_auto_new_session_times, value),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = value.toFloat(),
                    onValueChange = { onValueChange(it.toInt().coerceIn(min, max)) },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = max - min - 1,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
