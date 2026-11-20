package com.openminis.app.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.security.SessionPermissionMode
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.ui.theme.m3Icon
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.data.repository.MCPRepository

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * ChatScreen top bar ACTIONS: quick-action icons + the "..." overflow menu,
 * grouped into compact pages. ChatScreen still owns visibility and refreshes
 * restorableCount when the menu opens; action handlers retain their original state owners.
 */
@Composable
internal fun ChatTopBarActions(
    viewModel: ChatViewModel,
    memoryRepository: MemoryRepository?,
    skillRepository: SkillRepository?,
    mcpRepository: MCPRepository?,
    restorableCount: Int,
    showChatMenu: MutableState<Boolean>,
    onShowRestoreDialog: () -> Unit,
    onShowSkillsSheet: () -> Unit,
    onShowMcpsSheet: () -> Unit,
    onShowTokenUsageSheet: () -> Unit,
    onShowEnhancedCacheDialog: () -> Unit,
    onOpenTerminal: () -> Unit,
    onBrowseChatFiles: () -> Unit,
    onOpenGitPanel: () -> Unit,
) {
    // [P0-6] High-frequency entries promoted out of the "..."
    // menu into direct icons when the bar has room (≥600dp —
    // below that the centered two-line title needs the width;
    // the menu's 快捷 section carries them on phones). Same
    // handlers as the menu items — one funnel per action.
    if (shouldShowTopBarQuickActions()) {
        IconButton(onClick = onOpenTerminal) {
            Icon(m3Icon(Icons.Default.Terminal), contentDescription = stringResource(R.string.chat_menu_open_terminal))
        }
        IconButton(onClick = { viewModel.toggleBrowserSheet() }) {
            Icon(m3Icon(Icons.Default.Language), contentDescription = stringResource(R.string.chat_menu_open_browser))
        }
        IconButton(onClick = onBrowseChatFiles) {
            Icon(m3Icon(Icons.Default.Description), contentDescription = stringResource(R.string.chat_menu_browse_chat_files))
        }
    }
    var page by remember { mutableStateOf(ChatActionPage.ROOT) }
    val currentPermission by viewModel.permissionMode.collectAsStateWithLifecycle()
    ChatPermissionIndicator(currentPermission) {
        showChatMenu.value = true
        page = ChatActionPage.SESSION
    }
    ChatSendModeControl()
    // iOS: "..." circle button → dropdown menu
    Box {
        IconButton(onClick = { page = ChatActionPage.ROOT; showChatMenu.value = true }) {
            Icon(
                Icons.Outlined.MoreHoriz,
                contentDescription = stringResource(R.string.cd_more),
            )
        }
        MinisMenu(
            expanded = showChatMenu.value,
            alignEnd = true,
            minWidth = 216.dp,
            border = null,
            scrollState = remember(page) { ScrollState(0) },
            onDismissRequest = { showChatMenu.value = false },
        ) {
            // [T-android-memory-enabled-minisconfig] Gate the
            // "Memories in Session" item below on the session's
            // live memoryEnabled — when memory is off the entry
            // disappears, consistent with the per-session gating
            // of the memory_get / memory_write tools and the
            // system-prompt injection.
            val menuMemoryEnabled by viewModel.memoryEnabled.collectAsStateWithLifecycle()
            val sessionPerm by viewModel.permissionMode.collectAsStateWithLifecycle()
            if (page == ChatActionPage.ROOT) {
                if (!shouldShowTopBarQuickActions()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { showChatMenu.value = false; onOpenTerminal() }) {
                            Icon(m3Icon(Icons.Default.Terminal), stringResource(R.string.chat_menu_open_terminal), Modifier.size(18.dp))
                        }
                        IconButton(onClick = { showChatMenu.value = false; viewModel.toggleBrowserSheet() }) {
                            Icon(m3Icon(Icons.Default.Language), stringResource(R.string.chat_menu_open_browser), Modifier.size(18.dp))
                        }
                        IconButton(onClick = { showChatMenu.value = false; onBrowseChatFiles() }) {
                            Icon(m3Icon(Icons.Default.Description), stringResource(R.string.chat_menu_browse_chat_files), Modifier.size(18.dp))
                        }
                    }
                    MinisMenuDivider()
                }
                ChatMenuCategory(R.string.chat_menu_section_session, Icons.Default.Forum) { page = ChatActionPage.SESSION }
                ChatMenuCategory(R.string.chat_menu_section_tools, Icons.Default.Build) { page = ChatActionPage.TOOLS }
                ChatMenuCategory(R.string.chat_menu_section_settings, Icons.Default.Compress) { page = ChatActionPage.SETTINGS }
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(page.titleRes), fontSize = 13.sp) },
                    onClick = { page = ChatActionPage.ROOT },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), modifier = Modifier.size(18.dp)) },
                )
                MinisMenuDivider()
            }
            if (page == ChatActionPage.SESSION) {
                // [P0-6] 会话段：本会话的行为开关。
                // [#31] Four-tier tool gate. The row shows the
                // current tier and cycles on tap:
                // ASK → SAFE_AUTO → YOLO → OFF → ASK.
                DropdownMenuItem(
                    text = {
                        Text(
                            when (sessionPerm) {
                                SessionPermissionMode.OFF -> stringResource(R.string.chat_menu_perm_off)
                                SessionPermissionMode.ASK -> stringResource(R.string.chat_menu_perm_ask)
                                SessionPermissionMode.SAFE_AUTO -> stringResource(R.string.chat_menu_perm_safe_auto)
                                SessionPermissionMode.YOLO -> stringResource(R.string.chat_menu_perm_yoyo)
                            }
                        )
                    },
                    onClick = { viewModel.setSessionPermissionMode(sessionPerm.next()) },
                    leadingIcon = {
                        Icon(
                            if (sessionPerm.isYolo()) Icons.Filled.Bolt else Icons.Filled.Shield,
                            contentDescription = null,
                        )
                    },
                    // "Cycles on tap" must be visible or the row
                    // reads as a static status line.
                    trailingIcon = {
                        Icon(
                            m3Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                val groupChatOn by viewModel.groupChatEnabled.collectAsStateWithLifecycle()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_group_chat), fontSize = 13.sp) },
                    onClick = { viewModel.setGroupChatEnabled(!groupChatOn) },
                    leadingIcon = { Icon(Icons.Default.Forum, contentDescription = null) },
                    trailingIcon = {
                        // The row already toggles. A live switch here
                        // also consumes the tap and flips the flag
                        // back off, so @ keeps opening skill files.
                        SettingsSwitch(
                            checked = groupChatOn,
                            onCheckedChange = null,
                        )
                    },
                )
                // [P0-6] 结束讨论 lives ONLY on the group-chat
                // banner (same endGroupChat(), always visible
                // while a group chat runs, one tap away). The
                // duplicate overflow-menu entry was two taps to
                // reach the same call and only ever enabled in
                // the exact state where the banner is on screen.
                if (restorableCount > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_restore_deleted), fontSize = 13.sp) },
                        onClick = {
                            showChatMenu.value = false
                            onShowRestoreDialog()
                        },
                        leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
                    )
            }
            }
            if (page == ChatActionPage.TOOLS) {
                // [P0-6] 工具段：会话内的扩展面。
                // [P2-git-panel] Git panel — desktop-style git UI
                // over the session workspace.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_git_panel), fontSize = 13.sp) },
                    onClick = {
                        showChatMenu.value = false
                        onOpenGitPanel()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.MergeType, contentDescription = null)
                    },
                )
                // Session Skills (iOS parity)
                if (skillRepository != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.session_skills_title), fontSize = 13.sp) },
                        onClick = {
                            showChatMenu.value = false
                            onShowSkillsSheet()
                        },
                        leadingIcon = {
                            Icon(m3Icon(Icons.Default.Build), contentDescription = null)
                        },
                    )
            }
            // [T-mcp-integration-android] MCPs in Session, next to Skills.
            if (mcpRepository != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.session_mcps_title), fontSize = 13.sp) },
                    onClick = {
                        showChatMenu.value = false
                        onShowMcpsSheet()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Extension, contentDescription = null)
                    },
                )
            }
            // [T-ssh-workspace] (#32) Bind this session to an
            // SSH host: shell tools then run remotely. The
            // sheet's visibility rides the process-level
            // SshWorkspaceSheetState (ContextUsageSheetState
            // pattern) so no new callback crosses ChatScreen.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ssh_workspace_menu_title), fontSize = 13.sp) },
                onClick = {
                    showChatMenu.value = false
                    SshWorkspaceSheetState.show()
                },
                leadingIcon = {
                    Icon(m3Icon(Icons.Default.Dns), contentDescription = null)
                },
            )
            // Session Memory (iOS parity)
            if (memoryRepository != null && menuMemoryEnabled) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.session_memory_title), fontSize = 13.sp) },
                    onClick = {
                        showChatMenu.value = false
                        viewModel.toggleMemorySheet()
                    },
                    leadingIcon = {
                        Icon(m3Icon(Icons.Default.Psychology), contentDescription = null)
                    },
                )
            }
            }
            if (page == ChatActionPage.SETTINGS) {
                // [P0-6] 设置段：模型调用与会话开销类开关。增强缓存/
                // 极速模式保留在此而非移入系统设置页：它们的可见性
                // 由当前会话的模型/提供方决定（showFastModeToggle、
                // showEnhancedCacheToggle），设置页没有会话上下文。
                // Token Usage (iOS parity). [#25] The context
                // breakdown (per-block composition of the next
                // request) now lives INSIDE this sheet's Context
                // section — one entry point, one sheet, no
                // duplicated header stats (see TokenUsageSheet).
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_token_usage), fontSize = 13.sp) },
                    onClick = {
                        showChatMenu.value = false
                        onShowTokenUsageSheet()
                    },
                    leadingIcon = {
                        val contextUsage by viewModel.contextUsage.collectAsStateWithLifecycle()
                        ContextRing(usage = contextUsage)
                    },
                )
                // Enhanced Cache (iOS parity, commit 57aaf122):
                // 1-hour Anthropic cache TTL. Only shown for the
                // official Anthropic API (not relays / other
                // providers) — showEnhancedCacheToggle recomputes on
                // model/provider switch. First enable prompts a
                // one-time extra-billing confirmation.
                val showEnhancedCache by viewModel.showEnhancedCacheToggle.collectAsStateWithLifecycle()
                val enhancedCacheOn by viewModel.enhancedCacheEnabled.collectAsStateWithLifecycle()
                if (showEnhancedCache) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_enhanced_cache), fontSize = 13.sp) },
                        onClick = {
                            if (enhancedCacheOn) {
                                viewModel.setEnhancedCacheEnabled(false)
                            } else if (viewModel.isEnhancedCacheConfirmed()) {
                                viewModel.setEnhancedCacheEnabled(true)
                            } else {
                                showChatMenu.value = false
                                onShowEnhancedCacheDialog()
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Bolt, contentDescription = null)
                        },
                        trailingIcon = {
                            SettingsSwitch(
                                checked = enhancedCacheOn,
                                onCheckedChange = null,
                            )
                        },
                    )
            }
            // [T-codex-fast-mode] Fast Mode (iOS parity,
            // fb671083 + 838ba929): shown when the active model
            // is a gpt-family model served through the
            // Responses path (useResponsesAPI instance or Codex
            // OAuth). App-level persisted toggle; while on, the
            // Responses body carries service_tier="priority"
            // (≈1.5x faster at 2x credit burn on the ChatGPT
            // subscription) and the nav model row shows a ⚡
            // badge. Sits next to Enhanced Cache — both are
            // model-invocation controls (iOS 09944220 grouping).
            val showFastMode by viewModel.showFastModeToggle.collectAsStateWithLifecycle()
            val fastModeOn by viewModel.fastModeEnabled.collectAsStateWithLifecycle()
            if (showFastMode) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_fast_mode), fontSize = 13.sp) },
                    onClick = { viewModel.setFastModeEnabled(!fastModeOn) },
                    leadingIcon = {
                        Icon(Icons.Default.Bolt, contentDescription = null)
                    },
                    trailingIcon = {
                        SettingsSwitch(
                            checked = fastModeOn,
                            onCheckedChange = null,
                        )
                    },
                )
            }
            // Auto Compact: app-level persisted toggle mirroring
            // iOS `autoCompactEnabled`. On → crossing the
            // compact threshold before a send compacts silently
            // and sends; off → the user is asked first. Lives
            // beside Fast Mode because both are model-invocation
            // controls the user flips mid-conversation.
            val autoCompactOn by viewModel.autoCompactEnabled.collectAsStateWithLifecycle()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_menu_auto_compact), fontSize = 13.sp) },
                onClick = { viewModel.setAutoCompactEnabled(!autoCompactOn) },
                leadingIcon = {
                    Icon(Icons.Default.Compress, contentDescription = null)
                },
                trailingIcon = {
                    SettingsSwitch(
                        checked = autoCompactOn,
                        onCheckedChange = null,
                    )
                },
            )
            // T287: debug-only crash trigger so the user can verify
            // ACRA/native crash log generation (T283). Throws a
            // RuntimeException from the click handler — the
            // uncaught-exception handler catches it and writes
            // a crash-<stamp>.log under filesDir/logs/.
            if (BuildConfig.DEBUG) {
                MinisMenuDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.debug_trigger_crash_menu),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        showChatMenu.value = false
                        throw RuntimeException(
                            "Debug crash triggered by user (T287)",
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
            }
        }
    }
}

private enum class ChatActionPage(val titleRes: Int) {
    ROOT(R.string.cd_more),
    SESSION(R.string.chat_menu_section_session),
    TOOLS(R.string.chat_menu_section_tools),
    SETTINGS(R.string.chat_menu_section_settings),
}

@Composable
private fun ChatMenuCategory(titleRes: Int, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(titleRes), fontSize = 13.sp) },
        leadingIcon = { Icon(m3Icon(icon), contentDescription = null, modifier = Modifier.size(18.dp)) },
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp)) },
        onClick = onClick,
    )
}
