package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.autogroup.AutoGroupInput
import com.openminis.app.autogroup.AutoGroupPlan
import com.openminis.app.autogroup.AutoGroupPlanner
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.repository.ProviderRepository

/**
 * [T-android-auto-group] Entry point for one-tap model grouping.
 *
 * The whole feature lives in this file so that `ModelGroupsScreen.kt` only
 * grows by a single call line — that screen sits under the architecture
 * line-count ratchet and must not creep upward.
 *
 * Grouping itself is delegated to [AutoGroupPlanner], a pure Kotlin component
 * with no Android dependency, so the bucketing rules stay unit-testable.
 */
@Composable
fun AutoGroupEntryButton(
    providerRepository: ProviderRepository,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    // [T-android-auto-group] Icon-only, deliberately identical to the session
    // list's "magic" button: same Icons.Outlined.AutoAwesome, same 24.dp, same
    // IconButton in the top-bar actions slot. Both are one-tap bulk actions, so
    // they should read as the same affordance instead of one being a text
    // button. The label moves to contentDescription — an icon-only control has
    // no visible text left to name it for TalkBack.
    IconButton(onClick = { showDialog = true }, modifier = modifier) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = stringResource(R.string.auto_group_models),
            modifier = Modifier.size(24.dp),
        )
    }
    if (showDialog) {
        AutoGroupModelsDialog(
            providerRepository = providerRepository,
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * Preview dialog for one-tap grouping.
 *
 * Shows exactly which groups will be produced before anything is written, then
 * offers two ways to apply them: merge into same-named groups (deduplicated),
 * or rebuild — drop every group carrying [AutoGroupPlanner.AUTO_PREFIX] and
 * recreate them. Hand-built groups are never touched, because the prefix is
 * only ever applied by this feature.
 */
@Composable
fun AutoGroupModelsDialog(
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
) {
    val config by providerRepository.config.collectAsStateWithLifecycle()
    val mergePlans = remember(config.modelEntries, config.instances, config.modelGroups) {
        planFor(config, rebuild = false)
    }
    val rebuildPlans = remember(config.modelEntries, config.instances) {
        planFor(config, rebuild = true)
    }
    val autoGroupCount = remember(config.modelGroups) {
        config.modelGroups.count { it.name.startsWith(AutoGroupPlanner.AUTO_PREFIX) }
    }
    var lastResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_group_confirm_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.auto_group_confirm_body))
                Spacer(Modifier.height(12.dp))
                if (mergePlans.isEmpty()) {
                    Text(
                        text = stringResource(R.string.auto_group_empty_toast),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    mergePlans.forEach { plan ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = plan.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${plan.entryIds.size}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.auto_group_plan_summary, mergePlans.size),
                    style = MaterialTheme.typography.bodySmall,
                )
                lastResult?.let { (groups, entries) ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.auto_group_done_toast, groups, entries),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = mergePlans.isNotEmpty(),
                onClick = {
                    lastResult = apply(providerRepository, mergePlans, rebuild = false)
                },
            ) { Text(stringResource(R.string.auto_group_merge_existing)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = rebuildPlans.isNotEmpty(),
                    onClick = {
                        lastResult = apply(providerRepository, rebuildPlans, rebuild = true)
                    },
                ) { Text(stringResource(R.string.auto_group_rebuild)) }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}

/** Maps persisted config into planner input and runs one planning pass. */
private fun planFor(config: ProviderConfig, rebuild: Boolean): List<AutoGroupPlan> {
    val inputs = config.modelEntries.map { entry ->
        val instance = config.instances.firstOrNull { it.id == entry.providerInstanceId }
        AutoGroupInput(
            entryId = entry.id,
            modelId = entry.model.id,
            displayName = entry.model.displayName,
            providerInstanceId = entry.providerInstanceId,
            providerLabel = instance?.label.orEmpty(),
            isHidden = entry.isHidden,
        )
    }
    val existingNames = if (rebuild) emptyList() else config.modelGroups.map { it.name }
    return AutoGroupPlanner.plan(inputs, rebuild, existingNames)
}

/**
 * Writes the plans to the provider config.
 *
 * @return `groups created/updated` to `entries grouped`, for the inline result
 *         line. Rebuild mode deletes prefixed groups first, so hand-made groups
 *         survive untouched.
 */
private fun apply(
    providerRepository: ProviderRepository,
    plans: List<AutoGroupPlan>,
    rebuild: Boolean,
): Pair<Int, Int> {
    val current = providerRepository.config.value
    if (rebuild) {
        current.modelGroups
            .filter { it.name.startsWith(AutoGroupPlanner.AUTO_PREFIX) }
            .forEach { providerRepository.removeGroup(it.id) }
    }
    var grouped = 0
    plans.forEach { plan ->
        val existing = if (rebuild) null else current.modelGroups.firstOrNull { it.name == plan.name }
        if (existing != null) {
            val merged = (existing.memberEntryIds + plan.entryIds).distinct()
            if (merged.size != existing.memberEntryIds.size) {
                existing.memberEntryIds.clear()
                existing.memberEntryIds.addAll(merged)
                providerRepository.updateGroup(existing)
            }
        } else {
            providerRepository.addGroup(
                ModelGroup(
                    name = plan.name,
                    memberEntryIds = plan.entryIds.toMutableList(),
                ),
            )
        }
        grouped += plan.entryIds.size
    }
    return plans.size to grouped
}
