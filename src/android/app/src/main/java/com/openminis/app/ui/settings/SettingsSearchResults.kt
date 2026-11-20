package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/** A term can match a category, feature name or its hidden explanation. */
internal fun matchesSettingsSearch(query: String, vararg fields: String): Boolean {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return terms.all { term -> fields.any { it.contains(term, ignoreCase = true) } }
}

@Composable
internal fun SettingsSearchResults(
    query: String,
    onActivate: (SettingsDestination) -> Unit,
    onAppearanceClick: () -> Unit,
) {
    var resultCount = 0
    val appearanceTitle = stringResource(R.string.settings_section_appearance)
    val appearanceHelp = stringResource(R.string.settings_appearance_subtitle)
    if (matchesSettingsSearch(query, appearanceTitle, appearanceHelp)) {
        resultCount++
        SettingsSection {
            SettingsRow(
                title = appearanceTitle,
                helpText = appearanceHelp,
                icon = Icons.Outlined.Palette,
                onClick = onAppearanceClick,
                showDivider = false,
            )
        }
    }
    SettingsCategory.entries.forEach { category ->
        val categoryTitle = stringResource(category.titleRes)
        val matches = category.groups.flatten().filter { destination ->
            matchesSettingsSearch(
                query,
                categoryTitle,
                stringResource(destination.titleRes),
                destination.helpRes?.let { stringResource(it) }.orEmpty(),
                *destination.searchRes.map { stringResource(it) }.toTypedArray(),
            )
        }
        resultCount += matches.size
        if (matches.isNotEmpty()) {
            SettingsSection(header = categoryTitle) {
                matches.forEachIndexed { index, destination ->
                    SettingsRow(
                        title = stringResource(destination.titleRes),
                        helpText = destination.helpRes?.let { stringResource(it) },
                        icon = destination.icon,
                        onClick = { onActivate(destination) },
                        showDivider = index < matches.lastIndex,
                    )
                }
            }
        }
    }
    if (resultCount == 0) {
        Text(
            text = stringResource(R.string.search_no_results, query),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}
