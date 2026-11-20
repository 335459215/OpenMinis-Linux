package com.openminis.app.ui.settings

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Run with the opt-in Compose test host; no sandbox or model connection needed. */
class SettingsHelpUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun helpIsOnDemandAndDoesNotNavigateOrToggleTheRow() {
        var navigations = 0
        var toggles = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    SettingsRow(
                        title = "Connection",
                        subtitle = "Connected",
                        helpText = "Configure this model connection.",
                        onClick = { navigations++ },
                    )
                    SettingsSwitchRow(
                        title = "Retry",
                        checked = true,
                        onCheckedChange = { toggles++ },
                        helpText = "Retry interrupted requests.",
                    )
                }
            }
        }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onAllNodesWithText("Configure this model connection.").assertCountEquals(0)
        compose.onNodeWithContentDescription(helpLabel("Connection"))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithText("Configure this model connection.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, navigations) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onAllNodesWithText("Configure this model connection.").assertCountEquals(0)
        compose.onNodeWithContentDescription(helpLabel("Retry")).performClick()
        compose.onNodeWithText("Retry interrupted requests.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, toggles) }
    }

    @Test
    fun tappingOutsideClosesHelpWithoutActivatingTheRow() {
        var navigations = 0
        compose.setContent {
            MaterialTheme {
                SettingsRow(
                    title = "Connection",
                    helpText = "Configure this connection.",
                    onClick = { navigations++ },
                )
            }
        }
        compose.onNodeWithContentDescription(helpLabel("Connection")).performClick()
        compose.onNodeWithText("Configure this connection.").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                metrics.widthPixels / 2f, metrics.heightPixels * 0.8f, 0,
            )
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        compose.onAllNodesWithText("Configure this connection.").assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, navigations) }
    }

    @Test
    fun scaffoldUsesTheHostBackDispatcherByDefault() {
        var backCalls = 0
        compose.setContent {
            MaterialTheme {
                BackHandler { backCalls++ }
                SettingsScaffold(title = "Settings") {}
            }
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.cd_back)
        compose.onNodeWithContentDescription(label).performClick()
        compose.runOnIdle { assertEquals(1, backCalls) }
    }

    @Test
    fun editNavigationReplacesTheDefaultBackButton() {
        compose.setContent {
            MaterialTheme {
                SettingsScaffold(title = "Edit", navigation = { Text("Cancel") }) {}
            }
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.cd_back)
        compose.onAllNodesWithContentDescription(label).assertCountEquals(0)
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun valueRowsKeepTheirCurrentValueVisible() {
        compose.setContent {
            MaterialTheme {
                SettingsValueRow(
                    title = "Threshold",
                    value = "85%",
                    helpText = "Start compaction at this threshold.",
                )
            }
        }
        compose.onNodeWithText("85%", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Start compaction at this threshold.").assertCountEquals(0)
        compose.onNodeWithContentDescription(helpLabel("Threshold")).performClick()
        compose.onNodeWithText("Start compaction at this threshold.").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("85%", substring = true).assertIsDisplayed()
    }

    private fun helpLabel(title: String): String = InstrumentationRegistry.getInstrumentation()
        .targetContext.getString(R.string.settings_help_for, title)
}
