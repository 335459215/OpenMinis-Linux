package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-client-disguise-app-level] App 级「客户端伪装」配置。
 *
 * 与 [com.openminis.app.data.model.ProviderInstance.codexClientFingerprint]
 * 的关系：那个是单渠道的独立开关（渠道详情页里开），这里是 App 级总控。
 * 两者是 OR 关系 —— 任一开启即生效。App 级的好处是：总开关一关，所有渠道
 * 立刻恢复原样，不用逐条回详情页关。
 *
 * 三个层次，对应设置页「客户端伪装」板块的三块 UI：
 *
 *   1. masterEnabled      总开关。关掉时整块配置保留但全部不生效。
 *   2. enabledClients     多选：哪些客户端可以拿来伪装。没勾的不出现在
 *                         下面的「客户端」抽屉里。
 *   3. channelBindings    渠道 → 客户端 的绑定。一个渠道只能绑一个客户端
 *                         （map 的 key 唯一，天然互斥）；一个客户端可以绑
 *                         多个渠道。
 *
 * 请求层没有 Context，所以沿用 [FastModePrefs] 的做法：启动时 [prime]
 * 把配置读进内存缓存，之后 [isEnabled] / [clientForChannel] 这些无参调用
 * 都直接读缓存。改配置时同时更新缓存与磁盘，下一次请求立刻生效。
 */
object ClientDisguisePrefs {

    private const val PREFS = "minis_client_disguise_prefs"
    private const val KEY_ENABLED = "disguiseEnabled"
    private const val KEY_CLIENTS = "enabledClients"
    private const val KEY_BINDINGS = "channelBindings"

    /** 绑定表的磁盘格式：`channelId=clientId` 用 `\n` 分隔。 */
    private const val BINDING_SEP = "\n"
    private const val BINDING_KV = "="

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedEnabled: Boolean = false

    @Volatile
    private var cachedClients: Set<String> = emptySet()

    @Volatile
    private var cachedBindings: Map<String, String> = emptyMap()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 启动时抓 context 并预热缓存。在 MinisApp.onCreate 里调用一次。 */
    fun prime(context: Context) {
        appContext = context.applicationContext
        val p = prefs(context)
        cachedEnabled = p.getBoolean(KEY_ENABLED, false)
        cachedClients = p.getStringSet(KEY_CLIENTS, emptySet())?.toSet() ?: emptySet()
        cachedBindings = parseBindings(p.getString(KEY_BINDINGS, ""))
    }

    // ── 总开关 ──────────────────────────────────────────────────────────────

    fun isEnabled(): Boolean = cachedEnabled

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // ── 多选：可用客户端 ────────────────────────────────────────────────────

    fun enabledClients(): Set<String> = cachedClients

    fun setEnabledClients(context: Context, ids: Set<String>) {
        cachedClients = ids.toSet()
        prefs(context).edit().putStringSet(KEY_CLIENTS, ids).apply()
    }

    fun toggleClient(context: Context, id: String, on: Boolean) {
        val next = cachedClients.toMutableSet()
        if (on) next.add(id) else next.remove(id)
        setEnabledClients(context, next)
    }

    // ── 渠道 ↔ 客户端 绑定（互斥）────────────────────────────────────────────

    /**
     * 该渠道当前绑定的客户端 id；null 表示没绑。
     *
     * 请求层的唯一入口：无 Context，读缓存。渠道绑定了且总开关开着，
     * 这个渠道的出站请求就会带上对应客户端身份。
     */
    fun clientForChannel(channelId: String): String? {
        if (!cachedEnabled) return null
        return cachedBindings[channelId]
    }

    /** 不判总开关的原始绑定，UI 画勾要用它（总开关关着时也要显示保留的配置）。 */
    fun rawBindingForChannel(channelId: String): String? = cachedBindings[channelId]

    fun bindings(): Map<String, String> = cachedBindings

    /**
     * 把渠道绑到某个客户端。
     *
     * 互斥在数据结构层就成立：key 是 channelId，一个渠道只可能有一个值。
     * 如果这个渠道原先绑的是别的客户端，这里直接覆盖 —— 那就是「每个渠道
     * 只能伪装一种」。
     */
    fun bindChannel(context: Context, channelId: String, clientId: String) {
        val next = cachedBindings.toMutableMap()
        next[channelId] = clientId
        persistBindings(context, next)
    }

    /** 解绑一个渠道。 */
    fun unbindChannel(context: Context, channelId: String) {
        val next = cachedBindings.toMutableMap()
        next.remove(channelId)
        persistBindings(context, next)
    }

    /**
     * 切换某渠道的绑定：已绑到该客户端则解绑，否则改绑。
     * UI 的「选中就是打勾」点一下就走这里。
     */
    fun toggleChannelBinding(context: Context, channelId: String, clientId: String) {
        if (cachedBindings[channelId] == clientId) {
            unbindChannel(context, channelId)
        } else {
            bindChannel(context, channelId, clientId)
        }
    }

    /** 该客户端名下已绑定的渠道。 */
    fun channelsForClient(clientId: String): List<String> =
        cachedBindings.filterValues { it == clientId }.keys.toList()

    /** 清理已经不存在的渠道 / 已取消勾选的客户端，避免配置越积越脏。 */
    fun prune(context: Context, liveChannelIds: Set<String>, liveClientIds: Set<String>) {
        val next = cachedBindings.filter { (ch, cli) ->
            ch in liveChannelIds && cli in liveClientIds
        }
        if (next != cachedBindings) persistBindings(context, next)
    }

    private fun persistBindings(context: Context, next: Map<String, String>) {
        cachedBindings = next
        prefs(context).edit().putString(KEY_BINDINGS, encodeBindings(next)).apply()
    }

    // ── 序列化 ──────────────────────────────────────────────────────────────

    private fun encodeBindings(map: Map<String, String>): String =
        map.entries.joinToString(BINDING_SEP) { "${it.key}$BINDING_KV${it.value}" }

    private fun parseBindings(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(BINDING_SEP).mapNotNull { line ->
            val i = line.indexOf(BINDING_KV)
            if (i <= 0 || i == line.length - 1) null
            else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
    }

    /**
     * [T-client-disguise-app-level] 只写内存缓存，不落盘。
     *
     * 供 JVM 单元测试驱动真实的 [clientForChannel] 路径：这个模块没有
     * Robolectric，带 Context 的写入在纯 JVM 测试里用不了，而对着假实现断言
     * 等于测假实现。生产代码不要调。
     */
    @androidx.annotation.VisibleForTesting
    internal fun setCacheForTest(
        enabled: Boolean,
        clients: Set<String> = emptySet(),
        bindings: Map<String, String> = emptyMap(),
    ) {
        cachedEnabled = enabled
        cachedClients = clients
        cachedBindings = bindings
    }
}
