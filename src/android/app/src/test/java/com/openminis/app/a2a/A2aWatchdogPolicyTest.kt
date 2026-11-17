package com.openminis.app.a2a

import org.junit.Assert.*
import org.junit.Test

class A2aWatchdogPolicyTest {
    @Test fun transientFailureDoesNotRestartGateway() {
        val policy = A2aWatchdogPolicy()
        policy.failed()
        assertFalse(policy.shouldRespawn(true))
        assertEquals(30_000L, policy.delayMs)
    }

    @Test fun repeatedFailuresRestartOnlyADeadProcess() {
        val policy = A2aWatchdogPolicy()
        policy.failed()
        policy.failed()
        assertFalse(policy.shouldRespawn(false))
        assertTrue(policy.shouldRespawn(true))
        assertEquals(60_000L, policy.delayMs)
    }

    @Test fun failedRestartKeepsBackingOffToFiveMinutes() {
        val policy = A2aWatchdogPolicy()
        val delays = mutableListOf<Long>()
        repeat(7) {
            policy.failed()
            delays += policy.delayMs
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L), delays)
        repeat(100) { policy.failed() }
        assertEquals(300_000L, policy.delayMs)
        assertTrue(policy.shouldRespawn(true))
    }

    @Test fun recoveryClearsFailureHistory() {
        val policy = A2aWatchdogPolicy()
        repeat(6) { policy.failed() }
        policy.healthy()
        assertEquals(0, policy.failures)
        assertEquals(15_000L, policy.delayMs)
        assertFalse(policy.shouldRespawn(true))
        policy.failed()
        assertEquals(30_000L, policy.delayMs)
    }
}
