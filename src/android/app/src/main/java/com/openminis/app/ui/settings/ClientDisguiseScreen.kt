package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.ClientDisguisePrefs
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.provider.disguise.DisguiseClient
import com.openminis.app.provider.disguise.DisguiseClients

/**
 * [T-client-disguise-app-level] App 级「客户端伪装」设置屏。
 *
 * 三块，从上到下：
 *
 *  1. 总开关。关掉时下面两块**整块消失**（不是变灰）—— 关掉就等于「什么都没
 *     在伪装」，留着半灰的配置只会让人以为还在生效。
 *  2. 多选客户端抽屉：勾哪些客户端允许被拿来伪装。勾选是「连点」场景，所以
 *     菜单点完**不收起**，用户能一次勾好几个再点外面关掉。
 *  3. 一行两个抽屉：左选客户端、右选渠道。右抽屉的内容完全由左抽屉决定 ——
 *     换左边，右边立刻换成新客户端能绑的渠道，这是用户原话里点名要的。
 *
 * 数据层 [ClientDisguisePrefs] 是 object + 内存缓存（不是 StateFlow），所以
 * 每次写操作后 bump 一次 [revision] 把值重新读进组合；渠道列表则走
 * `providerRepository.config` 的 StateFlow，provider 还没加载完时不会停在空列表。
 */
@Composable
fun ClientDisguiseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val instances = app.providerRepository.config.collectAsStateWithLifecycle().value.instances

    // 写操作后 +1，驱动下面的 remember(revision) 重新求值。
    var revision by remember { mutableStateOf(0) }

    // 启动路径目前还没有人调用 ClientDisguisePrefs.prime()，缓存会是空的（界面
    // 永远显示「关闭」）。这里补一次预热，进屏读到的就是磁盘上的真实配置。
    LaunchedEffect(Unit) {
        ClientDisguisePrefs.prime(context)
        revision++
    }

    val enabled = remember(revision) { ClientDisguisePrefs.isEnabled() }
    val checkedClientIds = remember(revision) { ClientDisguisePrefs.enabledClients() }
    val bindings = remember(revision) { ClientDisguisePrefs.bindings() }

    // 左抽屉只列「已勾选」的客户端，顺序跟随 DisguiseClients.ALL。
    val clientChoices = remember(checkedClientIds) {
        DisguiseClients.ALL.filter { it.id in checkedClientIds }
    }

    // 左抽屉当前选中项。默认落到第一个可用客户端 —— 用户打开这个屏，最想做的
    // 事是「给某个渠道挑身份」，让他先点一次左边纯属多一步。
    var pickedClientId by remember { mutableStateOf<String?>(null) }
    // 选中的客户端如果刚被取消勾选，自动落到下一个仍可用的，右抽屉不会指向一个
    // 已经不在左抽屉里的值。
    val selectedClientId = pickedClientId?.takeIf { id -> clientChoices.any { it.id == id } }
        ?: clientChoices.firstOrNull()?.id
    val selectedClient = DisguiseClients.byId(selectedClientId)

    var clientsExpanded by remember { mutableStateOf(false) }
    var leftExpanded by remember { mutableStateOf(false) }
    var rightExpanded by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.client_disguise_title),
        onBack = onBack,
    ) {
        // ── 1. 总开关 ────────────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.client_disguise_section_master),
            footer = stringResource(R.string.client_disguise_master_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.client_disguise_master_title),
                helpText = stringResource(R.string.client_disguise_master_subtitle),
                checked = enabled,
                onCheckedChange = { on ->
                    ClientDisguisePrefs.setEnabled(context, on)
                    if (!on) {
                        // 关掉就收起所有抽屉，避免残留的展开状态在重新打开时
                        // 弹出一个和当前选择对不上的菜单。
                        clientsExpanded = false
                        leftExpanded = false
                        rightExpanded = false
                    }
                    revision++
                },
                showDivider = false,
            )
        }

        if (!enabled) return@SettingsScaffold

        // ── 2. 多选客户端抽屉 ────────────────────────────────────────────────
        SettingsSection(header = stringResource(R.string.client_disguise_section_clients)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    // 一眼要看到的是「选了几个」，不是列表本身。
                    title = stringResource(
                        R.string.client_disguise_clients_selected_count,
                        clientChoices.size,
                    ),
                    helpText = stringResource(R.string.client_disguise_clients_row_hint),
                    onClick = { clientsExpanded = !clientsExpanded },
                    showChevron = false,
                    showDivider = false,
                    trailing = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
                DropdownMenu(
                    expanded = clientsExpanded,
                    onDismissRequest = { clientsExpanded = false },
                    modifier = Modifier.widthIn(min = 240.dp),
                ) {
                    DisguiseClients.ALL.forEach { client ->
                        val on = client.id in checkedClientIds
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = client.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        text = client.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            onClick = {
                                // 多选：菜单保持展开，可以连着勾几个，点外面再关。
                                ClientDisguisePrefs.toggleClient(context, client.id, !on)
                                revision++
                            },
                            trailingIcon = {
                                if (on) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    // 占位，保证勾与未勾的文字左缘对齐。
                                    Spacer(Modifier.size(24.dp))
                                }
                            },
                        )
                    }
                }
            }
        }

        // ── 3. 一行两个抽屉：左客户端 / 右渠道 ──────────────────────────────
        val channelChoices = remember(selectedClientId, instances, bindings) {
            channelChoicesFor(instances, bindings, selectedClientId)
        }
        val boundHere = remember(channelChoices, selectedClientId) {
            channelChoices.filter { it.ownerClientId == selectedClientId }
        }
        val targetClientId = selectedClientId

        val channelCellValue = when {
            selectedClientId == null ->
                stringResource(R.string.client_disguise_picker_channel_placeholder)
            channelChoices.isEmpty() ->
                stringResource(R.string.client_disguise_channel_empty)
            boundHere.isEmpty() ->
                stringResource(R.string.client_disguise_channel_none_bound)
            boundHere.size == 1 ->
                boundHere.first().instance.label
            else ->
                stringResource(R.string.client_disguise_channel_bound_count, boundHere.size)
        }

        SettingsSection(
            header = stringResource(R.string.client_disguise_section_binding),
            footer = stringResource(R.string.client_disguise_binding_footer),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 左：客户端。只列已勾选的，当前那个打勾。
                DrawerCell(
                    label = stringResource(R.string.client_disguise_picker_client),
                    value = selectedClient?.displayName
                        ?: stringResource(R.string.client_disguise_picker_client_placeholder),
                    expanded = leftExpanded,
                    onExpandedChange = { leftExpanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    if (clientChoices.isEmpty()) {
                        DrawerPlaceholder(stringResource(R.string.client_disguise_clients_none))
                    } else {
                        clientChoices.forEach { client ->
                            val on = client.id == selectedClientId
                            DropdownMenuItem(
                                text = { Text(client.displayName) },
                                onClick = {
                                    // 换客户端 = 右抽屉整张列表跟着换。已经展开的
                                    // 右菜单先收掉，否则它会停在旧客户端的列表上。
                                    pickedClientId = client.id
                                    leftExpanded = false
                                    rightExpanded = false
                                },
                                trailingIcon = {
                                    if (on) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    } else {
                                        Spacer(Modifier.size(24.dp))
                                    }
                                },
                            )
                        }
                    }
                }

                // 右：渠道。没选客户端时不可点，直接给「先选择客户端」。
                DrawerCell(
                    label = stringResource(R.string.client_disguise_picker_channel),
                    value = channelCellValue,
                    expanded = rightExpanded,
                    onExpandedChange = { rightExpanded = it },
                    modifier = Modifier.weight(1f),
                    enabled = selectedClientId != null,
                ) {
                    if (selectedClientId == null) {
                        DrawerPlaceholder(
                            stringResource(R.string.client_disguise_picker_channel_placeholder),
                        )
                    } else if (channelChoices.isEmpty()) {
                        DrawerPlaceholder(stringResource(R.string.client_disguise_channel_empty))
                    } else {
                        channelChoices.forEach { choice ->
                            val owner = choice.ownerClientId
                            val boundToSelected = owner != null && owner == selectedClientId
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = choice.instance.label,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (owner != null && !boundToSelected) {
                                            // 已绑到别的客户端的渠道**显示但不隐藏**：
                                            // 藏起来用户会以为渠道丢了；标清楚占用者，
                                            // 点一下就是改绑（互斥由 map 的 key 保证）。
                                            val ownerLabel =
                                                DisguiseClients.byId(owner)?.displayName ?: owner
                                            Text(
                                                text = stringResource(
                                                    R.string.client_disguise_channel_taken,
                                                    ownerLabel,
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        } else {
                                            Text(
                                                text = choice.instance.providerType.displayName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    if (targetClientId != null) {
                                        // 已绑到该客户端 → 解绑；绑到别人 → 覆盖改绑。
                                        ClientDisguisePrefs.toggleChannelBinding(
                                            context,
                                            choice.instance.id,
                                            targetClientId,
                                        )
                                        revision++
                                        rightExpanded = false
                                    }
                                },
                                trailingIcon = {
                                    if (boundToSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    } else {
                                        Spacer(Modifier.size(24.dp))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── 数据整形 ──────────────────────────────────────────────────────────────────

/** 右抽屉里的一行：渠道实例 + 它当前绑的客户端（null = 没绑）。 */
private data class ChannelChoice(
    val instance: ProviderInstance,
    val ownerClientId: String?,
)

/**
 * 右抽屉的候选渠道。
 *
 * 规则（用户原话：切换左边的客户端，右边的选择框跟随变动）：
 *  - 家族匹配（[familyAllowsChannel]）的渠道都列出来，哪怕还没绑 —— 用户是来
 *    挑渠道的，候选必须完整。
 *  - 已经绑到**当前这个**客户端的渠道一定列，否则家族规则一收紧，之前绑好的
 *    渠道就从列表里消失、再也解绑不了。
 *  - 绑到**别的**客户端、且家族不匹配的渠道不列 —— 它对这个客户端本来就不可用。
 *    同家族里被别人占着的渠道会列出来并标「已被 X 占用」。
 */
private fun channelChoicesFor(
    instances: List<ProviderInstance>,
    bindings: Map<String, String>,
    selectedClientId: String?,
): List<ChannelChoice> {
    if (selectedClientId == null) return emptyList()
    val family = DisguiseClients.familyOf(selectedClientId)
    return instances
        .filter { inst ->
            bindings[inst.id] == selectedClientId ||
                familyAllowsChannel(family, inst.providerType.name)
        }
        .map { ChannelChoice(instance = it, ownerClientId = bindings[it.id]) }
}

/**
 * 客户端家族 ↔ provider 类型。用 `providerType.name` 字符串比较而不是枚举，
 * 这样以后往 [com.openminis.app.data.model.ProviderType] 里加值不会让这里
 * 编译失败（未列出的类型只是不出现，不会崩）。
 *
 * `gemini` / `kimiCode` / 只解码不实现的 `antigravity` / `unsupported` 不在
 * 任何家族里 —— 请求层目前没有对应的伪装实现，列出来等于给用户一个空承诺。
 */
private fun familyAllowsChannel(
    family: DisguiseClient.Family,
    providerTypeName: String,
): Boolean = when (family) {
    DisguiseClient.Family.ANY -> true
    DisguiseClient.Family.ANTHROPIC -> providerTypeName == "anthropic"
    DisguiseClient.Family.OPENAI -> providerTypeName in OPENAI_FAMILY_PROVIDER_TYPES
}

private val OPENAI_FAMILY_PROVIDER_TYPES = setOf(
    "openAI",
    "openAIResponses",
    "openRouter",
    "xAI",
)

// ─── 半宽抽屉 ──────────────────────────────────────────────────────────────────

/**
 * 半宽的可展开格子：上面一行小标题（「客户端」/「渠道」），下面一行当前值 +
 * 下拉箭头。点整格展开 [menuContent]。
 *
 * 不做成 [SettingsRow] 是因为这一块要并排两个，而 SettingsRow 是整行 56dp 的
 * 卡片行，两半各塞一个会把卡片边距和分隔线撑歪。
 */
@Composable
private fun DrawerCell(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    menuContent: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = enabled) { onExpandedChange(!expanded) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.widthIn(min = 200.dp),
        ) {
            menuContent()
        }
    }
}

/** 抽屉里没有可选项时的说明文字（不可点）。 */
@Composable
private fun DrawerPlaceholder(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
