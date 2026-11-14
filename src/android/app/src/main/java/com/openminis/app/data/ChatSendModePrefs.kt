package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-send-mode] 流式回复进行中，用户再次发送时的行为。
 *
 * - QUEUE（默认）：排队，等当前回复结束自动发出。现有行为。
 * - INTERRUPT：插话，先取消当前流式再立刻发送新消息。
 * - AUTO：有排队任务时排队，否则插话（少量场景下更顺手）。
 *
 * 与 [AutoCompactPrefs] 一样是 App 级持久化，切会话继承。
 */
object ChatSendModePrefs {
    private const val PREFS = "minis_chat_send_mode"
    private const val KEY_MODE = "sendMode"

    const val MODE_QUEUE = "queue"
    const val MODE_INTERRUPT = "interrupt"
    const val MODE_AUTO = "auto"

    @Volatile
    private var cached: String = MODE_QUEUE

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun prime(context: Context) {
        cached = prefs(context).getString(KEY_MODE, MODE_QUEUE) ?: MODE_QUEUE
    }

    fun mode(): String = cached

    fun setMode(context: Context, mode: String) {
        val normalized = when (mode) {
            MODE_INTERRUPT, MODE_AUTO -> mode
            else -> MODE_QUEUE
        }
        cached = normalized
        prefs(context).edit().putString(KEY_MODE, normalized).apply()
    }

    fun isInterrupt(context: Context): Boolean =
        (prefs(context).getString(KEY_MODE, cached) ?: cached) == MODE_INTERRUPT
}
