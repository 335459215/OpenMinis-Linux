package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.ChatSendModePrefs
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuSectionHeader

/** Visible choices replace the old implicit cycle, while using the same persisted preference. */
@Composable
internal fun ChatSendModeControl() {
    val context = LocalContext.current
    val mode by ChatSendModePrefs.modeFlow.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    val current = ChatSendChoice.entries.firstOrNull { it.value == mode } ?: ChatSendChoice.QUEUE
    Box {
        IconButton(onClick = { expanded = !expanded }) {
            Icon(
                imageVector = current.icon,
                contentDescription = "${stringResource(R.string.chat_send_mode)}: ${stringResource(current.titleRes)}",
                modifier = Modifier.size(18.dp),
            )
        }
        MinisMenu(expanded = expanded, onDismissRequest = { expanded = false }, alignEnd = true, border = null) {
            MinisMenuSectionHeader(stringResource(R.string.chat_send_mode))
            ChatSendChoice.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(stringResource(choice.titleRes), fontSize = 13.sp) },
                    leadingIcon = { Icon(choice.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (choice == current) Icon(Icons.Outlined.Check, stringResource(R.string.cd_selected), Modifier.size(18.dp))
                    },
                    onClick = {
                        ChatSendModePrefs.setMode(context, choice.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

private enum class ChatSendChoice(val value: String, val titleRes: Int) {
    QUEUE(ChatSendModePrefs.MODE_QUEUE, R.string.chat_send_queue),
    INTERRUPT(ChatSendModePrefs.MODE_INTERRUPT, R.string.chat_send_interrupt),
    AUTO(ChatSendModePrefs.MODE_AUTO, R.string.chat_send_auto);
}

private val ChatSendChoice.icon: ImageVector
    get() = when (this) {
        ChatSendChoice.QUEUE -> Icons.AutoMirrored.Outlined.PlaylistAdd
        ChatSendChoice.INTERRUPT -> Icons.Outlined.Bolt
        ChatSendChoice.AUTO -> Icons.Outlined.AutoAwesome
    }
