package com.openminis.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.scheduled.ScheduledTaskStore
import kotlinx.coroutines.flow.map
import androidx.navigation.compose.currentBackStackEntryAsState
import com.openminis.app.R
import com.openminis.app.navigation.Routes

/** Exact roots only: chat, editors and details retain the full screen. */
internal enum class HomeTab(val route: String, val titleRes: Int) {
    CHATS(Routes.SESSION_LIST, R.string.home_tab_chats),
    TOOLS(Routes.TOOLBOX, R.string.settings_section_tools),
    TASKS(Routes.SCHEDULED_TASKS, R.string.home_tab_tasks),
    ME(Routes.SETTINGS, R.string.home_tab_me);

    companion object {
        fun fromRoute(route: String?, chatListVisible: Boolean? = null): HomeTab? = when {
            route == Routes.SESSION_LIST && chatListVisible == false -> null
            route == Routes.CHAT && chatListVisible == true -> CHATS
            else -> entries.firstOrNull { it.route == route }
        }
    }
}

/** Pane selection lives inside CHAT/SESSION_LIST, independently of the outer route. */
internal val LocalHomeChatPaneReporter = staticCompositionLocalOf<(String, Boolean) -> Unit> {
    { _, _ -> }
}

internal fun homeChatListVisible(twoPane: Boolean, detailSelected: Boolean): Boolean =
    twoPane || !detailSelected

/** Observing route changes here keeps tab chrome independent of high-frequency app state. */
@Composable
internal fun HomeNavigationScaffold(
    navController: NavHostController,
    content: @Composable (Modifier) -> Unit,
) {
    val entry by navController.currentBackStackEntryAsState()
    val entryId = entry?.id
    var chatListVisible by remember(entryId) { mutableStateOf<Boolean?>(null) }
    val reportPane = remember(entryId) {
        { sourceId: String, visible: Boolean ->
            // NavHost composes outgoing and incoming entries during transitions.
            // A pane belonging to the outgoing route cannot change current chrome.
            if (sourceId == entryId) chatListVisible = visible
        }
    }
    val activeTab = HomeTab.fromRoute(entry?.destination?.route, chatListVisible)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 840.dp
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (activeTab != null && !useRail) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shadowElevation = 6.dp,
                        modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        modifier = Modifier.height(64.dp),
                    ) {
                        HomeTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = tab == activeTab,
                                onClick = { navController.selectHomeTab(tab, activeTab) },
                                icon = { HomeTabIcon(tab) },
                                label = { HomeTabLabel(tab) },
                            )
                        }
                    }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (activeTab != null && useRail) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        HomeTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = tab == activeTab,
                                onClick = { navController.selectHomeTab(tab, activeTab) },
                                icon = { HomeTabIcon(tab) },
                                label = { HomeTabLabel(tab) },
                            )
                        }
                    }
                }
                // Keep the host at the same composition position when root chrome changes.
                Box(Modifier.weight(1f).fillMaxSize()) {
                    CompositionLocalProvider(LocalHomeChatPaneReporter provides reportPane) {
                        content(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

private fun NavHostController.selectHomeTab(tab: HomeTab, activeTab: HomeTab) {
    if (tab == activeTab) return
    if (currentBackStackEntry?.lifecycle?.currentState != Lifecycle.State.RESUMED) return
    val hasSessionsRoot = runCatching { getBackStackEntry(Routes.SESSION_LIST) }.isSuccess
    if (!hasSessionsRoot) {
        // A quick-action graph can start at CHAT. Establish a real Chats anchor
        // before another tab, so Back never pops the only content destination.
        // This is one owned transition guarded once above; a second safeNavigate
        // would be dropped because the intermediate Sessions entry is STARTED.
        navigate(Routes.SESSION_LIST) {
            popUpTo(graph.id) { saveState = true }
            launchSingleTop = true
        }
        if (tab == HomeTab.CHATS) return
    }
    navigate(tab.route) {
        popUpTo(Routes.SESSION_LIST) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun HomeTabIcon(tab: HomeTab) {
    val icon: ImageVector = when (tab) {
        HomeTab.CHATS -> Icons.AutoMirrored.Outlined.Chat
        HomeTab.TOOLS -> Icons.Outlined.Dashboard
        HomeTab.TASKS -> Icons.Outlined.TaskAlt
        HomeTab.ME -> Icons.Outlined.PersonOutline
    }
    // The adjacent label supplies the semantics, avoiding duplicate TalkBack announcements.
    if (tab == HomeTab.TASKS) {
        val context = LocalContext.current.applicationContext
        val taskCount by remember(context) {
            ScheduledTaskStore(context).observe().map { tasks -> tasks.count { it.enabled } }
        }.collectAsStateWithLifecycle(initialValue = 0)
        BadgedBox(badge = {
            if (taskCount > 0) Badge { Text(taskCount.toString(), fontSize = 9.sp) }
        }) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp))
        }
    } else {
        Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun HomeTabLabel(tab: HomeTab) {
    Text(
        text = stringResource(tab.titleRes),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
