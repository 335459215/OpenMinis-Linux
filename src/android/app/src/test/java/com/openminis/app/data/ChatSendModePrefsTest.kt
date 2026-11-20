package com.openminis.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatSendModePrefsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("minis_chat_send_mode", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        ChatSendModePrefs.prime(context)
    }

    @After fun cleanup() = reset()

    @Test fun primeNormalizesUnknownStoredModeAndKeepsGetterAndFlowConsistent() {
        prefs.edit().putString("sendMode", "removed-mode").commit()
        ChatSendModePrefs.prime(context)
        assertEquals(ChatSendModePrefs.MODE_QUEUE, ChatSendModePrefs.mode())
        assertEquals(ChatSendModePrefs.MODE_QUEUE, ChatSendModePrefs.modeFlow.value)
        prefs.edit().putString("sendMode", ChatSendModePrefs.MODE_AUTO).commit()
        ChatSendModePrefs.prime(context)
        assertEquals(ChatSendModePrefs.MODE_AUTO, ChatSendModePrefs.modeFlow.value)
    }

    @Test fun setModePersistsNormalizedValueAndPublishesEachActualChangeOnce() = runTest {
        val observed = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            ChatSendModePrefs.modeFlow.collect { observed += it }
        }
        ChatSendModePrefs.setMode(context, ChatSendModePrefs.MODE_INTERRUPT)
        testScheduler.runCurrent()
        ChatSendModePrefs.setMode(context, ChatSendModePrefs.MODE_INTERRUPT)
        testScheduler.runCurrent()
        ChatSendModePrefs.setMode(context, "unknown")
        testScheduler.runCurrent()
        assertEquals(listOf(ChatSendModePrefs.MODE_QUEUE, ChatSendModePrefs.MODE_INTERRUPT, ChatSendModePrefs.MODE_QUEUE), observed)
        assertEquals(ChatSendModePrefs.MODE_QUEUE, prefs.getString("sendMode", null))
        assertEquals(ChatSendModePrefs.mode(), ChatSendModePrefs.modeFlow.value)
        collector.cancel()
    }
}
