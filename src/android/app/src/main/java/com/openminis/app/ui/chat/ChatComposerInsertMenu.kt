package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsRow

/** Insertion controls share one sheet; the caller keeps picker/permission/editor ownership. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatComposerInsertMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseMedia: () -> Unit,
    onAddFile: () -> Unit,
    onExpandEditor: () -> Unit,
    onSlashCommands: () -> Unit,
    onVoiceLanguage: () -> Unit,
) {
    if (!expanded) return
    fun select(action: () -> Unit) { onDismiss(); action() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SettingsRow(
                title = stringResource(R.string.chat_attach_take_photo),
                icon = Icons.Outlined.CameraAlt,
                onClick = { select(onTakePhoto) },
                showChevron = false,
            )
            SettingsRow(
                title = stringResource(R.string.chat_attach_choose_photos_videos),
                icon = Icons.Outlined.PhotoLibrary,
                onClick = { select(onChooseMedia) },
                showChevron = false,
            )
            SettingsRow(
                title = stringResource(R.string.chat_attach_add_file),
                icon = Icons.Outlined.Description,
                onClick = { select(onAddFile) },
                showChevron = false,
            )
            SettingsRow(
                title = stringResource(R.string.composer_expand_editor),
                icon = Icons.Outlined.OpenInFull,
                onClick = { select(onExpandEditor) },
                showChevron = false,
            )
            SettingsRow(
                title = stringResource(R.string.composer_insert_commands),
                icon = Icons.Outlined.Code,
                onClick = { select(onSlashCommands) },
                showChevron = false,
            )
            SettingsRow(
                title = stringResource(R.string.speech_lang_picker_title),
                icon = Icons.Outlined.Translate,
                onClick = { select(onVoiceLanguage) },
                showChevron = false,
                showDivider = false,
            )
        }
    }
}
