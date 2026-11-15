package com.openminis.app.ui.chat

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
    // [T-send-mode-semantics] Three genuinely different behaviours, matching what
    // mainstream agent UIs do. Before this, BOTH modes behaved like an interrupt,
    // and 插话 additionally threw the whole turn away:
    //
    //   排队 (QUEUE)     — the prompt waits for this turn to FINISH. It never
    //                      cuts the stream and never lands at a tool boundary;
    //                      the post-loop drain picks it up.
    //   插话 (INTERRUPT)  — the prompt is handed to the RUNNING turn as a steer:
    //                      the model is cut only while it is still in a pure
    //                      thinking phase (nothing visible yet), otherwise it
    //                      lands right after the current tool result. Tool
    //                      history and the turn itself survive — nothing is
    //                      cancelled, nothing is re-sent from scratch.
    //   自动 (AUTO)       — like 插话 but never cuts a running generation: it
    //                      lands at the next tool boundary, so a half-written
    //                      answer is never truncated.
    //
    // The old 插话 path called cancelStream() and re-sent after a 3s poll; that
    // discarded every tool result and the partial reply, and the race often
    // degenerated into an enqueue anyway — which is exactly the "完全不对" the
    // user reported.
    val mode = com.openminis.app.data.ChatSendModePrefs.mode()
    val steer = mode == com.openminis.app.data.ChatSendModePrefs.MODE_INTERRUPT
    enqueuePrompt(text, steer = steer)
}
