package com.openminis.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.data.AutoCompactPrefs
import com.openminis.app.data.RetryPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real sliders/dialogs: cancellation must leave both the row and prefs unchanged. */
class AgentRuntimeSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var threshold = 75
    private var attempts = 3
    private var retryEnabled = true

    @Before fun rememberPrefs() {
        threshold = AutoCompactPrefs.thresholdPercent()
        attempts = RetryPrefs.maxAttempts()
        retryEnabled = RetryPrefs.isEnabled()
        RetryPrefs.setEnabled(context, true)
        compose.setContent { MaterialTheme { AgentRuntimeSettingsScreen(onBack = {}) } }
    }

    @After fun restorePrefs() {
        AutoCompactPrefs.setThresholdPercent(context, threshold)
        RetryPrefs.setMaxAttempts(context, attempts)
        RetryPrefs.setEnabled(context, retryEnabled)
    }

    @Test fun cancelCompactionEditDiscardsDraft() {
        compose.onNode(hasText(context.getString(R.string.settings_compact_threshold)) and hasClickAction())
            .performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) {
            it(if (threshold == 80) 85f else 80f)
        }
        compose.onNodeWithText(context.getString(R.string.common_cancel)).performClick()
        assertEquals(threshold, AutoCompactPrefs.thresholdPercent())
        compose.onNodeWithText("$threshold%").assertIsDisplayed()
    }

    @Test fun saveRetryEditCommitsAndReopensSavedValue() {
        val title = context.getString(R.string.settings_auto_retry_attempts)
        val changed = if (attempts == 4) 2 else 4
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) {
            it(changed.toFloat())
        }
        compose.onNodeWithText(context.getString(R.string.common_save)).performClick()
        assertEquals(changed, RetryPrefs.maxAttempts())
        val saved = context.getString(R.string.settings_auto_retry_attempts_value, changed)
        compose.onNodeWithText(saved).assertIsDisplayed()
        compose.onNodeWithText(title).performClick()
        compose.onNode(hasText(saved) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }
}
