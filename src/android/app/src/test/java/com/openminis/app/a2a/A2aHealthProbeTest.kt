package com.openminis.app.a2a

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class A2aHealthProbeTest {
    private class Connection(private val code: Int = 200, private val failure: IOException? = null) :
        HttpURLConnection(URL("http://127.0.0.1/health")) {
        var closed = false
        override fun getResponseCode(): Int {
            failure?.let { throw it }
            return code
        }
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }

    @Test fun successfulProbeClosesConnectionAndBoundsTimeouts() {
        val connection = Connection(204)
        assertTrue(A2aHealthProbe.check("http://127.0.0.1/health") { connection })
        assertTrue(connection.closed)
        assertEquals(3_000, connection.connectTimeout)
        assertEquals(3_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
    }

    @Test fun failedProbeClosesConnection() {
        val connection = Connection(503)
        assertFalse(A2aHealthProbe.check("http://127.0.0.1/health") { connection })
        assertTrue(connection.closed)
    }

    @Test fun timedOutProbeClosesConnection() {
        val connection = Connection(failure = SocketTimeoutException("slow health"))
        assertFalse(A2aHealthProbe.check("http://127.0.0.1/health") { connection })
        assertTrue(connection.closed)
    }

    @Test fun refusedConnectionIsUnhealthy() {
        assertFalse(A2aHealthProbe.check("http://127.0.0.1/health") { throw IOException("refused") })
    }

    @Test fun redirectDoesNotCountAsAHealthyLocalGateway() {
        val connection = Connection(302)
        assertFalse(A2aHealthProbe.check("http://127.0.0.1/health") { connection })
        assertTrue(connection.closed)
    }

    @Test fun repeatedFailedProbesReleaseEveryConnection() {
        repeat(50) {
            val connection = Connection(failure = SocketTimeoutException())
            assertFalse(A2aHealthProbe.check("http://127.0.0.1/health") { connection })
            assertTrue(connection.closed)
        }
    }
}
