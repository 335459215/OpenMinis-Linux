package com.openminis.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.navigation.Routes
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection

/** Global tools use the same real destinations as settings and chat shortcuts. */
@Composable
fun HomeToolboxScreen(onNavigate: (String) -> Unit) {
    SettingsScaffold(
        title = stringResource(R.string.settings_section_tools),
        navigation = {},
        largeTitle = true,
    ) {
        SettingsSection(header = stringResource(R.string.settings_section_tools)) {
            SettingsRow(
                title = stringResource(R.string.sessionlist_shell_terminal),
                icon = Icons.Outlined.Terminal,
                onClick = { onNavigate(Routes.terminal()) },
            )
            SettingsRow(
                title = stringResource(R.string.browser_title),
                icon = Icons.Outlined.Language,
                onClick = { onNavigate(Routes.HOME_BROWSER) },
            )
            SettingsRow(
                title = stringResource(R.string.filebrowser_title),
                icon = Icons.Outlined.FolderOpen,
                onClick = { onNavigate(Routes.TOOLBOX_FILES) },
                showDivider = false,
            )
                SettingsRow(
                    title = stringResource(R.string.ssh_hosts_title),
                    icon = Icons.Outlined.Dns,
                    helpText = stringResource(R.string.settings_ssh_subtitle),
                    onClick = { onNavigate(Routes.SSH_HOSTS) },
                )
        }
        SettingsSection(header = stringResource(R.string.settings_skills)) {
            SettingsRow(
                title = stringResource(R.string.settings_skills),
                icon = Icons.Outlined.Extension,
                helpText = stringResource(R.string.settings_skills_subtitle),
                onClick = { onNavigate(Routes.SKILLS) },
            )
            SettingsRow(
                title = stringResource(R.string.settings_mcp),
                icon = Icons.Outlined.Dashboard,
                helpText = stringResource(R.string.settings_mcp_subtitle),
                onClick = { onNavigate(Routes.MCP) },
            )
            SettingsRow(
                title = stringResource(R.string.plugin_market_title),
                icon = Icons.Outlined.Storefront,
                helpText = stringResource(R.string.plugin_market_subtitle),
                onClick = { onNavigate(Routes.PLUGIN_MARKET) },
                showDivider = false,
            )
        }
        SettingsSection(header = stringResource(R.string.settings_web_search)) {
            SettingsRow(
                title = stringResource(R.string.settings_web_search),
                icon = Icons.Outlined.Search,
                helpText = stringResource(R.string.settings_web_search_subtitle),
                onClick = { onNavigate(Routes.WEB_SEARCH) },
            )
            SettingsRow(
                title = stringResource(R.string.settings_translate),
                icon = Icons.Outlined.Translate,
                helpText = stringResource(R.string.settings_translate_subtitle),
                onClick = { onNavigate(Routes.TRANSLATE_PAD) },
                showDivider = false,
            )
                SettingsRow(
                    title = stringResource(R.string.home_tab_tasks),
                    icon = Icons.Outlined.Schedule,
                    onClick = { onNavigate(Routes.SCHEDULED_TASKS) },
                    showDivider = false,
                )
        }
    }
}
