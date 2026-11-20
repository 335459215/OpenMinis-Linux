package com.openminis.app.ui.settings

import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.openminis.app.R
import com.openminis.app.ui.navigation.HomeToolboxScreen
import com.openminis.app.ui.navigation.HomeNavigationScaffold
import com.openminis.app.navigation.Routes
import com.openminis.app.ui.theme.MinisTheme
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test

/** Device-rendered evidence from real composables; no model or sandbox is started. */
class SettingsVisualCaptureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureSettingsAndHelpInBothThemesAndLargeFont() {
        val dark = mutableStateOf(false)
        val scale = mutableStateOf(1f)
        compose.setContent {
            CaptureTheme(dark = dark.value, fontScale = scale.value) {
                CaptureHome(Routes.SETTINGS) {
                    SettingsScreen(onBack = {}, onCategoryClick = {}, onAppearanceClick = {}, onNavigate = {})
                }
            }
        }
        compose.onNodeWithText(chineseContext().getString(R.string.settings_title)).assertIsDisplayed()
        capture("settings-light")
        val context = chineseContext()
        val label = context.getString(R.string.settings_help_for, context.getString(R.string.settings_nav_models))
        compose.onNodeWithContentDescription(label).performClick()
        capture("settings-help")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.runOnIdle { dark.value = true }
        capture("settings-dark")
        compose.runOnIdle { scale.value = 1.5f }
        capture("settings-dark-large-font")
    }

    @Test fun captureRuntime() {
        compose.setContent { CaptureTheme { AgentRuntimeSettingsScreen(onBack = {}) } }
        capture("runtime-light")
    }

    @Test fun captureToolbox() {
        compose.setContent { CaptureTheme { CaptureHome(Routes.TOOLBOX) { HomeToolboxScreen(onNavigate = {}) } } }
        capture("toolbox-light")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Compose idleness precedes SurfaceFlinger presentation. Allow the
        // committed frame to reach the display before the device screenshot.
        Thread.sleep(350)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-captures")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun chineseContext(): android.content.Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        return base.createConfigurationContext(config)
    }

    @Composable private fun CaptureTheme(
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        val context = chineseContext()
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides context.resources.configuration,
        ) { MinisTheme(darkTheme = dark, fontScale = fontScale, content = content) }
    }

    @Composable private fun CaptureHome(route: String, content: @Composable () -> Unit) {
        val nav = rememberNavController()
        HomeNavigationScaffold(nav) { modifier ->
            NavHost(nav, startDestination = route, modifier = modifier) {
                composable(route) { content() }
            }
        }
    }
}
