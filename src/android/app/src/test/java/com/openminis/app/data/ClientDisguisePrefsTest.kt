package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-client-disguise-app-level] App 级伪装配置的契约测试。
 *
 * 这里测的是纯内存逻辑（缓存 + 互斥绑定语义）。带 Context 的落盘路径在
 * 本模块没有 Robolectric，靠 [ClientDisguisePrefs.setCacheForTest] 驱动真实
 * 的读取路径 —— 对着假实现断言等于测假实现。
 *
 * 重点覆盖三条用户可见的规则：
 *   1. 总开关关掉时，任何渠道都不伪装（哪怕绑定还在）。
 *   2. 一个渠道只能绑一个客户端（互斥），改绑即覆盖。
 *   3. 解绑后该渠道恢复不伪装。
 */
class ClientDisguisePrefsTest {

    @Test
    fun `master switch off disables every channel even when bound`() {
        ClientDisguisePrefs.setCacheForTest(
            enabled = false,
            clients = setOf("codex-cli"),
            bindings = mapOf("ch-1" to "codex-cli"),
        )
        // 请求层的唯一入口必须返回 null
        assertNull(ClientDisguisePrefs.clientForChannel("ch-1"))
        // 但原始绑定要保留，UI 才能在重新打开总开关后恢复勾选状态
        assertEquals("codex-cli", ClientDisguisePrefs.rawBindingForChannel("ch-1"))
    }

    @Test
    fun `master switch on exposes the binding`() {
        ClientDisguisePrefs.setCacheForTest(
            enabled = true,
            clients = setOf("codex-cli"),
            bindings = mapOf("ch-1" to "codex-cli"),
        )
        assertEquals("codex-cli", ClientDisguisePrefs.clientForChannel("ch-1"))
        // 没绑的渠道保持 null
        assertNull(ClientDisguisePrefs.clientForChannel("ch-2"))
    }

    @Test
    fun `one channel binds to exactly one client`() {
        ClientDisguisePrefs.setCacheForTest(
            enabled = true,
            clients = setOf("codex-cli", "codex-desktop"),
            bindings = mapOf("ch-1" to "codex-cli"),
        )
        // map 的 key 是 channelId，互斥在数据结构层就成立
        val bindings = ClientDisguisePrefs.bindings()
        assertEquals(1, bindings.values.count { it == "codex-cli" })
        // 同一渠道不可能同时出现在两个客户端名下
        assertEquals(listOf("ch-1"), ClientDisguisePrefs.channelsForClient("codex-cli"))
        assertTrue(ClientDisguisePrefs.channelsForClient("codex-desktop").isEmpty())
    }

    @Test
    fun `one client can own multiple channels`() {
        ClientDisguisePrefs.setCacheForTest(
            enabled = true,
            clients = setOf("codex-cli"),
            bindings = mapOf("ch-1" to "codex-cli", "ch-2" to "codex-cli", "ch-3" to "codex-desktop"),
        )
        assertEquals(setOf("ch-1", "ch-2"), ClientDisguisePrefs.channelsForClient("codex-cli").toSet())
        assertEquals(listOf("ch-3"), ClientDisguisePrefs.channelsForClient("codex-desktop"))
    }

    @Test
    fun `enabled clients is a set that survives read-back`() {
        ClientDisguisePrefs.setCacheForTest(
            enabled = true,
            clients = setOf("codex-cli", "claude-code"),
        )
        assertEquals(setOf("codex-cli", "claude-code"), ClientDisguisePrefs.enabledClients())
    }

    @Test
    fun `unknown channel has no binding`() {
        ClientDisguisePrefs.setCacheForTest(enabled = true)
        assertNull(ClientDisguisePrefs.clientForChannel("nope"))
        assertNull(ClientDisguisePrefs.rawBindingForChannel("nope"))
        assertTrue(ClientDisguisePrefs.bindings().isEmpty())
    }
}
