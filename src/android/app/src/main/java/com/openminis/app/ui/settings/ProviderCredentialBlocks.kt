package com.openminis.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisSmallButton
import com.openminis.app.ui.components.MinisSmallOutlinedButton
import com.openminis.app.ui.components.MinisSmallTextButton
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.ui.theme.IosGreen
import com.openminis.app.ui.theme.m3Icon
import com.openminis.app.ui.util.bringIntoViewOnFocus

private const val TAG = "ProviderCredential"

// ─── Credential blocks ─────────────────────────────────────────────────────────

@Composable
internal fun ApiKeyCredentialBlock(
    storedKey: String,
    keyVisible: Boolean,
    onToggleVisibility: () -> Unit,
    isEditing: Boolean,
    editValue: String,
    onEditValueChange: (String) -> Unit,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
) {
    if (isEditing) {
        SectionTextField(
            value = editValue,
            onValueChange = onEditValueChange,
            singleLine = true,
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        if (keyVisible) m3Icon(m3Icon(Icons.Default.VisibilityOff)) else m3Icon(m3Icon(Icons.Default.Visibility)),
                        contentDescription = if (keyVisible) stringResource(R.string.a11y_hide) else stringResource(R.string.a11y_show),
                    )
                }
            },
            fieldModifier = Modifier.bringIntoViewOnFocus(),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            // [T-android-settings-ui-md3] #4 + #12 Cancel is the SECONDARY action:
            // a neutral outlined pill (onSurfaceVariant content/border), forming
            // the standard MD3 outlined-vs-filled pair with the filled Save below.
            // Previously a primary-teal text button — indistinguishable from Save.
            MinisSmallOutlinedButton(
                onClick = onCancelEdit,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            // [T-key-clear-entry] Enabled when the edit text is non-blank
            // (save) OR a stored key exists (clear with blank text).
            MinisSmallButton(onClick = onSave, enabled = editValue.isNotBlank() || storedKey.isNotBlank()) {
                Text(
                    stringResource(
                        if (editValue.isBlank() && storedKey.isNotBlank()) R.string.common_delete
                        else R.string.provider_detail_save_key
                    )
                )
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (keyVisible) storedKey else maskedKey(storedKey),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    if (keyVisible) m3Icon(m3Icon(Icons.Default.VisibilityOff)) else m3Icon(m3Icon(Icons.Default.Visibility)),
                    contentDescription = if (keyVisible) stringResource(R.string.a11y_hide) else stringResource(R.string.a11y_show),
                )
            }
            MinisSmallTextButton(onClick = onBeginEdit) {
                Text(stringResource(R.string.common_edit))
            }
        }
    }
}

internal fun maskedKey(key: String): String {
    if (key.length <= 10) return key.replace(Regex("."), "•")
    return key.take(6) + "..." + key.takeLast(4)
}

/**
 * Manual Bearer Token section for OAuth providers. Mirrors iOS
 * `manualOAuthTokenSection` — when set, the static token bypasses the OAuth
 * refresh flow entirely and is sent verbatim as `Authorization: Bearer …`.
 */
@Composable
internal fun ManualBearerTokenSection(
    instance: com.openminis.app.data.model.ProviderInstance,
    context: android.content.Context,
) {
    val manager = remember(instance.id) {
        com.openminis.app.auth.OAuthManager.forInstance(context, instance)
    }
    var reloadTick by remember(instance.id) { mutableStateOf(0) }
    val stored = remember(instance.id, reloadTick) { manager?.loadManualBearerToken() }
    val hasToken = !stored.isNullOrEmpty()

    var isEditing by remember(instance.id) { mutableStateOf(false) }
    var draft by remember(instance.id) { mutableStateOf("") }

    if (isEditing) {
        SectionTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = false,
            placeholder = stringResource(R.string.provider_detail_paste_bearer_token),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            fieldModifier = Modifier.bringIntoViewOnFocus(),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            // [T-android-settings-ui-md3] #4 + #12 neutral outlined Cancel (see
            // the API-key edit pair above) paired with the filled Save.
            MinisSmallOutlinedButton(
                onClick = {
                    isEditing = false
                    draft = ""
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            MinisSmallButton(
                onClick = {
                    val cleaned = draft.replace(Regex("\\s+"), "")
                    if (cleaned.isNotEmpty() && manager != null) {
                        manager.saveManualBearerToken(cleaned)
                        AppLogger.info(TAG, "Manual bearer token set for ${instance.id}")
                        draft = ""
                        isEditing = false
                        reloadTick++
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Text(stringResource(R.string.provider_detail_save_token))
            }
        }
        return
    }

    if (hasToken) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.provider_oauth_token_configured),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(8.dp)
                    .background(IosGreen, CircleShape),
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            MinisSmallOutlinedButton(onClick = {
                draft = ""
                isEditing = true
            }) {
                Text(stringResource(R.string.common_change))
            }
            Spacer(modifier = Modifier.width(8.dp))
            MinisSmallOutlinedButton(
                onClick = {
                    manager?.deleteManualBearerToken()
                    AppLogger.info(TAG, "Manual bearer token removed for ${instance.id}")
                    reloadTick++
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.common_remove))
            }
        }
    } else {
        MinisSmallOutlinedButton(onClick = {
            draft = ""
            isEditing = true
        }) {
            Text(stringResource(R.string.provider_detail_set_manual_bearer_token))
        }
    }
}
