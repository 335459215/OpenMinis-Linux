package com.openminis.app.a2a

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.Locale

/** A2A 面板的开关持久化。 */
object A2aPrefs {
    private const val FILE = "a2a_prefs"
    private const val KEY_FLOATING = "floating_bubble"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_GATEWAY_WANTED = "gateway_wanted"
    private const val KEY_PROVIDER_SYNC = "provider_sync"
    private const val KEY_PROVIDER_SYNCED_AT = "provider_synced_at"
    private const val KEY_PANEL_ADMIN_PASSWORD = "panel_admin_password"

    /** 内嵌面板的本地管理员账号；密码由 App 首次启动时随机生成并保存在私有目录。 */
    const val PANEL_ADMIN_USER = "minis-local"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 离开面板后是否保留悬浮小图标。默认开启。 */
    fun floatingBubble(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOATING, true)

    fun setFloatingBubble(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING, enabled).apply()
    }

    /**
     * 用户是否希望网关运行。默认开启。
     *
     * 与 [keepAlive] 的分工：keepAlive 管「用不用前台服务守」，
     * gatewayWanted 管「到底要不要活着」。用户在面板点停止后置 false，
     * App 冷启动与开机广播都必须尊重它 —— 否则「我让他死他就不能活」
     * 会被下一次启动推翻。
     */
    fun gatewayWanted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GATEWAY_WANTED, true)

    fun setGatewayWanted(context: Context, wanted: Boolean) {
        prefs(context).edit().putBoolean(KEY_GATEWAY_WANTED, wanted).apply()
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

    /**
     * 本地面板管理员的固定密码。
     *
     * 手机壳直接打开 `/m`，不应该让用户看到网页端的管理员注册/登录页。
     * 网关启动时用这组凭据预置管理员，壳再在 WebView 里静默换取会话 Cookie。
     * 密码只存在 App 私有 SharedPreferences，不写日志；首次使用随机生成。
     */
    fun panelAdminPassword(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_PANEL_ADMIN_PASSWORD, null)
        if (!existing.isNullOrBlank()) return existing
        val random = ByteArray(32)
        SecureRandom().nextBytes(random)
        val generated = random.joinToString("") { String.format(Locale.US, "%02x", it) }
        p.edit().putString(KEY_PANEL_ADMIN_PASSWORD, generated).commit()
        return generated
    }
}
