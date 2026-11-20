package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/** A compact index. Detailed features belong to their own category and back stack. */
@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onCategoryClick: (SettingsCategory) -> Unit,
    onAppearanceClick: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val activate = settingsFeatureAction(onNavigate)
    // The root is a bottom-tab destination; its back affordance would duplicate
    // the tab bar and push the V9 large title down. System back still handles
    // deep-link callers; category/detail pages keep their explicit back arrow.
    SettingsScaffold(title = stringResource(R.string.settings_title), navigation = {}, largeTitle = true) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { androidx.compose.material3.Text(stringResource(R.string.sessionlist_search_action)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.sessionlist_search_action)) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.browser_settings_clear))
                }
            },
            shape = RoundedCornerShape(20.dp),
            singleLine = true,
        )
        if (query.isNotBlank()) {
            SettingsSearchResults(query = query, onActivate = activate, onAppearanceClick = onAppearanceClick)
            Spacer(Modifier.height(24.dp))
            return@SettingsScaffold
        }
        SettingsSection {
            CategoryRow(SettingsCategory.MODELS, onCategoryClick)
            SettingsRow(
                title = stringResource(R.string.settings_section_appearance),
                helpText = stringResource(R.string.settings_appearance_subtitle),
                icon = Icons.Outlined.Palette,
                onClick = onAppearanceClick,
                showDivider = false,
            )
        }
        SettingsSection {
            CategoryRow(SettingsCategory.AGENT, onCategoryClick)
            CategoryRow(SettingsCategory.TOOLS, onCategoryClick)
            CategoryRow(SettingsCategory.CONNECTIONS, onCategoryClick, showDivider = false)
        }
        SettingsSection {
            CategoryRow(SettingsCategory.SECURITY, onCategoryClick)
            CategoryRow(SettingsCategory.DATA, onCategoryClick)
            CategoryRow(SettingsCategory.SYSTEM, onCategoryClick, showDivider = false)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CategoryRow(
    category: SettingsCategory,
    onCategoryClick: (SettingsCategory) -> Unit,
    showDivider: Boolean = true,
) {
    // Localized feature names form a useful index inside help without filling the home.
    val summary = category.groups.flatten().map { stringResource(it.titleRes) }.joinToString(" · ")
    SettingsRow(
        title = stringResource(category.titleRes),
        helpText = summary,
        icon = category.icon,
        onClick = { onCategoryClick(category) },
        showDivider = showDivider,
    )
}
