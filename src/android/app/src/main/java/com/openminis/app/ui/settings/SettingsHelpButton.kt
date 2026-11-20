package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.R
import com.openminis.app.ui.components.SectionDesign

/** Small contextual help with a full touch target; never triggers its parent row. */
@Composable
internal fun SettingsHelpButton(title: String, helpText: String) {
    var expanded by remember(title, helpText) { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.size(SectionDesign.HelpTouchTargetSize),
        ) {
            Icon(
                imageVector = Icons.Outlined.HelpOutline,
                contentDescription = stringResource(R.string.settings_help_for, title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SectionDesign.HelpIconSize),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 192.dp, max = 288.dp).heightIn(max = 320.dp),
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
        ) {
            Text(
                text = helpText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
