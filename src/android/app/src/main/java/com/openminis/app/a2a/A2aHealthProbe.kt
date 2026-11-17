package com.openminis.app.a2a

import java.net.HttpURLConnection
import java.net.URL

/** A failed probe must release its socket as reliably as a successful one. */
internal object A2aHealthProbe {
    fun check(
        address: String,
        open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    ): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = open(URL(address))
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.instanceFollowRedirects = false
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}

/** State is advanced only by completed probes, never by the scheduling thread. */
internal class A2aWatchdogPolicy {
    var failures: Int = 0
        private set
    var delayMs: Long = 15_000L
        private set

    fun healthy() {
        failures = 0
        delayMs = 15_000L
    }

    fun failed() {
        failures = (failures + 1).coerceAtMost(32)
        delayMs = (30_000L * (1L shl (failures - 1).coerceAtMost(4)))
            .coerceAtMost(300_000L)
    }

    fun shouldRespawn(canRespawn: Boolean): Boolean = failures >= 2 && canRespawn
}
