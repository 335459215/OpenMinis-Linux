package com.openminis.app.ui.chat

/**
 * [T-android-tts-scene-announcements] Loop-converged announcement.
 *
 * Extracted from ChatViewModelAgentLoopExt.kt to keep that file under the
 * architecture ceiling. The sink is owned by the UI layer ([ChatScreenTtsWire]
 * installs it while the screen is composed and clears it on dispose), so every
 * failure mode here — no engine bound, engine not yet initialized, pref off —
 * has to be swallowed. announceAgentIdle is a no-op when no sink is installed,
 * which is exactly the backgrounded/detached case.
 */
internal fun ChatViewModel.announceAgentIdle() {
    runCatching {
        val sink = announcementSink ?: return@runCatching
        com.openminis.app.speech.VoiceAnnouncementCoordinator(sink)
            .onScene(context, com.openminis.app.speech.AnnouncementScene.IDLE_AGENT_DONE)
    }
}
