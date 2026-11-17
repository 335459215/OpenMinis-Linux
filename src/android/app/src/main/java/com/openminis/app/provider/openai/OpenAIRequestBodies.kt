package com.openminis.app.provider.openai

import android.util.Base64
// [T-client-disguise-app-level] App 级伪装配置：请求层唯一入口。
import com.openminis.app.data.ClientDisguisePrefs
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.AcceptHeaderPolicy
import com.openminis.app.provider.SamplingIdentity
import com.openminis.app.provider.SamplingPolicy
// [T-client-disguise-app-level] 伪装客户端画像表（App 级 + 单渠道开关共用）。
import com.openminis.app.provider.disguise.DisguiseClients
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.compat.MaxTokensField
import com.openminis.app.provider.thinking.ThinkingEffort
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal class OpenAIRequestBodies(
    private val host: Host,
) {
    interface Host {
        val model: LLMModel
        val basePath: String
        val extraHeaders: Map<String, String>
        val codexAccountId: String?
        val useResponsesAPI: Boolean
        val forceChatCompletions: Boolean
        val customUserAgent: String?
        val isAzure: Boolean
        val isOAuth: Boolean
        val chatExtraBody: Map<String, Any?>
        val chatExtraHeaders: Map<String, String>
        val absoluteEndpointOverride: String?
        val isOpenRouter: Boolean
        val needsOpenRouterAnthropicCacheControl: Boolean
        val isMistral: Boolean
        val isDashScope: Boolean
        val compatFlags: com.openminis.app.provider.compat.CompatFlags // [T-compat-flags] pi compat-schema.ts switch table
        val isXAI: Boolean
        val usesUnifiedReasoningEffort: Boolean
        val thinkingRuleInstanceId: String?
        fun resolvedServiceTier(): String?
        fun endpointURL(defaultPath: String): String
        fun azureUrl(path: String): String?
        suspend fun getToken(): String
        fun explicitOffEffort(): String?
        fun applyKeyAuth(builder: Request.Builder, token: String): Request.Builder
        val codexClientVersion: String
        /** [T-codex-client-fingerprint] Opt-in Codex-official identity headers. */
        val codexClientFingerprintEnabled: Boolean
        fun clampThinkingLevel(level: ThinkingLevel): ThinkingLevel
        val provider: com.openminis.app.provider.LLMProvider
    }

    private fun Request.Builder.applyKeyAuth(token: String): Request.Builder =
        host.applyKeyAuth(this, token)

    // `internal` rather than private so the serialization can be asserted
    // directly in unit tests. The tool-result-image regression this guards
    // (T-android-toolresult-image-dropped) is a property of the request BODY,
    // and going through MockWebServer to read it only adds a network dependency
    // to a question that is pure JSON construction.
    internal fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        // T264: cross-provider image sanitization, mirrors iOS
        // OpenAIAgentProvider.swift:744-768 / 900-918. When the target model
        // doesn't declare "image" in inputModalities (e.g. DeepSeek V4 after
        // user sent image to GPT-5.5 then switched provider), serialize a
        // text placeholder instead of an image_url block — otherwise the
        // server returns "400 unknown variant `image_url`". Decided once
        // here so the structured-contentParts loop and the legacy
        // imageParts loop below stay consistent.
        // [T-android-vision-native-check-misses-image_input] hasImageInput, not a
        // raw membership test: this compared the catalog string EXACTLY, so
        // "image_input" (OpenAI/OpenRouter) and "Image" both read as "no vision"
        // and the pixels below were swapped for a text placeholder.
        val supportsImages = host.model.hasImageInput
        val body = JSONObject()
        body.put("model", host.model.id)
        // [T-android-dashscope-max-tokens #35][T-compat-flags] Endpoint-level
        // output-limit field name. OpenAI's newer Chat Completions wants
        // `max_completion_tokens`, but several OpenAI-compatible vendors (most
        // visibly DashScope / Bailian) only accept the legacy `max_tokens` and
        // 400 the whole request on the other name. Resolution lives in
        // CompatFlags.maxTokensField (pi compat-schema.ts; URL-matched family
        // tables, not capability guesses); OpenRouter already had this shape,
        // DashScope joins it.
        when (host.compatFlags.maxTokensField) {
            MaxTokensField.MAX_TOKENS -> body.put("max_tokens", maxTokens)
            MaxTokensField.MAX_COMPLETION_TOKENS -> body.put("max_completion_tokens", maxTokens)
        }
        body.put("stream", stream)

        // [T-android-xai-priority] xAI Priority Processing, driven by the same
        // app-level Fast Mode toggle as Codex (FastModePrefs), read here at
        // request-build time so a flip applies to the very next request.
        // Emitted only for xAI-capable providers, so every other provider's
        // body is unchanged — `service_tier` is an xAI extension and a strict
        // OpenAI-compatible relay would 400 on the unknown key.
        host.resolvedServiceTier()?.let { body.put("service_tier", it) }

        SamplingPolicy.wire(
            SamplingIdentity.of(host.provider), host.model.id, temperature, thinkingLevel.isEnabled,
        )?.let { body.put("temperature", it) }

        if (stream && host.compatFlags.supportsUsageInStreaming) { // [T-compat-flags] pi supportsUsageInStreaming
            body.put("stream_options", JSONObject().put("include_usage", true))
        }

        // Provider-specific thinking params. We always call this — some
        // models (e.g. DeepSeek V4) reason by default and need an explicit
        // `disabled` signal when the user toggles thinking off.
        //
        // [T-android-mistral-reasoning-422] …EXCEPT on Mistral, which rejects
        // the thinking request parameters outright with
        // `422 extra_forbidden body.reasoning`. Mirrors iOS
        // OpenAIAgentProvider.swift's `if !provider.isMistral` gate around this
        // same call (4592ca9b). Until now [isMistral] only suppressed the
        // message-level echo ([forbidReasoningField], 0839f019 / GH
        // OpenMinis#87) — the request-parameter half of that fix was never
        // ported, so an enabled thinking level still put `reasoning_effort` on
        // the wire to api.mistral.ai.
        if (!host.isMistral) {
            injectThinkingParams(body, thinkingLevel, maxTokens)
        }

        // [OpenMinis#191] Opt this request into Anthropic prompt caching.
        // OpenRouter passes the field through to Anthropic but never injects it
        // for us, so without it Claude requests cache nothing at all.
        //
        // Top-level "automatic" form: the breakpoint advances to the last
        // cacheable block on its own as the conversation grows, which is what an
        // agent loop wants — the per-block form would need us to hand-manage a
        // 4-breakpoint budget across a mutating history.
        //
        // Gated on host + `anthropic/` prefix, so no other model's body changes.
        if (host.needsOpenRouterAnthropicCacheControl) {
            body.put("cache_control", JSONObject().put("type", "ephemeral"))
        }

        // Tools
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(tool.toOpenAIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        val messagesArray = JSONArray()
        if (systemPrompt != null) {
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        // Mirror iOS OpenAIAgentProvider.flattenChatCompletionsMessages —
        // echo reasoning_content on prior assistant turns when:
        //   - user requested thinking this turn, OR the model always reasons (forced); AND
        //   - the model isn't explicitly known to reject reasoning.
        // Prevents 400s from Kimi / DeepSeek / GLM / QwQ that reject
        // multi-turn history missing reasoning_content once thinking is on.
        val modelAlwaysReasons = host.model.supportsReasoning == true
        val modelMayReason = host.model.supportsReasoning ?: true
        // [T-android-mistral-reasoning-422] Mistral forbids reasoning_content on
        // assistant messages entirely (closed schema → HTTP 422
        // extra_forbidden), so suppress BOTH the captured echo and the ""
        // placeholder for that endpoint. This cannot be driven by capability
        // metadata: MiMo/DeepSeek require the field's PRESENCE on multi-turn
        // history while Mistral forbids it, and neither advertises
        // supportsReasoning via /v1/models — opposite requirements on the same
        // generic openAI provider path. Hence a spec-driven vendor flag.
        val forbidReasoningField = host.isMistral
        val includeReasoning =
            (thinkingLevel.isEnabled || modelAlwaysReasons) && modelMayReason && !forbidReasoningField
        val echoReasoning = includeReasoning
        // T-mimo-reasoning-echo-34671: Mimo V2.5 returns 400 Param Incorrect on
        // multi-turn tool-call history when any prior assistant turn (especially
        // a tool_calls-bearing one) omits `reasoning_content`. Mimo's docs say
        // the field MUST be present (empty string OK) whenever thinking is on
        // and a tool call is in history. We drop the previous interleaved-only
        // gate and always emit reasoning_content (possibly "") whenever the
        // echo gate (includeReasoning) is true. OpenAI o-series ignores
        // unknown message-level `reasoning_content` so this stays harmless
        // there; non-reasoning models gate this off via includeReasoning=false.
        val placeholderAllowed = includeReasoning

        val lastUserIndex = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, msg) in messages.withIndex()) {
            if (msg.contentParts.isNotEmpty()) {
                // Structured content parts
                when {
                    // Assistant with tool_use → emit assistant message with tool_calls
                    msg.role == LLMMessage.Role.ASSISTANT -> {
                        val obj = JSONObject()
                        obj.put("role", "assistant")
                        if (echoReasoning) {
                            val rc = msg.reasoningContent
                            if (rc != null) {
                                // Round-trip exactly what the server emitted,
                                // including empty strings. DeepSeek V4 emits
                                // `reasoning_content: ""` on non-thinking turns
                                // and accepts the same shape on input — the empty
                                // value is the field-presence guarantee that
                                // prevents 400s once thinking is on.
                                obj.put("reasoning_content", rc)
                            } else if (placeholderAllowed) {
                                // No captured reasoning for this turn (e.g. fallback
                                // to a non-thinking model, or message persisted before
                                // thinking was enabled). Send "" rather than a synthetic
                                // marker: prior placeholders ("[no prior reasoning]",
                                // T249; single space, T257) were in-context-learned by
                                // DeepSeek V4 and echoed back as the model's own
                                // reasoning. Empty string satisfies the field-presence
                                // check with no learnable pattern.
                                obj.put("reasoning_content", "")
                            }
                        }
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        // [T-openai-assistant-empty-content] Always emit "content":
                        // omitting it on a tool_calls-only turn 400s strict gateways.
                        obj.put("content", textParts.joinToString("") { it.text })
                        val toolUseParts = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                        if (toolUseParts.isNotEmpty()) {
                            val toolCallsArr = JSONArray()
                            for (tu in toolUseParts) {
                                toolCallsArr.put(JSONObject().apply {
                                    put("id", capChatToolCallId(tu.id))
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", tu.name)
                                        put("arguments", tu.input.toString())
                                    })
                                })
                            }
                            obj.put("tool_calls", toolCallsArr)
                        }
                        messagesArray.put(obj)
                    }
                    // User with tool_results → emit separate tool messages
                    msg.role == LLMMessage.Role.USER -> {
                        val toolResults = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        val imageParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()

                        // [GH#293/#352] Tool results MUST be contiguous after
                        // the assistant tool_calls message. The image-carrier
                        // user messages used to be emitted INSIDE this loop,
                        // so with two image-bearing results the wire read
                        // tool,tool,user,tool — strict gateways answered
                        // "Messages with role 'tool' must be a response to a
                        // preceding message with 'tool_calls'". Collect them
                        // and append after every tool reply instead.
                        val toolResultImageMessages = mutableListOf<JSONObject>()
                        for (tr in toolResults) {
                            messagesArray.put(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", capChatToolCallId(tr.id))
                                put("content", tr.content)
                            })
                            // [T-android-toolresult-image-dropped] THE reported bug.
                            // read_image hands its pixels back on the ToolResult
                            // (ChatViewModel sets imageData on the part), but this
                            // loop only ever emitted `content`, so on a native-vision
                            // model the bytes were dropped at the provider boundary
                            // and the model answered "I can't actually see pixels" —
                            // with a green, successful-looking tool card above it.
                            //
                            // Chat Completions has no image block inside a `tool`
                            // message (the schema takes a plain string), so the
                            // pixels ride on a following USER message that references
                            // the call. Anthropic can nest the image directly in its
                            // tool_result and does (AnthropicProvider ~L600); this is
                            // the same intent expressed in the shape this API allows.
                            val trBytes = tr.imageData
                            if (trBytes != null && trBytes.isNotEmpty() && supportsImages) {
                                val safeBytes = com.openminis.app.provider.ImageBudget
                                    .compressUnderBudget(trBytes)
                                val safeMime = if (safeBytes === trBytes) {
                                    tr.imageMimeType ?: "image/jpeg"
                                } else "image/jpeg"
                                val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                                toolResultImageMessages += JSONObject().apply {
                                    put("role", "user")
                                    put("content", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "text")
                                            put(
                                                "text",
                                                "[Image returned by ${tr.name}]",
                                            )
                                        })
                                        put(JSONObject().apply {
                                            put("type", "image_url")
                                            put("image_url", JSONObject().apply {
                                                put("url", "data:$safeMime;base64,$b64")
                                            })
                                        })
                                    })
                                }
                            }
                        }
                        // [GH#293/#352] All tool replies first, then the
                        // image carriers — never interleaved.
                        toolResultImageMessages.forEach { messagesArray.put(it) }
                        // T132: emit text + image_url parts as a structured user
                        // message. The previous structured-contentParts branch
                        // dropped AgentContentPart.ImageData entirely — only the
                        // legacy non-contentParts path knew how to encode images,
                        // and that path is unreachable once contentParts is
                        // populated (which is always now). Mirrors iOS
                        // OpenAIAgentProvider.swift L732-738.
                        val hasImages = imageParts.isNotEmpty()
                        if (hasImages || textParts.isNotEmpty()) {
                            if (hasImages) {
                                val contentArray = JSONArray()
                                // Walk contentParts in original order so the
                                // [attached image: …] text caption that
                                // precedes each ImageData part stays adjacent
                                // to the right image, matching iOS.
                                for (part in msg.contentParts) {
                                    when (part) {
                                        is AgentContentPart.Text -> {
                                            if (part.text.isNotEmpty()) {
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", part.text)
                                                })
                                            }
                                        }
                                        is AgentContentPart.ImageData -> {
                                            if (supportsImages) {
                                                // T-imgsize: backstop — re-encode oversize
                                                // history image bytes before base64-inlining.
                                                val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(part.data)
                                                val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                                                val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "image_url")
                                                    put("image_url", JSONObject().apply {
                                                        put("url", "data:$safeMime;base64,$b64")
                                                    })
                                                })
                                            } else {
                                                // T264: target model has no vision modality —
                                                // emit a text placeholder in place of the pixels.
                                                // [T-android-vision-group / GH#182] Vision-Group
                                                // read_image hint when seeded (carries the path);
                                                // else the historical literal.
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", part.noVisionPlaceholder
                                                        ?: "[Image attached but this model does not support vision input]")
                                                })
                                            }
                                        }
                                        else -> Unit  // ToolUse/ToolResult never appear on user role here
                                    }
                                }
                                messagesArray.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", contentArray)
                                })
                            } else {
                                messagesArray.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", textParts.joinToString("") { it.text })
                                })
                            }
                        }
                    }
                }
            } else {
                // Legacy: plain text messages
                val obj = JSONObject()
                obj.put("role", msg.role.value)

                if (echoReasoning && msg.role == LLMMessage.Role.ASSISTANT) {
                    val rc = msg.reasoningContent
                    if (rc != null) {
                        // Round-trip exactly what the server emitted (including "").
                        // See structured-content branch above for the full rationale.
                        obj.put("reasoning_content", rc)
                    } else if (placeholderAllowed) {
                        // No captured reasoning — empty string satisfies the field-
                        // presence check without giving DeepSeek V4 a learnable
                        // marker to imitate (T249 / T257 history).
                        obj.put("reasoning_content", "")
                    }
                }

                val attachTopLevelImages =
                    index == lastUserIndex && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
                if (attachTopLevelImages || msg.audioParts.isNotEmpty()) {
                    val contentArray = JSONArray()
                    if (attachTopLevelImages) {
                        for (part in imageParts) {
                            if (supportsImages) {
                                // T-imgsize: provider-boundary backstop.
                                val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(part.data)
                                val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                                val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                                val imageUrl = JSONObject()
                                imageUrl.put("url", "data:$safeMime;base64,$b64")
                                contentArray.put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", imageUrl)
                                })
                            } else {
                                // T264: target model has no vision modality — emit
                                // a text placeholder in place of the pixels.
                                // [T-android-vision-group / GH#182] When a Vision
                                // Group is configured, ChatViewModel seeds
                                // part.noVisionPlaceholder with a read_image call
                                // hint (carrying the image path) so the model
                                // routes the image through the group instead of
                                // being told it can't see it. Null → the historical
                                // iOS-parity literal (no Vision Group configured).
                                contentArray.put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", part.noVisionPlaceholder
                                        ?: "[Image attached but this model does not support vision input]")
                                })
                            }
                        }
                    }
                    // [GH#67] Official Chat Completions audio-input shape,
                    // forwarded verbatim (modality is gated at the call site).
                    for (audio in msg.audioParts) {
                        contentArray.put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().apply {
                                put("data", audio.base64Data)
                                put("format", audio.format)
                            })
                        })
                    }
                    // Preserve the pre-GH#67 image-path behavior (text part
                    // always present); for audio-only messages skip an empty
                    // text block some servers reject.
                    if (attachTopLevelImages || msg.content.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                    obj.put("content", contentArray)
                } else {
                    obj.put("content", msg.content)
                }

                messagesArray.put(obj)
            }
        }
        // [T-dedupe-toolcallid follow-up] Cross-message defense-in-depth:
        // rename any tool_call_id that collides with one already seen
        // elsewhere in this request. 9421990 covers the stream-time
        // collision; historical messages reloaded from the DB — or
        // messages produced by a different provider before the user
        // switched — bypass that pass, and DeepSeek (plus several
        // OpenAI-compat gateways) reject the assembled request with
        // "Duplicate value for tool_call_id ... in message[N]" whenever
        // any id repeats across the full messages array.
        globallyDedupeToolCallIds(messagesArray)
        // [GH#222/#293/#352/#382] Last gate before the provider validates:
        // drop orphan/duplicate `role:"tool"` replies, close any assistant
        // `tool_calls` block that lost its replies, and refuse malformed
        // calls. Logged because a silent repair here used to leave no trace
        // of why a session had started "working again".
        val pairing = ChatToolPairSanitizer.sanitize(messagesArray)
        if (pairing.changed) {
            android.util.Log.w(
                "OpenAIProvider",
                "[sanitize-chat-tool-pairing] droppedOrphanResults=" +
                    "${pairing.droppedOrphanResults} droppedDuplicateResults=" +
                    "${pairing.droppedDuplicateResults} insertedMissingResults=" +
                    "${pairing.insertedMissingResults} droppedMalformedCalls=" +
                    "${pairing.droppedMalformedCalls}",
            )
        }
        body.put("messages", pairing.messages)

        // [T-android-model-use-passthrough-mode GH#72] Merge user-supplied extra
        // body fields verbatim (no OpenAI→native conversion — callers own the
        // shape). User keys win over our defaults, but `model` is force-kept so a
        // stray override can't misroute. Mirrors generateImage's merge + iOS.
        mergeChatExtraBody(body)

        return body
    }

    /**
     * [T-android-model-use-passthrough-mode GH#72] Shared verbatim merge of
     * [chatExtraBody] into a request body, applied by BOTH the chat/completions
     * and responses builders so no endpoint can forget the passthrough. User
     * keys overwrite; `model` is force-restored last. Skipped for Codex OAuth
     * (its body is part of the client fingerprint and must stay untouched).
     */
    private fun mergeChatExtraBody(body: JSONObject) {
        if (host.chatExtraBody.isEmpty()) return
        if (host.isOAuth && !host.forceChatCompletions) return  // Codex OAuth exemption
        for ((k, v) in host.chatExtraBody) body.put(k, v ?: JSONObject.NULL)
        body.put("model", host.model.id)
    }

    /**
     * Walk every assistant.tool_calls entry and every role:"tool"
     * tool_call_id in order, renaming any duplicate id to `{id}-{N}`.
     * The first occurrence keeps the raw id; subsequent collisions get
     * a numeric suffix starting at 2. Renames propagate to each pair's
     * matching role:"tool" reply by remembering the latest rename per
     * raw id (the reply is required to immediately follow its claiming
     * assistant tool_calls on this provider).
     */
    private fun globallyDedupeToolCallIds(messagesArray: JSONArray) {
        // raw id → max suffix already issued (0 = unused, 1 = raw kept,
        // 2+ = renamed copies).
        val seen = HashMap<String, Int>()
        // raw id → latest renamed id, so the next role:"tool" reply
        // claiming this raw id can pick up the same rewrite.
        val renameForPendingResults = HashMap<String, String>()
        var renamedCount = 0
        val n = messagesArray.length()
        for (i in 0 until n) {
            val msg = messagesArray.optJSONObject(i) ?: continue
            val role = msg.optString("role")
            when (role) {
                "assistant" -> {
                    val toolCalls = msg.optJSONArray("tool_calls") ?: continue
                    for (j in 0 until toolCalls.length()) {
                        val call = toolCalls.optJSONObject(j) ?: continue
                        val rawId = call.optString("id", "")
                        if (rawId.isEmpty()) continue
                        val used = seen[rawId] ?: 0
                        val renamedId: String
                        if (used == 0) {
                            renamedId = rawId
                            seen[rawId] = 1
                        } else {
                            val next = used + 1
                            renamedId = "$rawId-$next"
                            seen[rawId] = next
                            renamedCount += 1
                            call.put("id", renamedId)
                        }
                        renameForPendingResults[rawId] = renamedId
                    }
                }
                "tool" -> {
                    val rawId = msg.optString("tool_call_id", "")
                    if (rawId.isEmpty()) continue
                    val renamedId = renameForPendingResults[rawId] ?: continue
                    if (renamedId != rawId) {
                        msg.put("tool_call_id", renamedId)
                    }
                }
            }
        }
        if (renamedCount > 0) {
            android.util.Log.w("OpenAIProvider", "[dedupe-tool-call-id] renamed $renamedCount duplicate tool_call_id(s) across messages — likely DB-loaded history or cross-provider switch")
        }
    }

    /**
     * T302: takes the pre-serialized body string instead of the JSONObject so
     * the caller can serialize once and reuse the result for the debug log,
     * the OAuth byte build, and the OkHttp RequestBody. Per-call peak heap
     * dropped by ~2× the body size (often tens of MB on long agent loops).
     */
    internal suspend fun buildRequest(bodyStr: String): Request {
        val token = host.getToken()

        if (host.isOAuth && !host.forceChatCompletions) {
            // Codex OAuth — use ChatGPT backend Responses API
            // Use MediaType without charset — ChatGPT backend rejects "application/json; charset=utf-8"
            val jsonMediaType = "application/json".toMediaType()
            val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = jsonMediaType
                override fun contentLength() = bodyBytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
            }
            val builder = Request.Builder()
                .url("https://chatgpt.com/backend-api/codex/responses")
                .post(requestBody)
                .header("Authorization", "Bearer $token")
                .header("Version", host.codexClientVersion)
                .header("Openai-Beta", "responses=experimental")
                .header("User-Agent", "codex_cli_rs/${host.codexClientVersion} (Android; arm64)")
                .header("Originator", "codex_cli_rs")
            host.codexAccountId?.let { builder.header("Chatgpt-Account-Id", it) }
            // [T-provider-custom-user-agent] Applied last so a non-blank
            // override wins over the Codex default UA. In practice null on
            // this OAuth path (the UI only exposes it for custom-base
            // instances), so this is a no-op there.
            // [T-android-default-ua] `defaultUserAgent = null` — keep the
            // codex_cli_rs fingerprint set above when no per-provider
            // override is configured. We must NOT fall back to the branded
            // Minis UA here: the ChatGPT OAuth backend validates the
            // client identity against this header.
            builder.applyUserAgentOverride(host.customUserAgent, defaultUserAgent = null)
            // [T-codex-client-fingerprint] 可选：把上面那组 hand-rolled 头补齐
            // （session-id / thread-id / x-codex-window-id / x-codex-app-version /
            // x-codex-turn-metadata）。默认关闭时上面那段字节不变。
            applyCodexFingerprintHeaders(builder)
            return builder.build()
        }

        val endpointPath = if (host.useResponsesAPI) "/responses" else "/chat/completions"
        // [T-android-azure-openai] Azure routes via the deployments path and
        // auths with api-key; azureUrl() is null off Azure mode / no base.
        val requestUrl = when {
            host.absoluteEndpointOverride?.startsWith("/") == true -> host.endpointURL(endpointPath)
            host.isAzure -> host.azureUrl(endpointPath) ?: "${host.basePath}$endpointPath"
            else -> "${host.basePath}$endpointPath"
        }
        // T-responses-include: match iOS bare `application/json` Content-Type
        // via a custom RequestBody (same workaround as the OAuth branch):
        // several proxies/backends reject the charset suffix.
        val jsonMediaType = "application/json".toMediaType()
        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        val requestBody = object : okhttp3.RequestBody() {
            override fun contentType() = jsonMediaType
            override fun contentLength() = bodyBytes.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
        }
        val builder = Request.Builder()
            .url(requestUrl)
            .post(requestBody)
            .applyKeyAuth(token)
            .header("Content-Type", "application/json")
        // [T-relay-accept-406] A declared `Accept: text/event-stream` is 406
        // on strict relay gateways (even for streaming) — normalized to the
        // SDK-standard application/json; every other value passes verbatim.
        // See AcceptHeaderPolicy for the measured matrix.
        for ((key, value) in AcceptHeaderPolicy.sanitizeChatAccept(host.extraHeaders)) {
            builder.header(key, value)
        }
        // [T-android-model-use-passthrough-mode] Per-call chat header overrides,
        // applied AFTER the ctor extraHeaders → same-name REPLACE over any
        // default; Accept sanitized like the ctor map. Empty on normal calls.
        for ((key, value) in AcceptHeaderPolicy.sanitizeChatAccept(host.chatExtraHeaders)) {
            builder.header(key, value)
        }
        // [T-provider-custom-user-agent] Covers both chat/completions and
        // /responses (this builder serves both). Applied after extraHeaders
        // so the per-provider override wins. null/blank → default UA.
        builder.applyUserAgentOverride(host.customUserAgent)
        // [T-codex-client-fingerprint] Opt-in: send Codex-official client
        // identity so codex_cli_only relays accept the request. Runs AFTER
        // applyUserAgentOverride so the Codex UA wins over the branded default
        // (a user-supplied customUserAgent still takes precedence inside).
        applyCodexFingerprintHeaders(builder)
        return builder.build()
    }

    /**
     * [T-client-disguise-app-level] 该渠道当前该伪装成哪个客户端。
     *
     * 两级判定，App 级优先：
     *   1. [ClientDisguisePrefs] 的渠道绑定 —— 它内部已经判过总开关，
     *      返回 null 表示没绑或总开关关着。
     *   2. 回落到单渠道独立开关 `host.codexClientFingerprintEnabled`
     *      （旧行为，保持兼容）。
     *
     * 返回 null 是「完全不动请求」的信号：调用方必须直接 return，这样未开启
     * 伪装的实例出站字节与改前逐字节一致。
     */
    private fun activeDisguiseClientId(): String? {
        ClientDisguisePrefs.clientForChannel(host.thinkingRuleInstanceId ?: "")
            ?.let { return it }
        // 回落：单渠道独立开关（旧行为，保持兼容）
        return if (host.codexClientFingerprintEnabled) "codex-cli" else null
    }

    /**
     * [T-codex-client-fingerprint] 给 builder 打上官方 Codex 客户端身份头。
     *
     * 只在 [activeDisguiseClientId] 非 null 时生效，所以未开启伪装的实例
     * 出站字节与之前完全一致。身份按 provider 实例 id + 客户端 id 确定性派生：
     * 同一实例同一客户端重启后得到同一组 installation/session ID，不会因重启
     * 就换一个“新客户端”；切换伪装客户端则会得到另一套身份（否则冒充 desktop
     * 却带着 CLI 的 installation_id，本身就是可识别特征）。
     *
     * 头顺序按 [CodexFingerprint.headers] 的顺序逐个 header() 写入，因为顺序
     * 本身在严格风控侧可观测。
     */
    private fun applyCodexFingerprintHeaders(builder: Request.Builder) {
        val clientId = activeDisguiseClientId() ?: return
        // 非 Codex 家族（如 claude-code）返回 null：OpenAI 路径只认 Codex 画像，
        // 别的一律不改头，避免把 Anthropic 身份塞进 OpenAI 请求。
        val profile = DisguiseClients.codexProfileFor(clientId) ?: return
        val identity = codexIdentityFor(clientId)
        for ((name, value) in CodexFingerprint.headers(
            identity = identity,
            version = host.codexClientVersion,
            profile = profile,
            // 官方 CLI 直连用 text/event-stream；中转的兼容层两者都要，否则被当非法
            accept = if (host.isOAuth) "text/event-stream" else "application/json, text/event-stream",
        )) {
            builder.header(name, value)
        }
        // Codex 客户端 UA 与 originator 恒等：若用户没设自定义 UA，这里再压一次，
        // 保它不被上游的 applyUserAgentOverride 默认值覆盖。
        if (host.customUserAgent.isNullOrBlank()) {
            builder.header("User-Agent", profile.userAgent(host.codexClientVersion))
        }
    }

    /**
     * [T-client-disguise-app-level] 按伪装客户端缓存身份。
     *
     * 不能用固定 seed 的 lazy：切换客户端后身份必须跟着变。按 clientId 分桶，
     * 每个桶只算一次；回到之前用过的客户端时稳定字段还是原来那套（不会因为
     * 来回切就每次换新 installation_id）。
     *
     * 请求层本身在单线程外被调用，用 ConcurrentHashMap 兜底。
     */
    private val codexIdentityCache =
        java.util.concurrent.ConcurrentHashMap<String, CodexFingerprint.Identity>()

    /**
     * 轮次级字段的存活窗口。
     *
     * 真实 CLI 的 turn_id 是 now_v7，turn_started_at 是当前毫秒 —— 每轮都新。
     * 这里用「超过窗口就翻新」而不是「每次调用都翻新」，原因是头注入与 body
     * 注入是两个独立调用点，必须读到**同一套** turn 字段，否则 header 里的
     * turn_id 和 body client_metadata 里的对不上，那比不做伪装更可疑。
     *
     * 20 秒足够覆盖一次请求从建 body 到发头的全过程（毫秒级），又远短于人的
     * 两轮对话间隔，所以跨轮必然翻新 —— 服务端看到的是一个 turn 持续秒级，
     * 不是几小时。
     */
    private companion object {
        const val TURN_TTL_MS = 20_000L
    }

    /**
     * 取该客户端当前的伪装身份。稳定字段来自缓存，轮次级字段按
     * [TURN_TTL_MS] 翻新。
     */
    private fun codexIdentityFor(clientId: String): CodexFingerprint.Identity {
        val seed = CodexFingerprint.seedFor(host.thinkingRuleInstanceId, host.model.id) + ":" + clientId
        val now = System.currentTimeMillis()
        val current = codexIdentityCache[clientId]
        if (current != null && now - current.turnStartedAtUnixMs < TURN_TTL_MS) {
            return current
        }
        val fresh = current?.nextTurn(now) ?: CodexFingerprint.newIdentity(seed, now)
        codexIdentityCache[clientId] = fresh
        return fresh
    }

    /**
     * [T-android-xhigh-effort-clamp] Clamp the reasoning-effort string for
     * model families whose backend only accepts low/medium/high and 400/422 on
     * our `xhigh` tier: MiMo-2.5/Pro and Agnes. For these, `xhigh` → `high`;
     * every other value passes through untouched, and every other model is
     * unaffected. Applied at the single point where each branch would emit an
     * effort string (Chat Completions reasoning_effort / reasoning.effort AND
     * the Responses API reasoning.effort) so no branch can leak a raw xhigh.
     * lowercase-contains match, mirroring the T-reasoning-effort-fallback keys.
     */
    private fun clampEffortForModel(effort: String): String {
        val lid = host.model.id.lowercase()
        // [T-fallback-thinking-preclamp] Match the FAMILY substring, not one
        // spelling: catalog docs say "MiMo-2.5" but the live API returns
        // "mimo-v2.5" / "mimo-v2.5-pro", which the old "mimo-2.5" match missed
        // (mirrors iOS 72968c4f).
        return if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort
    }

    /**
     * Inject the provider-specific thinking parameters for one request.
     *
     * [T-thinking-rules-phase1] The body of this function used to be an if-return chain
     * keyed on model-id substrings. That logic now lives in [ThinkingRuleResolver] as a
     * data-driven rule registry (design §4/§5); this remains as the call-site-compatible
     * entry point so every caller — and the golden snapshot that pins this exact
     * behaviour — is unchanged.
     *
     * Behaviour is byte-for-byte identical to the pre-refactor chain, enforced by
     * ThinkingWireGoldenSnapshotTest (119 rows generated against the old code and
     * committed before the refactor, fdc28e2b).
     *
     * PHASE 1 SCOPE: OpenAI-compatible endpoints only. Gemini and Anthropic keep their
     * own emitters and are not routed through the resolver yet.
     */
    private fun injectThinkingParams(body: JSONObject, level: ThinkingLevel, maxTokens: Int) {
        // [T-android-thinking-level-arch] `level` is already clamped to the model ceiling
        // by LLMProvider.streamMessage/sendMessage — do NOT re-clamp here.
        val ctx = ThinkingResolveContext(
            modelId = host.model.id,
            instanceId = host.thinkingRuleInstanceId,
            supportsReasoning = host.model.supportsReasoning,
            declaredEffortValues = host.model.reasoningEffortValues,
            // [T-thinking-ultra-manual] Folded operator ceiling — a
            // picker-selected ULTRA wires the literal tier: the manual
            // override IS the operator's vocabulary assertion.
            ceilingOverride = host.model.maxThinkingLevel,
            budgetTokensMin = host.model.budgetTokensMin,
            budgetTokensMax = host.model.budgetTokensMax,
            // [OpenMinis#163] null (catalog silent) must read as false here —
            // only an affirmative declaration may suppress the field.
            declaresNoEffortTiers = host.model.declaresNoEffortTiers == true,
            level = host.clampThinkingLevel(level),
            maxTokens = maxTokens,
            isOpenRouter = host.isOpenRouter,
            usesUnifiedReasoningEffort = host.usesUnifiedReasoningEffort,
            isMistral = host.isMistral,
            isDashScope = host.isDashScope,
            isXAI = host.isXAI,
            offEffort = host.explicitOffEffort(),
        )
        val trace = ThinkingRuleResolver.apply(body, ctx)
        // [T-thinking-rules-observability] Design §8 / GH OpenMinis#100: which rule
        // actually won must be inspectable, or a rule layer just replaces one hidden
        // variable with a more complicated one. minis-config exposure is Phase 2.
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] model=${host.model.id} level=${level.name} ${trace.logLine}",
        )
    }

    // MARK: - Responses API (Codex OAuth)

    /**
     * Build request body for the Responses API format (used by Codex OAuth).
     * Uses `input` instead of `messages`, `instructions` instead of system prompt.
     */

    // `internal` for the same reason as [buildRequestBody]: the tool-result-image
    // regression is asserted against the constructed JSON directly.
    internal fun buildResponsesAPIBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        /**
         * [T-android-responses-toplevel-images] Images passed as the top-level
         * argument rather than on `msg.contentParts`, attached to the LAST user
         * message — the same contract [buildRequestBody] implements.
         *
         * This parameter did not exist, and that was a silent data loss: every
         * caller that supplies images this way (minis-model-use's `image_url`
         * blocks, VisionGroupResolver.describeOnce, any direct
         * sendMessage(imageParts=…)) had its pixels dropped on the floor the
         * moment the provider was on the Responses path, with no error. The
         * user-visible symptom was a vision model replying "no image was
         * provided" — reported against a Vision Group whose describing model
         * ran on Responses.
         */
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        temperature: Double? = null,
    ): JSONObject {
        // T264: same vision-capability gate as buildRequestBody. Responses API
        // path (Codex OAuth) is currently always wired to a vision-capable
        // GPT-5.x so this branch is defensive rather than load-bearing, but
        // keeping the two paths symmetric prevents future regressions when
        // a non-vision model gets routed through Responses (e.g. via
        // forceResponsesAPI on a custom provider).
        // [T-android-vision-native-check-misses-image_input] hasImageInput, not a
        // raw membership test: this compared the catalog string EXACTLY, so
        // "image_input" (OpenAI/OpenRouter) and "Image" both read as "no vision"
        // and the pixels below were swapped for a text placeholder.
        val supportsImages = host.model.hasImageInput
        val body = JSONObject()
        body.put("model", host.model.id)
        body.put("stream", stream)
        SamplingPolicy.wire(
            SamplingIdentity.of(host.provider), host.model.id, temperature, thinkingLevel.isEnabled,
        )?.let { body.put("temperature", it) }
        // [T-android-xai-priority] xAI documents service_tier for both text
        // inference endpoints. xAI currently always resolves to the Chat
        // Completions path (forceChatCompletions), so this is belt-and-braces
        // — but keeping the two builders in step means a future routing change
        // does not silently drop the user's Fast Mode choice.
        host.resolvedServiceTier()?.let { body.put("service_tier", it) }
        body.put("store", false)
        body.put("parallel_tool_calls", true)
        // Stable per-conversation cache key so the Responses API can hit prompt
        // cache across turns. Codex CLI sets this to its conversation_id; at
        // this layer we don't have one, so we hash the first user message —
        // re-sent verbatim every turn of the same chat → stable across turns,
        // distinct between chats. iOS does the same in
        // OpenAIAgentProvider.swift:325 + derivePromptCacheKey() at line 515.
        // Without this, each turn was treated as a separate prompt by the
        // Responses-API cache regardless of how byte-stable the prefix was —
        // that's the missing piece between Android (~70%) and iOS (90%+) on
        // the Codex OAuth / forceResponsesAPI path.
        body.put("prompt_cache_key", derivePromptCacheKey(messages))
        // T-responses-include: `include: ["reasoning.encrypted_content"]` is a
        // ChatGPT-backend-only field. Third-party Responses-API-compatible
        // proxies (non-OpenAI) don't recognize it and reject the request with
        // 400. Mirrors iOS OpenAIAgentProvider.swift:404 which gates this
        // strictly behind isCodexOAuth. OpenAI's first-party Responses API
        // also accepts the field, so we keep it on for OAuth (Codex) only —
        // the encrypted reasoning content is what lets the ChatGPT backend
        // re-attach prior reasoning across turns without store=true.
        if (host.isOAuth) {
            body.put("include", JSONArray().put("reasoning.encrypted_content"))
        }
        // Thinking level → Responses API `reasoning.effort`. Mirrors iOS
        // OpenAIAgentProvider.swift:327-338. Pre-T119 this was hardcoded to
        // "low" regardless of the user's setting, so toggling Thinking
        // High/Medium/Off had no effect on GPT-5.x via the Responses path.
        // - When the user has thinking enabled → map their level to the
        //   matching effort string.
        // - When off but using Codex OAuth → fall back to "low" because the
        //   ChatGPT backend rejects requests without a `reasoning` object.
        // - Else → omit the field so the upstream applies its own default.
        // [T-android-codex-thinking-summary] `summary: "auto"` opts in to
        // streaming the human-readable reasoning SUMMARY (delivered as
        // `response.reasoning_summary_text.delta` SSE events with non-empty
        // `delta`). Without it the Responses API / Codex backend returns ONLY
        // `encrypted_content` — the reasoning deltas arrive empty, so the
        // Thinking region never renders even though the model reasoned (token
        // usage shows it did). This was the Codex-OAuth "thinking on but UI
        // shows nothing" bug (XIN). Mirrors iOS OpenAIAgentProvider.swift:415
        // (`["effort": effort, "summary": "auto"]`). OpenAI ignores the variant
        // it doesn't support and falls back to an auto-equivalent, so it's safe
        // on every Responses-flavor endpoint.
        // [T-android-xhigh-effort-clamp] Also clamp on the Responses API path:
        // the reasoning.effort field is the same name/values as Chat Completions
        // and would send xhigh too. MiMo-2.5/Agnes normally use Chat Completions
        // (the reported 400/422), but a user could flip useResponsesAPI on, so
        // guard it here as well — only xhigh for those two families is affected.
        // [T-android-thinking-level-arch] Pre-clamped by LLMProvider — that
        // clamp is override-aware since the ULTRA-manual fix, so a manual-
        // ULTRA ceiling arrives here as ULTRA. [T-thinking-ultra-declared]
        // [T-thinking-ultra-manual] Second injection site outside the
        // resolver: shared rule — declared OR manually-asserted "ultra" gets
        // the literal; only vocabulary-less models fold to "max".
        val effort = if (thinkingLevel.isEnabled) {
            ThinkingEffort.effortOrDeclaredUltra(
                thinkingLevel,
                host.model.reasoningEffortValues,
                host.model.maxThinkingLevel,
            ).let { clampEffortForModel(it) }
        } else null
        when {
            // [T-android-mistral-reasoning-422] Mistral rejects the reasoning
            // request parameter outright (`422 extra_forbidden body.reasoning`,
            // GH OpenMinis#87). The gate added alongside injectThinkingParams
            // covers only the Chat Completions path; this builder is a SECOND,
            // independent injection site that a Mistral instance with
            // useResponsesAPI enabled reaches ungated. For Mistral the answer to
            // "should any thinking field be sent" is NEVER, on every request
            // path — so suppress the whole block. Must stay FIRST so it wins over
            // the isOAuth fallback below.
            host.isMistral -> {}
            effort != null -> body.put(
                "reasoning",
                JSONObject().put("effort", effort).put("summary", "auto"),
            )
            host.isOAuth -> body.put(
                "reasoning",
                JSONObject().put("effort", "low").put("summary", "auto"),
            )
            // [T-thinking-off-explicit] Thinking OFF on a reasoning-capable
            // model: send the explicit off tier instead of omitting `reasoning`
            // — omission lets the vendor default kick in. Same ALLOWLIST as the
            // Chat path (official OpenAI → "none", Volcano Ark → "minimal");
            // vendors with undocumented off semantics keep the historical
            // omission. No summary/include: nothing should stream back.
            // Mirrors iOS OpenAIAgentProvider ff60c818's Responses off branch.
            !thinkingLevel.isEnabled && host.model.supportsReasoning == true &&
                !host.model.id.lowercase().let { it.contains("mimo") || it.contains("agnes") } -> {
                host.explicitOffEffort()?.let { offEffort ->
                    body.put("reasoning", JSONObject().put("effort", offEffort))
                }
            }
        }

        // [T-responses-max-output-tokens] The builder received maxTokens but
        // never wrote it into the body, so Responses-flavor vendors fell back
        // to their (often tiny) defaults and truncated. Same guard as iOS
        // 637cd890/5f148144: maxTokens > 0, and Codex OAuth excluded — the
        // codex_cli_rs body shape is a client fingerprint and must not carry
        // fields the real CLI doesn't send.
        if (maxTokens > 0 && !host.isOAuth) {
            body.put("max_output_tokens", maxTokens)
        }

        // [T-codex-fast-mode] Fast tier injection (mirrors iOS fb671083 +
        // 838ba929). Wire value verified against openai/codex source
        // (codex-rs/protocol config_types.rs): ServiceTier::Fast sends
        // service_tier="priority" — "fast" is only the UI name, so this stays
        // inside the codex_cli_rs client fingerprint on the OAuth route. Gate
        // is toggle + gpt-family model only: this builder IS the Responses
        // path, and Responses relays (e.g. sub2api) normalize/pass the tier
        // through, so no isOAuth narrowing. Ineligible upstreams ignore the
        // field or silently downgrade (receipt visible via the
        // response.completed service_tier log).
        //
        // [T-android-xai-priority] Guarded on the key being absent so this
        // cannot clobber a per-instance tier set above. The two gates are
        // disjoint today (that toggle is xAI-only, this one gpt-only, and xAI
        // never reaches this builder), so the guard changes no current
        // behaviour — it just means neither feature can silently overwrite the
        // other if either's routing widens later. Both want the same value
        // anyway, so "first writer wins" loses nothing.
        if (!body.has("service_tier") &&
            com.openminis.app.data.FastModePrefs.isEnabled() &&
            host.model.id.contains("gpt", ignoreCase = true)
        ) {
            body.put("service_tier", "priority")
        }

        if (systemPrompt != null) {
            body.put("instructions", systemPrompt)
        }

        // Tools — flat shape required by Responses API ({type, name, description,
        // parameters}), distinct from Chat Completions' wrapped {type, function:{...}}.
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(tool.toResponsesAPIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        // Mirrors iOS convertMessagesResponsesAPI (OpenAIAgentProvider.swift:895):
        // structured content parts become typed input items — function_call /
        // function_call_output — instead of free-text role/content pairs.
        val input = buildResponsesInput(messages, imageParts, supportsImages)
        // [T-android-responses-orphan-tool-output] Last gate on the tool
        // pairing, over the ids this request actually carries (split on "|"
        // and capped). Without it a stranded or duplicated tool result 400s
        // every send until the chat is cleared. See ResponsesToolPairing.
        val paired = ResponsesToolPairing.sanitize(input)
        if (paired.changed) {
            AppLogger.warning(
                "OpenAIRequestBodies",
                "[sanitize-responses] repaired outgoing tool pairing: " +
                    "droppedOrphanOutputs=${paired.droppedOrphanOutputs} " +
                    "droppedDuplicateOutputs=${paired.droppedDuplicateOutputs} " +
                    "placeholderCalls=${paired.placeholderCalls} items=${input.length()}",
            )
        }
        body.put("input", paired.items)

        // [T-android-model-use-passthrough-mode GH#72] Same verbatim merge as the
        // chat-completions builder. Skipped for Codex OAuth inside mergeChatExtraBody.
        mergeChatExtraBody(body)

        // [T-codex-client-fingerprint] client_metadata 是 Responses API 的合法
        // 字段，中转站的引擎指纹校验会读 client_metadata.x-codex-window-id /
        // x-codex-installation-id。只在这一条路径注入：chat/completions 带未知
        // 顶层字段会被严格后端 400，而那边的身份已经由请求头承担。
        //
        // [T-client-disguise-app-level] 判定与请求头同源（都走
        // activeDisguiseClientId），且 body 里的身份必须和头里的同一套 ——
        // 两处用不同 identity 会立刻露馅。非 Codex 家族客户端同样跳过。
        activeDisguiseClientId()?.let { clientId ->
            if (DisguiseClients.codexProfileFor(clientId) != null) {
                CodexFingerprint.applyToBody(body, codexIdentityFor(clientId))
            }
        }

        return body
    }
}
