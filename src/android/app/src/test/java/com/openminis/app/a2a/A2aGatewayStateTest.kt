package com.openminis.app.a2a

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Field
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class A2aGatewayStateTest {
    private val singleton: Any = A2aGateway::class.java
        .getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)!!
    private val processField: Field = A2aGateway::class.java
        .getDeclaredField("process").apply { isAccessible = true }
    private val probeField: Field = A2aGateway::class.java
        .getDeclaredField("probeOk").apply { isAccessible = true }
    private var savedProcess: Any? = null
    private var savedProbe = false
    private var savedStop = false

    @Before fun prepare() {
        savedProcess = processField.get(singleton)
        savedProbe = probeField.getBoolean(singleton)
        savedStop = A2aGateway.stopRequested
        processField.set(singleton, null)
        probeField.setBoolean(singleton, false)
        A2aGateway.stopRequested = false
    }

    @After fun restore() {
        processField.set(singleton, savedProcess)
        probeField.setBoolean(singleton, savedProbe)
        A2aGateway.stopRequested = savedStop
    }

    @Test fun deadHandleOverridesPreviouslyHealthyProbe() {
        val process = FakeProcess(alive = false)
        processField.set(singleton, process)
        probeField.setBoolean(singleton, true)
        assertFalse(A2aGateway.isRunningFast())
        assertTrue(A2aGateway.canRespawn())
        assertTrue(A2aGateway.dropDeadHandle())
        assertFalse(A2aGateway.isRunningFast())
        assertNull(processField.get(singleton))
    }

    @Test fun liveHandleCannotBeDroppedOrRespawned() {
        val process = FakeProcess(alive = true)
        processField.set(singleton, process)
        assertFalse(A2aGateway.dropDeadHandle())
        assertFalse(A2aGateway.canRespawn())
        assertSame(process, processField.get(singleton))
    }

    @Test fun automaticRecoveryDoesNotOverrideStopIntent() {
        A2aGateway.stopRequested = true
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = error("stopped gateway must not read preferences")
        }
        val result = A2aGateway.ensureStarted(context)
        assertTrue(result.isFailure)
    }

    @Test fun slowHealthOnALiveProcessDoesNotStartAnotherProcess() {
        val process = FakeProcess(alive = true)
        processField.set(singleton, process)
        val context = wantedContext(wanted = true)
        assertTrue(A2aGateway.ensureStarted(context) { false }.isFailure)
        assertSame(process, processField.get(singleton))
    }

    @Test fun persistedStopIntentPreventsAutomaticProbe() {
        val context = wantedContext(wanted = false)
        val result = A2aGateway.ensureStarted(context) { error("must not probe a stopped gateway") }
        assertTrue(result.isFailure)
    }

    private fun wantedContext(wanted: Boolean): Context {
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            if (method.name == "getBoolean") wanted else error("unexpected preferences call: ${method.name}")
        } as SharedPreferences
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
            override fun getFilesDir(): java.io.File = error("must not deploy files or spawn a second process")
        }
    }

    private class FakeProcess(private val alive: Boolean) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() = Unit
        override fun isAlive(): Boolean = alive
    }
}
