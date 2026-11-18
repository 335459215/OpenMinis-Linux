package com.openminis.app.tools

import com.openminis.app.data.model.LLMError
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Request retries never restart a lane or re-execute its completed tools. */
internal object SubAgentStreamRetryPolicy {
    fun retryable(error: Exception): Boolean = when (error) {
        is CancellationException -> false
        is LLMError -> error.isRetryable
        is IOException -> true
        else -> error.cause is IOException
    }

    fun delayMs(error: Exception, baseMs: Long, failedAttempt: Int): Long {
        val server = (error as? LLMError.RateLimited)?.retryAfterSeconds
            ?.coerceAtLeast(0)?.toLong()?.times(1_000L) ?: 0L
        val backoff = baseMs.coerceIn(250L, 60_000L) * (1L shl (failedAttempt - 1).coerceIn(0, 3))
        return maxOf(server, backoff).coerceAtMost(300_000L)
    }
}
