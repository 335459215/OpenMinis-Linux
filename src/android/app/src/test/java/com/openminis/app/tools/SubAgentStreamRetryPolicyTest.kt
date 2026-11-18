package com.openminis.app.tools

import com.openminis.app.data.model.LLMError
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-025] The request-level retry policy: what may be retried inside a single
 * lane request, and how long to wait before it.
 */
class SubAgentStreamRetryPolicyTest {

    @Test
    fun networkFailuresAreRetryable() {
        assertTrue(SubAgentStreamRetryPolicy.retryable(IOException("connection reset")))
        assertTrue(SubAgentStreamRetryPolicy.retryable(SocketTimeoutException("read timed out")))
        assertTrue(SubAgentStreamRetryPolicy.retryable(LLMError.NetworkError(IOException("refused"))))
        assertTrue(SubAgentStreamRetryPolicy.retryable(LLMError.Timeout("ttfb")))
        assertTrue(SubAgentStreamRetryPolicy.retryable(LLMError.TransientError("502")))
        assertTrue(SubAgentStreamRetryPolicy.retryable(LLMError.RateLimited(5)))
    }

    @Test
    fun fatalFailuresAreNotRetryable() {
        assertFalse(SubAgentStreamRetryPolicy.retryable(LLMError.InvalidApiKey("bad key")))
        assertFalse(SubAgentStreamRetryPolicy.retryable(LLMError.ContextLengthExceeded("too long")))
        assertFalse(SubAgentStreamRetryPolicy.retryable(LLMError.ContentFiltered("policy")))
        assertFalse(SubAgentStreamRetryPolicy.retryable(LLMError.DecodingError(IOException("json"))))
        assertFalse(SubAgentStreamRetryPolicy.retryable(IllegalStateException("bug")))
    }

    /** A stop must abort the request, never schedule a retry. */
    @Test
    fun cancellationIsNeverRetried() {
        assertFalse(SubAgentStreamRetryPolicy.retryable(CancellationException("stopped")))
        assertFalse(SubAgentStreamRetryPolicy.retryable(LLMError.Cancelled()))
    }

    /** A non-LLM exception wrapping a dropped socket still counts. */
    @Test
    fun wrappedIoIsRetryable() {
        assertTrue(SubAgentStreamRetryPolicy.retryable(RuntimeException("eof", IOException("reset"))))
        assertFalse(SubAgentStreamRetryPolicy.retryable(RuntimeException("eof", IllegalStateException("x"))))
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        assertEquals(1_000L, SubAgentStreamRetryPolicy.delayMs(IOException("x"), 1_000L, 1))
        assertEquals(2_000L, SubAgentStreamRetryPolicy.delayMs(IOException("x"), 1_000L, 2))
        assertEquals(4_000L, SubAgentStreamRetryPolicy.delayMs(IOException("x"), 1_000L, 3))
        // Coerced shift: a long retry streak cannot overflow into a negative wait.
        assertEquals(8_000L, SubAgentStreamRetryPolicy.delayMs(IOException("x"), 1_000L, 99))
        // Base is clamped at 250ms…
        assertEquals(250L, SubAgentStreamRetryPolicy.delayMs(IOException("x"), 1L, 1))
        // …and grows at most ×8 before the five-minute ceiling applies.
        assertEquals(240_000L, SubAgentStreamRetryPolicy.delayMs(IOException("x"), 60_000L, 3))
        assertEquals(300_000L, SubAgentStreamRetryPolicy.delayMs(IOException("x"), 60_000L, 99))
    }

    @Test
    fun retryAfterWinsOverBackoff() {
        assertEquals(
            30_000L,
            SubAgentStreamRetryPolicy.delayMs(LLMError.RateLimited(retryAfterSeconds = 30), 1_000L, 1),
        )
        // …and a longer local backoff still wins over a short Retry-After.
        assertEquals(
            5_000L,
            SubAgentStreamRetryPolicy.delayMs(LLMError.RateLimited(retryAfterSeconds = 2), 5_000L, 1),
        )
        // …and an absurd Retry-After is bounded.
        assertEquals(
            300_000L,
            SubAgentStreamRetryPolicy.delayMs(LLMError.RateLimited(retryAfterSeconds = 86_400), 1_000L, 1),
        )
        // Negative/garbage values must not produce a negative delay.
        assertEquals(1_000L, SubAgentStreamRetryPolicy.delayMs(LLMError.RateLimited(-5), 1_000L, 1))
    }
}
