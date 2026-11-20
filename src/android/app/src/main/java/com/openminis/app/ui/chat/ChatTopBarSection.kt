package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.m3Icon
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.openminis.app.data.repository.ProviderRepository

/**
 * ChatScreen top bar TITLE column: session title + model/group/provider rows
 * with the fallback pulse, extracted verbatim (pure move). ViewModel-flow
 * values are re-collected here; ChatScreen-local flags and one-shot actions
 * arrive as parameters.
 */
@Composable
internal fun ChatTopBarTitle(
    viewModel: ChatViewModel,
    showChatTitlePill: Boolean,
    providerRepository: ProviderRepository,
    modelPickerInteraction: MutableInteractionSource,
    onShowModelPicker: () -> Unit,
    onShowThinkingLevelSheet: () -> Unit,
    onEditTitle: () -> Unit,
) {
    // ViewModel-flow values, previously collected at the top of ChatScreen and
    // consumed only here; re-collected so the title subscribes directly.
    val sessionTitle by viewModel.sessionTitle.collectAsStateWithLifecycle()
    val modelName by viewModel.modelName.collectAsStateWithLifecycle()
    val providerName by viewModel.providerName.collectAsStateWithLifecycle()
    val selectedGroupName by viewModel.selectedGroupName.collectAsStateWithLifecycle()
    val selectedGroupId by viewModel.selectedGroupId.collectAsStateWithLifecycle()
    val availableGroups by viewModel.availableGroups.collectAsStateWithLifecycle()
                    // iOS-style centered layout: "Minis Ultra" + group row + provider·model row
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val noFontPad = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                        )
                        // Fallback pulse animation (iOS: 3× red pulse on model switch)
                        val fallbackTrigger by viewModel.fallbackTrigger.collectAsStateWithLifecycle()
                        val fallbackPulseAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
                        LaunchedEffect(fallbackTrigger) {
                            if (fallbackTrigger == 0) return@LaunchedEffect
                            repeat(3) {
                                fallbackPulseAlpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(350))
                                fallbackPulseAlpha.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(350))
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Red.copy(alpha = 0.35f * fallbackPulseAlpha.value))
                                // [T-android-topbar-shrink] vertical 4dp→2dp.
                                // Combined with the expandedHeight drop below,
                                // closes the dead-space gap between the model
                                // name row and the TopAppBar bottom edge that
                                // T-topbar-model-row-clip's 76dp overshoot left
                                // behind. Horizontal 32dp keeps the fallback
                                // pulse highlight comfortably padded around
                                // the longest title.
                                .padding(horizontal = 32.dp, vertical = 2.dp),
                        ) {
                            // Nav title: current session title when one
                            // exists and the toggle is on, else fall back to
                            // the Soul name (matches the input placeholder
                            // "Message <SoulName>"), then to app_name
                            // ("Minis Ultra") as the terminal fallback.
                            // Tap opens the same SessionEditSheet used from
                            // the session list — drafts return null from
                            // loadSessionEntity so the sheet stays closed.
                            // SoulStore.cachedMetadata is the same source the
                            // input placeholder uses (see ~line 3581), so
                            // soul renames in Soul Settings reflect here live.
                            val topBarSoul by com.openminis.app.agent.SoulStore
                                .cachedMetadata.collectAsStateWithLifecycle()
                            val displayTitle = when {
                                showChatTitlePill
                                    && sessionTitle.isNotBlank()
                                    && sessionTitle != "New Chat" -> sessionTitle
                                topBarSoul.name.isNotBlank() -> topBarSoul.name
                                else -> stringResource(R.string.app_name)
                            }
                            Text(
                                text = displayTitle,
                                fontSize = 16.sp,
                                lineHeight = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ChatColors.primaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = noFontPad,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        onEditTitle()
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            // Model picker subtitle: green dot + group +
                            // provider/model. Tap opens the model picker —
                            // separated from the title above so tapping the
                            // title rows opens the rename sheet instead.
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    // [T-android-modelpicker-stuck-ripple] Own the
                                    // interaction source so the press can be
                                    // released explicitly. Opening the picker
                                    // sheet puts a modal window over this row, so
                                    // the pointer's UP never reaches the clickable:
                                    // Compose emits PressInteraction.Press with no
                                    // matching Release and the ripple stays lit
                                    // behind the sheet — still there after the
                                    // sheet closes, reading as a permanent grey
                                    // highlight on the title bar.
                                    .clickable(
                                        interactionSource = modelPickerInteraction,
                                        indication = ripple(),
                                    ) { onShowModelPicker() }
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                // Line 1: group name + dropdown arrow (iOS: "Default ⌄").
                                // [T-usage-topbar-brand-icon] Old green status dot removed
                                // (clashed with picker's provider-brand dots); row hides
                                // entirely when no group is bound.
                                //
                                // T-android-topbar-group-name-fallback:
                                    // _selectedGroupName is empty during the
                                    // brief window before loadSession's group
                                    // resolve runs, or whenever a binding
                                    // resolve fails. Falling straight to the
                                    // "Default" badge string masks the
                                    // active group's real name (e.g. the
                                    // onboarding-created "Default Models" or
                                    // any user-renamed group). Insert a real
                                    // fallback chain: collected VM value →
                                    // active/default group name from the live
                                    // config → terminal badge string. Mirrors
                                    // the #476 TopBar title fallback pattern
                                    // (commit b4c88775).
                                    //
                                    // [T-android-group-resolve-skip-uncredentialed]
                                    // ...but only while a group is ACTUALLY
                                    // bound. This chain used to run
                                    // unconditionally, so a session that failed
                                    // to resolve its group — and was therefore
                                    // running on a model from the new-chat
                                    // default chain, unrelated to any group —
                                    // still displayed the default group's name.
                                    // The header then contradicted the model
                                    // line right below it and made a real
                                    // routing failure read as normal operation,
                                    // which is what made that bug hard to spot.
                                    // Mirrors iOS, which keys the group glyph
                                    // off the binding (`isGroupBound`) rather
                                    // than off a name lookup.
                                    val groupNameDisplay = selectedGroupName.ifEmpty {
                                        if (selectedGroupId == null) {
                                            ""
                                        } else {
                                            val defaultGroupId = providerRepository.defaultPrimaryGroupId
                                            availableGroups.firstOrNull { it.id == defaultGroupId }?.name
                                                ?: stringResource(R.string.model_picker_default_badge)
                                        }
                                    }
                                    // Drop the whole affordance when no group is
                                    // bound — an empty label would still leave
                                    // a dangling chevron pointing at nothing.
                                    if (groupNameDisplay.isNotEmpty()) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        ) {
                                        Text(
                                            text = groupNameDisplay,
                                            fontSize = 12.sp,
                                            lineHeight = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ChatColors.secondaryText,
                                            maxLines = 1,
                                            style = noFontPad,
                                        )
                                        Icon(
                                            m3Icon(Icons.Default.KeyboardArrowDown),
                                            contentDescription = null,
                                            tint = ChatColors.tertiaryText,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        }
                                    }
                                // Layer 3: logo + model + thinking badge, two separate
                                // tappable siblings. [T-android-thinking-badge-navbar]
                                // The badge owns its own clickable, so a tap on it opens
                                // the thinking sheet and never bubbles to the column's
                                // model-picker handler. model text is weight(1f, fill=false)
                                // so it truncates before the badge gets clipped.
                                // [T-topbar-three-tier] Layer 2: provider on its own
                                // line — glued as "天翼云 · glm-5.3" a long provider ate
                                // the width and truncated the model name.
                                if (providerName.isNotEmpty()) {
                                    Text(
                                        text = providerName,
                                        fontSize = 11.sp,
                                        lineHeight = 13.sp,
                                        color = ChatColors.tertiaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = noFontPad,
                                    )
                                }
                                if (providerName.isNotEmpty() || modelName.isNotEmpty()) {
                                    val thinkingLevelBadgeState by viewModel.thinkingLevel.collectAsStateWithLifecycle()
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        // [T-codex-fast-mode] ⚡ badge ahead of the
                                        // resolved model name — small orange circle
                                        // + white bolt, shown only while Fast Mode
                                        // is enabled AND the active model is
                                        // eligible (iOS 9e3c76ef row-3 placement,
                                        // 09944220 9pt sizing).
                                        val fastBadgeEligible by viewModel.showFastModeToggle.collectAsStateWithLifecycle()
                                        val fastBadgeOn by viewModel.fastModeEnabled.collectAsStateWithLifecycle()
                                        if (fastBadgeEligible && fastBadgeOn) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(11.dp)
                                                    .background(Color(0xFFFF9500), CircleShape),
                                            ) {
                                                Icon(
                                                    Icons.Default.Bolt,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(9.dp),
                                                )
                                            }
                                        }
                                // [T-usage-topbar-brand-icon] Vendor logo by model-name
                                // keyword scoring (relay labels carry no brand).
                                val brandSlug = remember(modelName) { ModelBrandResolver.resolve(modelName) }
                                        ModelBrandIcon(slug = brandSlug, sizeDp = 13.dp)
                                        Text(
                                            text = modelName.ifEmpty { providerName },
                                            fontSize = 11.sp,
                                            lineHeight = 13.sp,
                                            color = ChatColors.tertiaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = noFontPad,
                                            // Yield first when space is tight; the
                                            // badge to the right stays intrinsic.
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        // Show the badge whenever thinking is on,
                                        // and ALSO when it's Off but the active
                                        // model supports deep thinking (iOS
                                        // parity, e6bd75efc): the icon + "Off"
                                        // pill is then a discoverable tap target
                                        // for enabling thinking via the level
                                        // sheet. The Off pill is gated on
                                        // currentModelSupportsReasoning so
                                        // non-reasoning models don't grow a dead
                                        // toggle; an enabled level still shows
                                        // unconditionally (user may have opted in
                                        // on an unknown-capability model).
                                        if (viewModel.availableThinkingLevels.collectAsStateWithLifecycle().value.isNotEmpty() &&
                                            (
                                                thinkingLevelBadgeState.isEnabled ||
                                                    viewModel.currentModelSupportsReasoning
                                            )
                                        ) {
                                            ThinkingLevelBadge(
                                                level = thinkingLevelBadgeState,
                                                onClick = { onShowThinkingLevelSheet() },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
}
