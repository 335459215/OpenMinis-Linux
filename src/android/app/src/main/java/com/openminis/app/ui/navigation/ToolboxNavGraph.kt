package com.openminis.app.ui.navigation

import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.openminis.app.navigation.Routes
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.ui.sandbox.FileBrowserScreen
import com.openminis.app.ui.sandbox.FileBrowserViewModel

internal fun NavGraphBuilder.toolboxRoutes(navController: NavHostController) {
    composable(Routes.TOOLBOX) {
        HomeToolboxScreen(onNavigate = { navController.safeNavigate(it) })
    }
    // Unlike FILE_BROWSER this route needs no process-static handover. Its VM
    // belongs to the back-stack entry and is recreated safely after process death.
    composable(Routes.TOOLBOX_FILES) {
        val context = LocalContext.current.applicationContext
        val rootfs = RootfsManager.getInstance(context)
        val browser: FileBrowserViewModel = viewModel(factory = viewModelFactory {
            initializer {
                FileBrowserViewModel(
                    rootPath = rootfs.rootfsDir,
                    rootLabel = "/",
                    linuxRootPath = "/",
                    appContext = context,
                )
            }
        })
        FileBrowserScreen(
            viewModel = browser,
            onBack = { navController.safePopBackStack() },
            onPreviewFile = { item ->
                FilePreviewHolder.currentItem = item
                navController.safeNavigate(Routes.filePreview(item.file.absolutePath))
            },
        )
    }
}
