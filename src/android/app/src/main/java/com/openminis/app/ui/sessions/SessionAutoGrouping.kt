package com.openminis.app.ui.sessions

import android.util.Log
import com.openminis.app.R
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.*
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.ui.chat.TaskAnchor
import com.openminis.app.ui.sessions.SessionListViewModel.AutoClassifyAssignment
import com.openminis.app.ui.sessions.SessionListViewModel.AutoClassifyPlan
import com.openminis.app.ui.sessions.SessionListViewModel.AutoClassifyState
import com.openminis.app.ui.sessions.SessionListViewModel.GroupSuggestion
import org.json.JSONObject

/**
 * [T-android-group-ai-suggest] / [T-android-group-auto-classify] The
 * sub-model plumbing behind both AI grouping entry points, extracted out of
 * [SessionListViewModel] verbatim to keep the VM under its architecture-policy
 * line ceiling.
 *
 * "AI Suggest" (inside the group picker) and the top-bar "magic" button are
 * the same action with two triggers: hand a batch of session titles to the sub
 * model and demand pure JSON. They therefore share ONE candidate chain and ONE
 * send-with-fallback loop — two parallel implementations would eventually lose
 * an OAuth refresh or a credential rotation on one side, and the only symptom
 * would be "AI stopped working".
 *
 * Everything here is an extension on [SessionListViewModel] rather than free
 * functions: the repository handles, the application context and the model
 * cache all live on the VM, and they are `internal` for exactly this reason.
 */

/** Log prefix — same tag the VM uses, so both halves read as one component. */
private const val TAG = "SessionListVM"

/**
 * [T-android-group-ai-suggest] Verbatim from iOS `suggestFolder`, so
 * both platforms constrain the sub model identically.
 */
private const val GROUP_SUGGEST_SYSTEM_PROMPT =
    "You organize chat sessions into folders. Respond with a single valid JSON object only."

/**
 * [T-android-group-auto-classify] 单次「魔术」最多送去整理多少条会话。
 *
 * 每多一条会话就多一行 prompt + 一条 JSON assignment，输出 token 会线性
 * 增长；40 条刚好放进非推理模型 1200 token 的输出预算。超出的部分不送
 * （列表本来就是 updated_at DESC，先给最近的那批），用户再点一次即可。
 */
private const val AUTO_CLASSIFY_MAX_SESSIONS = 40

/** 首条用户消息摘要的截断长度。只够模型判断主题，不构成「把聊天内容上传」。 */
private const val AUTO_CLASSIFY_SUMMARY_CHARS = 120

/** 组名上限：防止模型把一整句话当组名塞进侧栏卡片。 */
private const val AUTO_CLASSIFY_MAX_FOLDER_NAME = 24

/**
 * [T-android-group-auto-classify] 「魔术」整理的 system prompt。
 *
 * 与 [GROUP_SUGGEST_SYSTEM_PROMPT] 同样只约束「输出形状」；具体字段含义
 * 写在 user prompt 里 —— 两处都能改的话，改一处忘另一处就是必然。
 */
private const val AUTO_CLASSIFY_SYSTEM_PROMPT =
    "You file chat sessions into folders. Respond with a single valid JSON object only."

/**
 * [T-android-group-auto-classify] 「魔术」按钮回包的解析。
 *
 * 与 [SessionListViewModel.parseGroupSuggestion] 一样先按第一个 `{` / 最后一个 `}` 裁掉模型的
 * 废话和 ```json 围栏。三条硬规则，都是为了让「模型胡编」不会被写进数据库：
 *
 * 1. `sessionId` 必须在 [knownSessionIds] 里 —— 模型偶尔会漏字或自造 id，
 *    写一条不存在的 id 等于静默丢一次整理；
 * 2. `folder` 为空 / 全是空白的条目直接丢；
 * 3. `newFolders` 里与现有组同名的条目不算「新建」（大小写不敏感）——
 *    否则每次点魔术都会多出一张同名卡片。
 *
 * @return null = 回包根本不是这个形状（调用方据此重试一次）。形状对但两条
 *   数组都空，返回的是**空计划**而不是 null：那是一个合法的「无事可做」。
 */
internal fun parseAutoClassifyReply(
    text: String,
    knownSessionIds: Set<String>,
    existingFolderNames: Set<String>,
): AutoClassifyPlan? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val json = try {
        JSONObject(text.substring(start, end + 1))
    } catch (_: Exception) {
        return null
    }
    // 两条 key 一个都没有 = 模型答非所问（比如只回了一句"好的"）。
    if (!json.has("assignments") && !json.has("newFolders")) return null

    val newFolders = mutableListOf<GroupSuggestion.Create>()
    val declared = mutableSetOf<String>()
    json.optJSONArray("newFolders")?.let { arr ->
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim().take(AUTO_CLASSIFY_MAX_FOLDER_NAME)
            if (name.isBlank()) continue
            // 同名折叠成一条；和现有组同名则整条丢弃。
            if (!declared.add(name.lowercase())) continue
            if (existingFolderNames.any { it.trim().equals(name, ignoreCase = true) }) continue
            val desc = obj.optString("description").trim()
                .takeIf { it.isNotEmpty() }?.take(SessionListViewModel.FOLDER_DESC_MAX_CHARS)
            newFolders += GroupSuggestion.Create(name, desc)
        }
    }

    val assignments = mutableListOf<AutoClassifyAssignment>()
    val seen = mutableSetOf<String>()
    json.optJSONArray("assignments")?.let { arr ->
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val sessionId = obj.optString("sessionId").trim()
            val folderName = obj.optString("folder").trim().take(AUTO_CLASSIFY_MAX_FOLDER_NAME)
            if (sessionId !in knownSessionIds || folderName.isBlank()) continue
            // 一条会话只能落一个组：后出现的重复 id 一律忽略（先到先得）。
            if (!seen.add(sessionId)) continue
            assignments += AutoClassifyAssignment(sessionId, folderName)
        }
    }

    // 只有被引用到的新组才值得建 —— 模型经常先列一堆候选组名再只用其中一个。
    val referenced = assignments.mapTo(mutableSetOf()) { it.folderName.lowercase() }
    val usedNewFolders = newFolders.filter { it.name.lowercase() in referenced }
    return AutoClassifyPlan(assignments = assignments, newFolders = usedNewFolders)
}

// ─── 分组子模型的公共选型 / 发送 ──────────────────────────────────────
// [T-android-group-auto-classify] 「AI 建议」（picker 内）与顶栏「魔术」按钮
// 是同一个动作的两种触发方式：都把一段整理意图 + 一组会话标题交给子模型，
// 都要求纯 JSON。因此候选链和发送循环各只留一份——两套并行实现最可能的结局
// 是其中一套悄悄丢掉 OAuth 刷新或凭证轮换，而失败只会表现为「AI 不工作」。
// [T-android-group-ai-suggest]

/**
 * 把「能问的子模型」排成一条降级链，按可信度从高到低：
 *
 * 1. 设置里专门配的子模型（[ProviderRepository.resolveTitleSubEntry]）；
 * 2. 锚点会话自己绑定的模型（锚点来自会话列表，用户最可能已经配好）；
 * 3. 其余任何能出文本的候选。
 *
 * @param anchorModelIds 候选锚点。调用方按**稳定顺序**传入——iOS
 *   [T-ios-folder-suggest-anchor-nondeterminism] 的教训是：第一个会话恰好
 *   没配模型时，整个功能会对用户永久失效，且原因他既看不见也影响不了。
 * @param logTag 日志前缀，便于区分是哪个入口失败的。
 */
internal fun SessionListViewModel.groupSubModelCandidates(
    anchorModelIds: List<String>,
    logTag: String,
): List<ModelEntry> {
    // 只有「能输出文本的聊天模型」才适合干这件事：TTS / embedding / 图像
    // 模型会以各种方式失败（或干脆回一段二进制），把它们放进降级链只是
    // 让重试次数白烧。
    val titleEligible = providerRepository.allVisibleEntries().filter { entry ->
        val outs = entry.model.outputModalities
        val outputsText = outs == null || outs.isEmpty() || outs.contains("text")
        val idLower = entry.model.id.lowercase()
        val nonChatId = listOf("tts", "voiceclone", "voicedesign", "embedding", "embed-", "whisper", "image", "video")
            .any { idLower.contains(it) }
        outputsText && !nonChatId
    }
    val subEntry = providerRepository.resolveTitleSubEntry()
        ?.takeIf { sub -> titleEligible.any { it == sub } }
    val anchorPrimary = anchorModelIds.firstNotNullOfOrNull { modelId ->
        titleEligible.firstOrNull { it.model.id == modelId }
    }
    val candidates = (listOfNotNull(subEntry, anchorPrimary) +
        titleEligible.filter { it != subEntry && it != anchorPrimary })
    if (candidates.isEmpty()) {
        AppLogger.error(
            TAG,
            "$logTag FAILED reason=no-sub-model-available " +
                "(no enabled+credentialed+text-output entry; anchor candidates=${anchorModelIds.size})",
        )
        throw IllegalStateException("No sub model available")
    }
    return candidates
}

/**
 * 沿降级链问一遍，返回**第一个能解析出结果**的回复；全灭返回 null。
 *
 * [parse] 放进循环里而不是循环外：一个候选回了段废话（模型把 JSON 包在散文
 * 里、或干脆截断）时，应当直接走到下一个候选，而不是把整次尝试报销掉。
 * 这正是 iOS 逐题重试下一个的语义。
 *
 * 抛 [IllegalStateException] 只在连候选都没有时发生（由
 * [groupSubModelCandidates] 完成打点）。
 */
internal suspend fun <T : Any> SessionListViewModel.groupSubModelText(
    candidates: List<ModelEntry>,
    prompt: String,
    systemPrompt: String,
    logTag: String,
    parse: (String) -> T?,
): T? {
    var lastError: Exception? = null
    for (entry in candidates) {
        val instance = providerRepository.instance(entry.providerInstanceId) ?: continue
        // [T-credential-pool-x] 这是一次真实的发送：轮换凭证池，让 429 掉
        // 的那个槽位精确冷却，下一个候选自然走到新 key。无 key 的自建实例
        // 仍然是候选（拿 "" 顶上）。
        var apiKey = providerRepository.requestCredential(instance)?.first ?: continue
        if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth) {
            try {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val freshToken = manager?.validAccessToken()
                if (freshToken != null && freshToken != apiKey) {
                    providerRepository.saveApiKey(instance.id, freshToken)
                    apiKey = freshToken
                }
            } catch (e: Exception) {
                Log.w(TAG, "$logTag OAuth refresh failed: ${e.message}")
            }
        }
        val provider = try {
            ProviderFactory.create(instance, apiKey, entry.model, context)
        } catch (e: Exception) {
            Log.w(TAG, "$logTag provider creation failed for ${entry.model.displayName}: ${e.message}")
            continue
        }
        try {
            // Same budget shape as title-gen: a reasoning model needs room
            // to finish thinking before it can emit the JSON, even with
            // thinking explicitly OFF (which is a no-op on some models).
            val maxTokens = if (entry.model.supportsReasoning == true) 2048 else 256
            val response = provider.sendMessage(
                messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                systemPrompt = systemPrompt,
                maxTokens = maxTokens,
                temperature = entry.overrides.temperature,
                thinkingLevel = ThinkingLevel.OFF,
            )
            val text = response.text
            if (text.isBlank()) {
                Log.w(
                    TAG,
                    "$logTag empty reply from ${entry.model.displayName}: " +
                        "stopReason=${response.stopReason}",
                )
                continue
            }
            val parsed = parse(text)
            if (parsed != null) {
                Log.d(TAG, "$logTag reply via ${entry.model.displayName} (${text.length} chars)")
                return parsed
            }
            Log.w(
                TAG,
                "$logTag unparseable reply from ${entry.model.displayName}: " +
                    "raw=\"${text.take(160)}\"",
            )
        } catch (e: Exception) {
            lastError = e
            Log.w(TAG, "$logTag via ${entry.model.displayName} failed: ${e.message}")
            continue
        }
    }
    Log.w(TAG, "$logTag all candidates exhausted, last=${lastError?.message}")
    return null
}

internal suspend fun SessionListViewModel.runGroupSuggestion(sessionIds: List<String>): GroupSuggestion {
    // [T-ios-folder-suggest-anchor-nondeterminism] Walk the selection in a
    // STABLE (sorted) order and take the first session that actually
    // resolves a usable model, instead of letting one arbitrary session
    // decide whether the feature works. On iOS the ids arrived from a Set,
    // so an unlucky first session with an unconfigured model made
    // "AI Suggest" fail permanently for reasons the user could neither see
    // nor influence. Ours is a List, but the same failure mode applies to
    // whichever session happens to be first, and sorting also makes the
    // sample below reproducible across launches.
    val sorted = sessionIds.sorted()

    // 选型与「魔术」整理共用 [groupSubModelCandidates]：两条路都只是"问子模型
    // 一句话"，维护两套候选链只会让其中一套悄悄腐坏。
    val candidates = groupSubModelCandidates(
        anchorModelIds = sorted.mapNotNull { chatRepository.getSession(it)?.modelId },
        logTag = "[GroupSuggest]",
    )

    // Existing groups + up to 3 member titles each, deduped by name so two
    // offline-created "Work" folders don't both bid for the merge.
    val folders = chatRepository.listFolders()
    val seenNames = mutableSetOf<String>()
    val folderLines = mutableListOf<String>()
    for (f in folders) {
        if (!seenNames.add(f.name.lowercase())) continue
        val memberTitles = chatRepository.sessionIdsInFolder(f.id).take(3)
            .mapNotNull { chatRepository.getSession(it)?.title }
        val descPart = f.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        folderLines += "- \"${f.name}\"$descPart: ${memberTitles.joinToString(" / ")}"
    }

    // Sorted + capped at 20 for the same reason as the anchor: an unsorted
    // sample would send a different 20 sessions on each run.
    val sessionLines = sorted.take(20).mapNotNull { sid ->
        chatRepository.getSession(sid)?.let { s ->
            "- ${s.title ?: "Untitled"} [${s.category ?: "other"}]"
        }
    }
    if (sessionLines.isEmpty()) {
        AppLogger.error(
            TAG,
            "[GroupSuggest] FAILED reason=no-sessions-found " +
                "(${sessionIds.size} id(s) selected, none resolved in the store)",
        )
        throw IllegalStateException("No sessions found")
    }

    val foldersBlock = if (folderLines.isEmpty()) "The user has no folders yet."
    else "Existing folders with sample member titles:\n${folderLines.joinToString("\n")}"
    val prompt = buildString {
        append("The user selected these chat sessions to file into a folder:\n")
        append(sessionLines.joinToString("\n"))
        append("\n\n")
        append(foldersBlock)
        append("\n\n")
        append("Decide: merge them into ONE existing group (only if they clearly fit it), ")
        append("or propose ONE new group name (2-8 characters preferred, in the same language as the session titles). ")
        append("For a new group also write \"description\": one sentence (under 100 characters, same language as the name) ")
        append("describing what belongs in it — it will guide future automatic grouping.\n\n")
        append("You MUST respond with valid JSON only. Examples:\n")
        append("{\"decision\": \"merge\", \"folder\": \"Work\"}\n")
        append("{\"decision\": \"create\", \"name\": \"Trip Planning\", \"description\": \"Flights, hotels and itineraries for upcoming trips\"}")
    }

    val parsed = groupSubModelText(candidates, prompt, GROUP_SUGGEST_SYSTEM_PROMPT, "[GroupSuggest]") {
        SessionListViewModel.parseGroupSuggestion(it, folders)
    }
    if (parsed == null) {
        AppLogger.error(
            TAG,
            "[GroupSuggest] FAILED reason=all-candidates-exhausted " +
                "(no parseable reply from ${candidates.size} candidate(s))",
        )
        throw IllegalStateException("Unparseable suggestion")
    }
    AppLogger.info(TAG, "[GroupSuggest] OK result=$parsed")
    return parsed
}

/**
 * [autoClassifySessions] 的 IO 侧主体：扫描 → 问模型 → 建组 → 批量落库。
 *
 * 失败路径一律返回 [AutoClassifyState.Failed] 而不是抛异常：这里的每一种失败
 * （没配模型、模型回废话、没有可整理的会话）都是**用户能理解并自己处理**的
 * 情况，把异常抛给上层只会变成一句「出错了」。
 */
internal suspend fun SessionListViewModel.runAutoClassify(): AutoClassifyState {
    val folders = chatRepository.listFolders()
    val allSessions = chatRepository.listSessions()
    // 悬空的 folderId（组已被删）在 UI 上就显示成未归组，这里必须用同一套
    // 判据 —— 否则那些会话既不会被整理，也永远追不回来。
    val liveFolderIds = folders.mapTo(HashSet()) { it.id }
    // `listSessions()` 已经是 updated_at DESC，即最近用过的排前面。
    val unassigned = allSessions.filter { s ->
        val fid: String? = s.folderId
        fid.isNullOrBlank() || fid !in liveFolderIds
    }
    if (unassigned.isEmpty()) {
        AppLogger.info(TAG, "[AutoClassify] SKIP reason=no-unassigned-sessions")
        return AutoClassifyState.Failed(context.getString(R.string.group_magic_no_sessions))
    }
    val batch = unassigned.take(AUTO_CLASSIFY_MAX_SESSIONS)
    AppLogger.info(
        TAG,
        "[AutoClassify] scanned folders=${folders.size} unassigned=${unassigned.size} " +
            "sent=${batch.size}",
    )

    // 选中要送出去的会话，以及每条的上下文：标题 + 首条用户消息摘要。
    // 标题是 AI 写的语义摘要，摘要只截 120 字 —— 足够判断主题，又不至于变成
    // 「把聊天记录整段上传给子模型」。这与 picker 内的 AI 建议保持同一隐私尺度，
    // 只是整理整表时标题不够用（新建的空会话标题常常是"New Chat"）。
    val sessionLines = batch.map { s ->
        val title = s.title?.takeIf { it.isNotBlank() && it != SessionListViewModel.NEW_CHAT_TITLE }?.trim()
            ?: s.lastMessage?.trim()?.take(AUTO_CLASSIFY_SUMMARY_CHARS)
            ?: "Untitled"
        val summary = runCatching {
            TaskAnchor.firstUserTextFromParts(chatRepository.firstUserPartsJson(s.id))
        }.getOrNull()?.replace(Regex("\\s+"), " ")?.trim()?.take(AUTO_CLASSIFY_SUMMARY_CHARS)
        buildString {
            append("- id=").append(s.id)
            append(" | title=").append(title)
            if (!summary.isNullOrBlank() && summary != title) {
                append(" | first-user: ").append(summary)
            }
            s.category?.takeIf { it.isNotBlank() }?.let { append(" | category=").append(it) }
        }
    }

    // 现有组：名字 + 描述 + 每组的示例成员标题。示例成员让模型能判断「这个组
    // 讲的是哪种话题」，只有组名的话它只能靠字面匹配。
    val folderLines = folders.map { f ->
        val members = chatRepository.sessionIdsInFolder(f.id).take(3)
            .mapNotNull { chatRepository.getSession(it)?.title }
        val descPart = f.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        "- \"${f.name}\"$descPart: ${members.joinToString(" / ").ifBlank { "—" }}"
    }

    val prompt = buildString {
        append("以下会话目前都没有归入任何分组。请把它们分配到合适的分组里：\n")
        append(sessionLines.joinToString("\n"))
        append("\n\n")
        if (folderLines.isEmpty()) {
            append("The user has no folders yet.\n")
        } else {
            append("现有分组（名称 (描述): 示例成员标题）：\n")
            append(folderLines.joinToString("\n"))
            append("\n")
        }
        append("\n")
        append("规则：\n")
        append("1. 能放进现有分组就优先复用，不要重复建同名分组（大小写不敏感）；\n")
        append("2. 只有确实没有合适归属时才新建分组；组名 2-8 个字，与标题同语言；\n")
        append("3. 新建分组时必须给 \"description\"：一句话（不超过 100 字）说明什么内容该放进来，它会被用来指导以后的自动分组；\n")
        append("4. 确实无法归类的会话**不要**出现在 assignments 里（保持未归组）；\n")
        append("5. 一条会话最多出现一次。\n\n")
        append("你只能返回合法的 JSON，格式严格如下：\n")
        append("{\"assignments\":[{\"sessionId\":\"<上面的 id>\",\"folder\":\"<现有组名或新组名>\"}],")
        append("\"newFolders\":[{\"name\":\"...\",\"description\":\"...\"}]}")
    }

    val candidates = groupSubModelCandidates(
        anchorModelIds = batch.map { it.modelId },
        logTag = "[AutoClassify]",
    )

    // 解析失败重试一次：模型偶尔会吐一段纯散文或截断的 JSON。一次 attempt
    // 已经会沿整条候选链逐条重试（见 [groupSubModelText]），所以这里的
    // "重试"是**整条链再问一遍** —— 对偶发的截断有效，而逐条重试做不到。
    // 代价只有一次额外的模型调用，比直接告诉用户失败划算。
    val knownIds = batch.mapTo(HashSet()) { s -> s.id }
    val existingNames = folders.mapTo(HashSet()) { f -> f.name }
    fun parsePlan(text: String) =
        parseAutoClassifyReply(text, knownSessionIds = knownIds, existingFolderNames = existingNames)

    var plan = groupSubModelText(
        candidates, prompt, AUTO_CLASSIFY_SYSTEM_PROMPT, "[AutoClassify]", ::parsePlan,
    )
    if (plan == null) {
        AppLogger.warning(TAG, "[AutoClassify] retry reason=unparseable-reply attempt=1")
        plan = groupSubModelText(
            candidates, prompt, AUTO_CLASSIFY_SYSTEM_PROMPT, "[AutoClassify/retry]", ::parsePlan,
        )
    }
    if (plan == null) {
        AppLogger.error(TAG, "[AutoClassify] FAILED reason=unparseable-reply-after-retry")
        return AutoClassifyState.Failed(context.getString(R.string.group_magic_failed))
    }

    if (plan.assignments.isEmpty()) {
        // 模型看完了，认为没有该动的 —— 这是成功，不是失败，只是 0 条。
        AppLogger.info(TAG, "[AutoClassify] OK moved=0 (model proposed nothing)")
        return AutoClassifyState.Done(0)
    }

    // 组名 → 组 id。同名组只按 updated_at 最新的那一个落库（与
    // [parseGroupSuggestion] 的 tie-break 一致）：schema 允许重名，随便挑
    // 一个会让"这次归进 Work、下次归进另一个 Work"。
    // listFolders 本身就是 updated_at DESC，先出现的先占位。
    val folderIdByName = mutableMapOf<String, String>()
    for (f in folders) {
        folderIdByName.putIfAbsent(f.name.trim().lowercase(), f.id)
    }
    // 只有真正被引用到的新组才建（parse 阶段已经过滤过一遍，这里是第二道保险）。
    for (newFolder in plan.newFolders) {
        val key = newFolder.name.trim().lowercase()
        if (folderIdByName.containsKey(key)) continue
        val created = chatRepository.createFolder(
            name = newFolder.name,
            description = newFolder.description,
            origin = SessionListViewModel.FOLDER_ORIGIN_AI,
        )
        folderIdByName[key] = created.id
        AppLogger.info(TAG, "[AutoClassify] created folder name=${created.name} id=${created.id}")
    }

    var moved = 0
    var skippedFiled = 0
    for ((folderKey, assignments) in plan.assignments.groupBy { it.folderName.trim().lowercase() }) {
        val folderId = folderIdByName[folderKey]
        if (folderId == null) {
            // 模型点名了一个既不在现有组、也不在 newFolders 里的组名。
            AppLogger.warning(TAG, "[AutoClassify] skip unknown folder=$folderKey n=${assignments.size}")
            continue
        }
        // 逐条走 setFolderIfUnfiled 而不是批量 setFolderForSessions：条件是
        // UPDATE 的一部分，扫描期间被用户手动归组的会话因此不会被本流程覆盖。
        // （批量接口没有「只动未归组」的变体。）
        for (a in assignments) {
            val ok = try {
                chatRepository.setFolderIfUnfiled(folderId, a.sessionId)
            } catch (e: Exception) {
                AppLogger.warning(TAG, "[AutoClassify] file failed session=${a.sessionId}: ${e.message}")
                false
            }
            if (ok) moved++ else skippedFiled++
        }
    }

    AppLogger.info(
        TAG,
        "[AutoClassify] OK moved=$moved skipped=$skippedFiled " +
            "newFolders=${plan.newFolders.size} candidates=${candidates.size}",
    )
    return AutoClassifyState.Done(moved)
}
