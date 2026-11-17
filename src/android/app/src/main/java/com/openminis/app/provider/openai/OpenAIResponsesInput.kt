package com.openminis.app.provider.openai

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-openai-request-bodies-split] The Responses-API `input` array builder —
 * the structured `function_call` / `function_call_output` / typed-content
 * transcoding loop — extracted verbatim from [OpenAIRequestBodies] when that
 * file outgrew its architecture-policy ceiling.
 *
 * Nothing here touches provider instance state: the three inputs plus the
 * vision gate are the whole contract, which is exactly why it lifts out
 * cleanly. The caller still owns `body`, tools, instructions and the final
 * [ResponsesToolPairing] gate.
 */
internal fun buildResponsesInput(
    messages: List<LLMMessage>,
    imageParts: List<LLMMessage.ImagePart>,
    supportsImages: Boolean,
): JSONArray {
    val input = JSONArray()
    // [T-android-responses-toplevel-images] Same contract as
    // buildRequestBody: top-level images ride on the LAST user message.
    val lastUserIdx = messages.indexOfLast { it.role == LLMMessage.Role.USER }
    for ((msgIndex, msg) in messages.withIndex()) {
        val attachTopLevelImages =
            msgIndex == lastUserIdx && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
        if (msg.contentParts.isNotEmpty()) {
            when (msg.role) {
                LLMMessage.Role.ASSISTANT -> {
                    val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                    if (textParts.isNotEmpty()) {
                        val text = textParts.joinToString("") { it.text }
                        if (text.isNotEmpty()) {
                            input.put(JSONObject().apply {
                                put("role", "assistant")
                                put("content", text)
                            })
                        }
                    }
                    for (tu in msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()) {
                        val (callId, fcId) = splitResponsesAPIIds(tu.id)
                        val safeCallId = capResponsesId(callId)
                        // Responses API requires both `id` (fc_…) and `call_id` (call_…).
                        // When the message was synthesized outside a Responses round-trip
                        // (e.g. injected from Chat Completions history) the fcId is null —
                        // generate a deterministic synthetic so the API still accepts it.
                        val safeFcId = fcId?.let { capResponsesId(it) }
                            ?: "fc_syn_${safeCallId.takeLast(24)}"
                        input.put(JSONObject().apply {
                            put("type", "function_call")
                            put("id", safeFcId)
                            put("call_id", safeCallId)
                            put("name", tu.name)
                            put("arguments", tu.input.toString())
                        })
                    }
                }
                LLMMessage.Role.USER -> {
                    for (tr in msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()) {
                        val (callId, _) = splitResponsesAPIIds(tr.id)
                        input.put(JSONObject().apply {
                            put("type", "function_call_output")
                            put("call_id", capResponsesId(callId))
                            put("output", tr.content)
                        })
                        // [T-android-toolresult-image-dropped] Same defect as the
                        // Chat Completions branch: function_call_output takes a
                        // string `output`, so read_image's pixels had nowhere to
                        // go and were silently dropped. Emit them as a following
                        // user turn carrying an input_image block.
                        val trBytes = tr.imageData
                        if (trBytes != null && trBytes.isNotEmpty() && supportsImages) {
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "input_text")
                                        put("text", "[Image returned by ${tr.name}]")
                                    })
                                    put(
                                        responsesImageBlock(
                                            trBytes,
                                            tr.imageMimeType ?: "image/jpeg",
                                            supportsImages,
                                            null,
                                        ),
                                    )
                                })
                            })
                        }
                    }
                    // T132: emit text + input_image content for the user
                    // turn so vision-capable Responses-API models actually
                    // see the bytes. Without the input_image branch the
                    // outer `content` was a flat concatenated string and
                    // image bytes never reached the wire (the textual
                    // [attached image: …] caption was the only hint, and
                    // the model fell back to read_image / shell_execute
                    // groping for a path it could see). Mirrors iOS
                    // convertMessagesResponsesAPI's image handling.
                    val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                    val imgParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()
                    if (imgParts.isNotEmpty()) {
                        val contentArray = JSONArray()
                        for (part in msg.contentParts) {
                            when (part) {
                                is AgentContentPart.Text -> {
                                    if (part.text.isNotEmpty()) {
                                        contentArray.put(JSONObject().apply {
                                            put("type", "input_text")
                                            put("text", part.text)
                                        })
                                    }
                                }
                                is AgentContentPart.ImageData -> {
                                    if (supportsImages) {
                                        // T-imgsize: backstop for Responses API path.
                                        val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(part.data)
                                        val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                                        val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                                        contentArray.put(JSONObject().apply {
                                            put("type", "input_image")
                                            // Responses API takes image_url
                                            // as a *string*, not the
                                            // {"url":...} object shape used
                                            // by Chat Completions.
                                            put("image_url", "data:$safeMime;base64,$b64")
                                        })
                                    } else {
                                        // T264: target model has no vision modality —
                                        // emit a text placeholder. [T-android-vision-group
                                        // / GH#182] Vision-Group read_image hint when
                                        // seeded (carries the path); else the historical
                                        // literal. Note: Responses API uses "input_text"
                                        // type (vs "text" on Chat Completions).
                                        contentArray.put(JSONObject().apply {
                                            put("type", "input_text")
                                            put("text", part.noVisionPlaceholder
                                                ?: "[Image attached but this model does not support vision input]")
                                        })
                                    }
                                }
                                // [T-android-responses-toplevel-images]
                                // ToolUse/ToolResult are handled above for
                                // this role and legitimately don't belong
                                // in the content array. Anything else is a
                                // part type this converter has never been
                                // taught to encode — the failure mode being
                                // fixed here (content dropped with no error
                                // and no log) is exactly what that produces,
                                // so make it visible rather than silent.
                                is AgentContentPart.ToolUse,
                                is AgentContentPart.ToolResult -> Unit
                                else -> com.openminis.app.logging.AppLogger.error(
                                    "OpenAIProvider",
                                    "[responses] DROPPED unconvertible content part " +
                                        "${part.javaClass.simpleName} on role=user — it will NOT " +
                                        "reach the model. Add an encoding branch for it.",
                                )
                            }
                        }
                        // [T-android-responses-toplevel-images] Top-level
                        // images belong on this same turn.
                        if (attachTopLevelImages) {
                            for (p in imageParts) {
                                contentArray.put(
                                    responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                                )
                            }
                        }
                        input.put(JSONObject().apply {
                            put("role", "user")
                            put("content", contentArray)
                        })
                    } else if (attachTopLevelImages) {
                        // [T-android-responses-toplevel-images] Structured
                        // message with no ImageData parts, but images were
                        // supplied top-level (minis-model-use / Vision
                        // Group). Previously this fell into the text-only
                        // branch below and the pixels vanished.
                        val contentArray = JSONArray()
                        val text = textParts.joinToString("") { it.text }
                        if (text.isNotEmpty()) {
                            contentArray.put(JSONObject().apply {
                                put("type", "input_text")
                                put("text", text)
                            })
                        }
                        for (p in imageParts) {
                            contentArray.put(
                                responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                            )
                        }
                        input.put(JSONObject().apply {
                            put("role", "user")
                            put("content", contentArray)
                        })
                    } else if (textParts.isNotEmpty()) {
                        input.put(JSONObject().apply {
                            put("role", "user")
                            put("content", textParts.joinToString("") { it.text })
                        })
                    }
                }
                else -> {
                    input.put(JSONObject().apply {
                        put("role", msg.role.value)
                        put("content", msg.content)
                    })
                }
            }
        } else if (msg.audioParts.isNotEmpty()) {
            // [GH#67] Legacy (non-contentParts) message carrying audio —
            // the minis-model-use path. The Responses API keeps the SAME
            // nested input_audio shape as Chat Completions ({data,
            // format}), unlike input_image which flattens image_url to a
            // string. Text rides along as input_text.
            val contentArray = JSONArray()
            for (audio in msg.audioParts) {
                contentArray.put(JSONObject().apply {
                    put("type", "input_audio")
                    put("input_audio", JSONObject().apply {
                        put("data", audio.base64Data)
                        put("format", audio.format)
                    })
                })
            }
            if (msg.content.isNotEmpty()) {
                contentArray.put(JSONObject().apply {
                    put("type", "input_text")
                    put("text", msg.content)
                })
            }
            // [T-android-responses-toplevel-images] An audio-carrying turn
            // can also carry images.
            if (attachTopLevelImages) {
                for (p in imageParts) {
                    contentArray.put(
                        responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                    )
                }
            }
            input.put(JSONObject().apply {
                put("role", msg.role.value)
                put("content", contentArray)
            })
        } else if (attachTopLevelImages) {
            // [T-android-responses-toplevel-images] THE reported bug's path.
            // A plain (contentParts-free) user message plus top-level
            // images — what VisionGroupResolver.describeOnce and
            // minis-model-use's image_url blocks produce. This builder had
            // no imageParts parameter at all, so the message was emitted as
            // a bare text string and the pixels never reached the wire. The
            // vision model then answered "no image was provided", with no
            // error anywhere to explain it.
            val contentArray = JSONArray()
            if (msg.content.isNotEmpty()) {
                contentArray.put(JSONObject().apply {
                    put("type", "input_text")
                    put("text", msg.content)
                })
            }
            for (p in imageParts) {
                contentArray.put(
                    responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                )
            }
            input.put(JSONObject().apply {
                put("role", msg.role.value)
                put("content", contentArray)
            })
        } else {
            input.put(JSONObject().apply {
                put("role", msg.role.value)
                put("content", msg.content)
            })
        }
    }
    return input
}

/**
 * [T-android-responses-toplevel-images] Encode one image as a Responses-API
 * content block, or as the no-vision text placeholder when the target model
 * cannot see pixels.
 *
 * Extracted so the two Responses call sites (structured `contentParts`
 * messages and legacy top-level `imageParts` messages) cannot drift apart —
 * the drift is exactly what produced the silent drop this fixes. Note the
 * Responses shape differs from Chat Completions: `image_url` is a bare
 * STRING here, not a `{"url": …}` object.
 */
/**
 * [T-android-responses-toplevel-images] Encode one image as a Responses-API
 * content block, or as the no-vision text placeholder when the target model
 * cannot see pixels.
 *
 * Extracted so the two Responses call sites (structured `contentParts`
 * messages and legacy top-level `imageParts` messages) cannot drift apart —
 * the drift is exactly what produced the silent drop this fixes. Note the
 * Responses shape differs from Chat Completions: `image_url` is a bare
 * STRING here, not a `{"url": …}` object.
 */
private fun responsesImageBlock(
    data: ByteArray,
    mimeType: String,
    supportsImages: Boolean,
    noVisionPlaceholder: String?,
): JSONObject = if (supportsImages) {
    // T-imgsize: provider-boundary backstop.
    val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(data)
    val safeMime = if (safeBytes === data) mimeType else "image/jpeg"
    val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
    JSONObject().apply {
        put("type", "input_image")
        put("image_url", "data:$safeMime;base64,$b64")
    }
} else {
    JSONObject().apply {
        put("type", "input_text")
        put(
            "text",
            noVisionPlaceholder
                ?: "[Image attached but this model does not support vision input]",
        )
    }
}
