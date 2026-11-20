package com.openminis.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.a2a.A2aSettingsActivity
import com.openminis.app.ui.components.openExternalUrl

/** Category navigation preserves the existing leaf screens and their state/storage. */
@Composable
internal fun SettingsCategoryScreen(
    category: SettingsCategory,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val activate = settingsFeatureAction(onNavigate)
    SettingsScaffold(title = stringResource(category.titleRes), onBack = onBack) {
        category.groups.forEach { group ->
            SettingsSection {
                group.forEachIndexed { index, destination ->
                    SettingsRow(
                        title = stringResource(destination.titleRes),
                        helpText = destination.helpRes?.let { stringResource(it) },
                        icon = destination.icon,
                        showDivider = index < group.lastIndex,
                        onClick = { activate(destination) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Both category pages and search results dispatch through this one action owner. */
@Composable
internal fun settingsFeatureAction(onNavigate: (String) -> Unit): (SettingsDestination) -> Unit {
    val context = LocalContext.current
    var showSshWorkspace by rememberSaveable { mutableStateOf(false) }
    var showFeedback by rememberSaveable { mutableStateOf(false) }
    if (showSshWorkspace) SshHostsManagerSheet(onDismiss = { showSshWorkspace = false })
    if (showFeedback) SettingsFeedbackSheet(onDismiss = { showFeedback = false })
    return { destination ->
        when (destination) {
            SettingsDestination.A2A -> context.startActivity(Intent(context, A2aSettingsActivity::class.java))
            SettingsDestination.SSH_WORKSPACE -> showSshWorkspace = true
            SettingsDestination.PRIVACY_POLICY -> openExternalUrl(context, "https://openminis.github.io/privacy-policy.html")
            SettingsDestination.FEEDBACK -> showFeedback = true
            else -> destination.route?.let(onNavigate)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsFeedbackSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            SettingsRow(
                title = stringResource(R.string.settings_submit_github_issues),
                icon = Icons.Outlined.BugReport,
                onClick = { onDismiss(); openExternalUrl(context, buildBugReportUrl()) },
                showChevron = false,
                showDivider = false,
            )
            SettingsRow(
                title = stringResource(R.string.settings_feedback_telegram),
                icon = Icons.AutoMirrored.Outlined.Send,
                onClick = { onDismiss(); openExternalUrl(context, "https://t.me/+2NzhOJuzRyI1YmM1") },
                showChevron = false,
                showDivider = false,
            )
            SettingsRow(
                title = stringResource(R.string.settings_feedback_email),
                icon = Icons.Outlined.Email,
                onClick = { onDismiss(); openExternalUrl(context, buildFeedbackMailto()) },
                showChevron = false,
                showDivider = false,
            )
        }
    }
}

internal val SettingsCategory.icon: ImageVector
    get() = when (this) {
        SettingsCategory.MODELS -> Icons.Outlined.Dns
        SettingsCategory.AGENT -> Icons.Outlined.Tune
        SettingsCategory.TOOLS -> Icons.Outlined.Dashboard
        SettingsCategory.CONNECTIONS -> Icons.Outlined.Wifi
        SettingsCategory.SECURITY -> Icons.Outlined.Shield
        SettingsCategory.DATA -> Icons.Outlined.Inventory2
        SettingsCategory.SYSTEM -> Icons.Outlined.Settings
    }

internal val SettingsDestination.icon: ImageVector
    get() = when (this) {
        SettingsDestination.PROVIDERS -> Icons.Outlined.Lock
        SettingsDestination.CLIENT_DISGUISE, SettingsDestination.PERMISSIONS -> Icons.Outlined.Shield
        SettingsDestination.MODEL_GROUPS, SettingsDestination.BROWSER_SETTINGS -> Icons.Outlined.Settings
        SettingsDestination.USAGE -> Icons.Outlined.BarChart
        SettingsDestination.GATEWAY, SettingsDestination.SSH_WORKSPACE -> Icons.Outlined.Dns
        SettingsDestination.A2A, SettingsDestination.RUNTIME -> Icons.Outlined.Tune
        SettingsDestination.MULTI_AGENT -> Icons.Outlined.Groups
        SettingsDestination.WEB_SEARCH -> Icons.Outlined.TravelExplore
        SettingsDestination.LIFECYCLE_HOOKS -> Icons.Outlined.Build
        SettingsDestination.TRANSLATION -> Icons.Outlined.Translate
        SettingsDestination.SOUL -> Icons.Outlined.AutoAwesome
        SettingsDestination.MEMORY -> Icons.Outlined.Psychology
        SettingsDestination.TERMINAL, SettingsDestination.ENV_VARS, SettingsDestination.SSH_HOSTS -> Icons.Outlined.Terminal
        SettingsDestination.BROWSER -> Icons.Outlined.Language
        SettingsDestination.SKILLS, SettingsDestination.PLUGINS -> Icons.Outlined.Extension
        SettingsDestination.MCP -> Icons.Outlined.Dashboard
        SettingsDestination.NETWORK -> Icons.Outlined.Wifi
        SettingsDestination.BACKGROUND -> Icons.Outlined.BatteryFull
        SettingsDestination.PRIVACY_POLICY -> Icons.Outlined.FrontHand
        SettingsDestination.STORAGE -> Icons.Outlined.Inventory2
        SettingsDestination.SHARED_FOLDERS -> Icons.Outlined.Folder
        SettingsDestination.MOUNTED_FOLDERS -> Icons.Outlined.FolderShared
        SettingsDestination.BACKUP -> Icons.Outlined.Backup
        SettingsDestination.LOGS -> Icons.Outlined.Description
        SettingsDestination.ABOUT -> Icons.Outlined.Info
        SettingsDestination.FEEDBACK -> Icons.Outlined.Feedback
    }
