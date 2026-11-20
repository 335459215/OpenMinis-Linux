package com.openminis.app.ui.navigation

import com.openminis.app.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTabTest {
    @Test
    fun homeChromeIsAvailableOnTheFourRootScreens() {
        assertEquals(HomeTab.CHATS, HomeTab.fromRoute(Routes.SESSION_LIST))
        assertEquals(HomeTab.TOOLS, HomeTab.fromRoute(Routes.TOOLBOX))
        assertEquals(HomeTab.TASKS, HomeTab.fromRoute(Routes.SCHEDULED_TASKS))
        assertEquals(HomeTab.ME, HomeTab.fromRoute(Routes.SETTINGS))
    }

    @Test
    fun chatAndDetailsNeverInheritATabFromAPrefixMatch() {
        listOf(null, Routes.CHAT, Routes.chat("one"), Routes.SETTINGS_CATEGORY,
            Routes.settingsCategory("models"), Routes.AGENT_RUNTIME,
            Routes.SCHEDULED_TASK_EDIT, Routes.SCHEDULED_TASK_RUNS,
            Routes.terminal()).forEach { assertNull(HomeTab.fromRoute(it)) }
    }

    @Test
    fun phoneSelectionHidesTabsEvenWhenTheOuterRouteStaysSessions() {
        assertEquals(HomeTab.CHATS, HomeTab.fromRoute(Routes.SESSION_LIST, chatListVisible = true))
        assertNull(HomeTab.fromRoute(Routes.SESSION_LIST, chatListVisible = false))
        assertEquals(HomeTab.CHATS, HomeTab.fromRoute(Routes.SESSION_LIST, chatListVisible = true))
    }

    @Test
    fun quickActionChatCanNavigateBackToTheInnerListAndShowAllTabs() {
        assertNull(HomeTab.fromRoute(Routes.CHAT, chatListVisible = false))
        assertEquals(HomeTab.CHATS, HomeTab.fromRoute(Routes.CHAT, chatListVisible = true))
        assertNull(HomeTab.fromRoute(Routes.CHAT, chatListVisible = false))
        assertNull(HomeTab.fromRoute(Routes.AGENT_RUNTIME, chatListVisible = true))
    }

    @Test
    fun innerPaneVisibilityKeepsTabletNavigationWhilePhonesUseFullScreenChats() {
        assertTrue(homeChatListVisible(twoPane = false, detailSelected = false))
        assertFalse(homeChatListVisible(twoPane = false, detailSelected = true))
        assertTrue(homeChatListVisible(twoPane = true, detailSelected = false))
        assertTrue(homeChatListVisible(twoPane = true, detailSelected = true))
    }
}
