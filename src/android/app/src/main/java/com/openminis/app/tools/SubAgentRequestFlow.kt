package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.ProviderKeyGate
import com.openminis.app.provider.streamStallWatchdog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * [T-025] Queueing for the shared provider bucket is not a network stall.
 *
 * `LLMProvider.streamMessage` already takes [ProviderKeyGate] itself, but it
 * does so *inside* the flow, after `streamStallWatchdog` was attached by the
 * caller. A lane queued behind a sibling's stream would therefore spend that
 * sibling's remaining stream time on the first-event budget and be killed as a
 * "stall" before its own request even started.
 *
 * Taking the permit here, in the same coroutine, makes the provider's own
 * acquisition a reentrant no-op ([ProviderKeyGate.withPermit] tracks held keys
 * per coroutine), so the watchdog is only armed against a request that really
 * is on the wire. The permit is released when the block returns — before the
 * runner executes the turn's tools.
 */
internal fun subAgentRequestFlow(
    gateKey: String,
    firstTimeoutMs: Long,
    idleTimeoutMs: Long,
    firstContentTimeoutMs: Long = firstTimeoutMs,
    firstContentReason: String = "sub-agent stream connected but no first token " +
        "within ${firstTimeoutMs / 1000}s",
    request: () -> Flow<LLMStreamChunk>,
): Flow<LLMStreamChunk> = channelFlow {
    ProviderKeyGate.withPermit(gateKey) {
        request().streamStallWatchdog(
            firstTimeoutMs = firstTimeoutMs,
            idleTimeoutMs = idleTimeoutMs,
            firstContentTimeoutMs = firstContentTimeoutMs,
            firstContentReason = firstContentReason,
        ).collect { send(it) }
    }
}
