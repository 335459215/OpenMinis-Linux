package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.data.ChatSendModePrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Two mounted chats observe the same persisted send-mode owner. */
class ChatSendModeUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var originalMode: String

    @Before fun reset() {
        ChatSendModePrefs.prime(context)
        originalMode = ChatSendModePrefs.mode()
        ChatSendModePrefs.setMode(context, ChatSendModePrefs.MODE_QUEUE)
    }

    @After fun restore() {
        ChatSendModePrefs.setMode(context, originalMode)
    }

    private fun modeLabel(labelRes: Int) =
        "${context.getString(R.string.chat_send_mode)}: ${context.getString(labelRes)}"

    @Test fun explicitChoiceUpdatesThePreferenceAndEveryMountedModeIcon() {
        compose.setContent { MaterialTheme { Row { ChatSendModeControl(); ChatSendModeControl() } } }
        compose.onAllNodesWithContentDescription(modeLabel(R.string.chat_send_queue)).assertCountEquals(2)
        compose.onAllNodesWithContentDescription(modeLabel(R.string.chat_send_queue))[0].performClick()
        compose.onNodeWithText(context.getString(R.string.chat_send_interrupt)).performClick()
        compose.onAllNodesWithContentDescription(modeLabel(R.string.chat_send_interrupt)).assertCountEquals(2)
        compose.runOnIdle {
            assertEquals(ChatSendModePrefs.MODE_INTERRUPT, ChatSendModePrefs.mode())
            assertEquals(ChatSendModePrefs.MODE_INTERRUPT, context.getSharedPreferences("minis_chat_send_mode", 0).getString("sendMode", null))
            ChatSendModePrefs.setMode(context, ChatSendModePrefs.MODE_AUTO)
        }
        compose.onAllNodesWithContentDescription(modeLabel(R.string.chat_send_auto)).assertCountEquals(2)
        compose.onAllNodesWithContentDescription(modeLabel(R.string.chat_send_auto))[1].performClick()
        compose.onNodeWithText(context.getString(R.string.chat_send_queue)).performClick()
        compose.onAllNodesWithContentDescription(modeLabel(R.string.chat_send_queue)).assertCountEquals(2)
    }
}
