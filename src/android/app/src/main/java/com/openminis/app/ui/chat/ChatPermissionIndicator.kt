package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.security.SessionPermissionMode

/** Current mode stays discoverable while the menu is closed; tapping only opens settings. */
@Composable
internal fun ChatPermissionIndicator(mode: SessionPermissionMode, onClick: () -> Unit) {
    val label = when (mode) {
        SessionPermissionMode.OFF -> R.string.chat_menu_perm_off
        SessionPermissionMode.ASK -> R.string.chat_menu_perm_ask
        SessionPermissionMode.SAFE_AUTO -> R.string.chat_menu_perm_safe_auto
        SessionPermissionMode.YOLO -> R.string.chat_menu_perm_yoyo
    }
    val icon = when (mode) {
        SessionPermissionMode.OFF -> Icons.Filled.Block
        SessionPermissionMode.YOLO -> Icons.Filled.Bolt
        else -> Icons.Filled.Shield
    }
    // Keep the visible glyph compact while preserving the full 48dp touch target
    // required by the phone interaction contract.
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            icon,
            contentDescription = stringResource(label),
            tint = if (mode == SessionPermissionMode.YOLO) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
