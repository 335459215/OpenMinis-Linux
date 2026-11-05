package com.openminis.app.a2a

import android.content.Context
import android.content.SharedPreferences

/** A2A 面板的开关持久化。 */
object A2aPrefs {
    private const val FILE = "a2a_prefs"
    private const val KEY_FLOATING = "floating_bubble"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_PROVIDER_SYNC = "provider_sync"
    private const val KEY_PROVIDER_SYNCED_AT = "provider_synced_at"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 离开面板后是否保留悬浮小图标。默认开启。 */
    fun floatingBubble(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOATING, true)

    fun setFloatingBubble(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING, enabled).apply()
    }

    /** 是否用前台服务保活网关。默认开启。 */
    fun keepAlive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_ALIVE, true)

    fun setKeepAlive(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply()
    }

    /**
     * 是否把本机网关注册成 Minis 的 AI 服务商。默认开启。
     *
     * 关掉它只影响「自动注册」，不会删掉已经建好的实例 —— 用户手动删过
     * 实例之后不应该被下次启动又加回来，所以这里同时记录上次同步时间，
     * 由调用方决定是否重试。
     */
    fun providerSyncEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROVIDER_SYNC, true)

    fun setProviderSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROVIDER_SYNC, enabled).apply()
    }

    /** 上次成功把网关注册/刷新进服务商列表的时间（毫秒）。0 表示从未成功。 */
    fun providerSyncedAt(context: Context): Long =
        prefs(context).getLong(KEY_PROVIDER_SYNCED_AT, 0L)

    fun setProviderSynced(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_PROVIDER_SYNCED_AT, at).apply()
    }
}
