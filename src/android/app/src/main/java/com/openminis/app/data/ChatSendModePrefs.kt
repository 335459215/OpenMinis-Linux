package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _modeFlow = MutableStateFlow(MODE_QUEUE)
    val modeFlow: StateFlow<String> = _modeFlow.asStateFlow()

    private fun normalize(mode: String?): String = when (mode) {
        MODE_INTERRUPT, MODE_AUTO -> mode
        else -> MODE_QUEUE
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun prime(context: Context) {
        _modeFlow.value = normalize(prefs(context).getString(KEY_MODE, MODE_QUEUE))
    }

    fun mode(): String = _modeFlow.value

    fun setMode(context: Context, mode: String) {
        val normalized = normalize(mode)
        prefs(context).edit().putString(KEY_MODE, normalized).apply()
        _modeFlow.value = normalized
    }

    fun isInterrupt(context: Context): Boolean =
        normalize(prefs(context).getString(KEY_MODE, mode())) == MODE_INTERRUPT
}
