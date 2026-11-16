package com.openminis.app.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.util.UUID

/**
 * [T-codex-client-fingerprint] 指纹生成器的契约测试。
 *
 * 断言全部对应真实源码里的硬约束，不是"跑起来不崩"这种弱检查：
 *  - UUID 版本位（installation v4 / session v7）—— 版本位错了本身就是特征
 *  - UA 前缀 == originator、UA 内版本 == Version 头（三元一致）
 *  - x-codex-app-version 必须存在（中转站按它判定官方客户端，缺了 403）
 *  - installation_id 只在 body，不作为独立请求头
 *  - 同 seed 稳定、turn_id 每轮不同
 */
class CodexFingerprintTest {

    @Test
    fun `installation id is uuid v4`() {
        val id = CodexFingerprint.newIdentity("seed-a")
        assertEquals(4, UUID.fromString(id.installationId).version())
    }

    @Test
    fun `session and thread ids are uuid v7`() {
        val id = CodexFingerprint.newIdentity("seed-a")
        assertEquals(7, UUID.fromString(id.sessionId).version())
        assertEquals(7, UUID.fromString(id.contextWindowId).version())
        // thread 与 session 同源，window 是 thread 加后缀
        assertEquals(id.sessionId, id.threadId)
        assertEquals("${id.threadId}:0", id.windowId)
    }

    @Test
    fun `same seed derives stable identity but fresh turn id`() {
        val a = CodexFingerprint.newIdentity("stable-seed")
        val b = CodexFingerprint.newIdentity("stable-seed")
        assertEquals(a.installationId, b.installationId)
        assertEquals(a.sessionId, b.sessionId)
        assertEquals(a.contextWindowId, b.contextWindowId)
        // turn_id 逐轮新建，真实客户端也是 now_v7
        assertNotEquals(a.turnId, b.turnId)
    }

    @Test
    fun `different seeds diverge`() {
        val a = CodexFingerprint.newIdentity("seed-1")
        val b = CodexFingerprint.newIdentity("seed-2")
        assertNotEquals(a.installationId, b.installationId)
        assertNotEquals(a.sessionId, b.sessionId)
    }

    @Test
    fun `user agent prefix equals originator and carries the version`() {
        val profile = CodexFingerprint.CLI_MACOS_ARM64
        val ua = profile.userAgent("0.169.0")
        assertTrue(ua.startsWith(profile.originator + "/"))
        assertTrue(ua.contains("/0.169.0 "))
        // 末尾标记 (app名; 版本) 与形态匹配
        assertTrue(ua.endsWith("(${profile.appName}; 0.169.0)"))
    }

    @Test
    fun `headers carry every field a relay checks`() {
        val id = CodexFingerprint.newIdentity("hdr-seed")
        val pairs = CodexFingerprint.headers(id, version = "0.169.0")
        val map = pairs.toMap()

        assertEquals(CodexFingerprint.DEFAULT_PROFILE.originator, map["originator"])
        assertEquals("0.169.0", map["Version"])
        // 中转站按这个头判定官方客户端；缺了直接 403 forbidden_error
        assertEquals("0.169.0", map["x-codex-app-version"])
        assertEquals(id.sessionId, map["session-id"])
        assertEquals(id.threadId, map["thread-id"])
        assertEquals(id.threadId, map["x-client-request-id"])
        assertEquals(id.windowId, map["x-codex-window-id"])
        assertEquals("responses=experimental", map["OpenAI-Beta"])
        // 中转的 OpenAI 兼容层两者都要
        assertTrue(map["Accept"]!!.contains("application/json"))
        assertTrue(map["Accept"]!!.contains("text/event-stream"))

        // installation_id 只进 body，绝不作为独立头出现
        assertFalse(map.keys.any { it.equals("x-codex-installation-id", ignoreCase = true) })
    }

    @Test
    fun `header order puts app version before turn metadata`() {
        val pairs = CodexFingerprint.headers(CodexFingerprint.newIdentity("order-seed"))
        val names = pairs.map { it.first }
        assertTrue(names.indexOf("x-codex-app-version") < names.indexOf("x-codex-turn-metadata"))
        assertTrue(names.indexOf("originator") < names.indexOf("User-Agent"))
        assertTrue(names.indexOf("session-id") < names.indexOf("x-codex-window-id"))
    }

    @Test
    fun `turn metadata is valid json with all required keys`() {
        val id = CodexFingerprint.newIdentity("md-seed")
        val md = JSONObject(id.turnMetadataJson())
        for (key in listOf(
            "installation_id", "session_id", "thread_id", "turn_id",
            "window_id", "turn_started_at_unix_ms", "context_window_id",
        )) {
            assertTrue("missing $key", md.has(key))
        }
        assertEquals(id.installationId, md.getString("installation_id"))
        assertEquals(id.turnId, md.getString("turn_id"))
    }

    @Test
    fun `body gets client metadata without clobbering caller keys`() {
        val id = CodexFingerprint.newIdentity("body-seed")
        val body = JSONObject().put("model", "gpt-5-codex")
        CodexFingerprint.applyToBody(body, id)

        val cm = body.getJSONObject("client_metadata")
        assertEquals(id.sessionId, cm.getString("session_id"))
        assertEquals(id.threadId, cm.getString("thread_id"))
        assertEquals(id.turnId, cm.getString("turn_id"))
        // 真实 CLI 只把它放在这里
        assertEquals(id.installationId, cm.getString("x-codex-installation-id"))
        assertTrue(cm.has("x-codex-turn-metadata"))
        // 其它字段不动
        assertEquals("gpt-5-codex", body.getString("model"))

        // 再调一次不覆盖调用方已写好的值
        val custom = JSONObject().put("model", "m").put(
            "client_metadata", JSONObject().put("session_id", "keep-me"),
        )
        CodexFingerprint.applyToBody(custom, id)
        assertEquals("keep-me", custom.getJSONObject("client_metadata").getString("session_id"))
    }

    @Test
    fun `seed is stable per instance and model`() {
        val a = CodexFingerprint.seedFor("inst-1", "gpt-5-codex")
        val b = CodexFingerprint.seedFor("inst-1", "gpt-5-codex")
        val c = CodexFingerprint.seedFor("inst-2", "gpt-5-codex")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
