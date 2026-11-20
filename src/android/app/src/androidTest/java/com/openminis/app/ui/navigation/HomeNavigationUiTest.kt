package com.openminis.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real Compose navigator: no additional navigation-testing dependency. */
@RunWith(AndroidJUnit4::class)
class HomeNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun phonePaneTransitionsControlChromeWithoutChangingTheOuterRoute() {
        var listVisible by mutableStateOf(true)
        compose.setContent {
            val nav = rememberNavController()
            MaterialTheme {
                HomeNavigationScaffold(nav) { modifier ->
                    NavHost(nav, startDestination = Routes.SESSION_LIST, modifier = modifier) {
                        composable(Routes.SESSION_LIST) { entry ->
                            val report = LocalHomeChatPaneReporter.current
                            SideEffect { report(entry.id, listVisible) }
                            Text("session-content")
                        }
                    }
                }
            }
        }
        compose.onAllNodesWithText(context.getString(R.string.home_tab_tasks)).assertCountEquals(1)
        compose.runOnIdle { listVisible = false }
        compose.waitForIdle()
        compose.onAllNodesWithText(context.getString(R.string.home_tab_tasks)).assertCountEquals(0)
        compose.runOnIdle { listVisible = true }
        compose.waitForIdle()
        compose.onAllNodesWithText(context.getString(R.string.home_tab_tasks)).assertCountEquals(1)
    }

    @Test
    fun coldChatInnerListCreatesHomeAnchorAndRestoresTabsWithoutBlankBack() {
        var listVisible by mutableStateOf(false)
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            MaterialTheme {
                HomeNavigationScaffold(nav) { modifier ->
                    NavHost(nav, startDestination = Routes.chat("quick"), modifier = modifier) {
                        composable(Routes.CHAT, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { entry ->
                            val report = LocalHomeChatPaneReporter.current
                            SideEffect { report(entry.id, listVisible) }
                            Text("quick-chat-content")
                        }
                        composable(Routes.SESSION_LIST) { Text("sessions-content") }
                        composable(Routes.SETTINGS) { Text("settings-content") }
                        composable(Routes.TOOLBOX) { Text("tools-content") }
                        composable(Routes.SCHEDULED_TASKS) {
                            var count by rememberSaveable { mutableIntStateOf(0) }
                            Column {
                                Text("task-state:$count")
                                Button(onClick = { count++ }) { Text("increment-task-view") }
                            }
                        }
                    }
                }
            }
        }
        compose.onAllNodesWithText(context.getString(R.string.home_tab_tasks)).assertCountEquals(0)
        compose.runOnIdle { listVisible = true }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.home_tab_tasks)).performClick()
        compose.runOnIdle {
            assertEquals(Routes.SCHEDULED_TASKS, nav.currentDestination?.route)
            assertEquals(Routes.SESSION_LIST, nav.getBackStackEntry(Routes.SESSION_LIST).destination.route)
        }
        compose.onNodeWithText("increment-task-view").performClick()
        compose.onNodeWithText(context.getString(R.string.home_tab_me)).performClick()
        compose.onNodeWithText(context.getString(R.string.home_tab_tasks)).performClick()
        compose.onAllNodesWithText("task-state:1").assertCountEquals(1)
        compose.runOnIdle { assertTrue(nav.safePopBackStack()) }
        compose.runOnIdle { assertEquals(Routes.SESSION_LIST, nav.currentDestination?.route) }
        compose.onAllNodesWithText("sessions-content").assertCountEquals(1)
    }
}
