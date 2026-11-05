package com.openminis.app.a2a

import android.content.Context
import android.content.SharedPreferences

/** A2A 面板的开关持久化。 */
object A2aPrefs {
    private const val FILE = "a2a_prefs"
    private const val KEY_FLOATING = "floating_bubble"
    private const val KEY_KEEP_ALIVE = "keep_alive"

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
}
