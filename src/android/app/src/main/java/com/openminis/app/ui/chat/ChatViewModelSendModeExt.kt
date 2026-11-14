package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [T-send-mode] 用户发送统一入口：流式进行中按设置决定排队或插话。
 *
 * 放独立文件是因为 ChatViewModel 已经顶到架构行数上限（6201），
 * 这段逻辑只依赖 _isStreaming / _promptQueue / cancelStream，适合做扩展。
 */
internal fun ChatViewModel.sendOrQueue(text: String) {
    if (text.isBlank()) return
    if (!_isStreaming.value) {
        sendMessage(text)
        return
    }
    val mode = com.openminis.app.data.ChatSendModePrefs.mode()
    val interrupt = when (mode) {
        com.openminis.app.data.ChatSendModePrefs.MODE_INTERRUPT -> true
        com.openminis.app.data.ChatSendModePrefs.MODE_AUTO -> _promptQueue.value.isEmpty()
        else -> false
    }
    if (!interrupt) {
        enqueuePrompt(text)
        return
    }
    cancelStream()
    viewModelScope.launch {
        delay(150)
        sendMessage(text)
    }
}
