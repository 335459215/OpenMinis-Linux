package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.security.SessionPermissionMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatComposerInsertMenuUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everyInsertionActionDismissesBeforeDispatchAndRunsOnce() {
        val events = mutableListOf<String>()
        compose.setContent {
            var expanded by remember { mutableStateOf(false) }
            MaterialTheme {
                Button(onClick = { expanded = true }) { Text("open-insert") }
                ChatComposerInsertMenu(
                    expanded = expanded,
                    onDismiss = { expanded = false; events += "dismiss" },
                    onTakePhoto = { events += "camera" },
                    onChooseMedia = { events += "media" },
                    onAddFile = { events += "file" },
                    onExpandEditor = { events += "editor" },
                    onSlashCommands = { events += "commands" },
                    onVoiceLanguage = { events += "language" },
                )
            }
        }
        val actions = listOf(
            R.string.chat_attach_take_photo to "camera",
            R.string.chat_attach_choose_photos_videos to "media",
            R.string.chat_attach_add_file to "file",
            R.string.composer_expand_editor to "editor",
            R.string.composer_insert_commands to "commands",
            R.string.speech_lang_picker_title to "language",
        )
        actions.forEach { (label, action) ->
            compose.runOnIdle { events.clear() }
            compose.onNodeWithText("open-insert").performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(label)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onAllNodesWithText(context.getString(label)).assertCountEquals(0)
            compose.runOnIdle { assertEquals(listOf("dismiss", action), events) }
        }
        compose.runOnIdle { events.clear() }
        compose.onNodeWithText("open-insert").performClick()
        compose.onNodeWithText(context.getString(R.string.chat_attach_take_photo)).assertIsDisplayed()
        compose.waitForIdle()
        Thread.sleep(350)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.waitUntil(2_000) { events.contains("dismiss") }
        compose.runOnIdle { assertEquals(listOf("dismiss"), events) }
    }

    @Test fun compactControlsKeepFullTouchTargetsAndPermissionTapDoesNotChangeMode() {
        var permissionClicks = 0
        var insertClicks = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    InputCircleButton(onClick = { insertClicks++ }) { Text("insert-touch") }
                    ChatPermissionIndicator(SessionPermissionMode.YOLO) { permissionClicks++ }
                }
            }
        }
        // The text child is narrower than its 48dp clickable parent; click the
        // merged subtree to exercise the real touch target.
        compose.onNodeWithText("insert-touch").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_menu_perm_yoyo))
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(1, insertClicks)
            assertEquals(1, permissionClicks)
        }
    }
}
