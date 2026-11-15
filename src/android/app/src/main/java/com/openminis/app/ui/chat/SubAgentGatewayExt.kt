package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.model.ModelEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * [T-android-arch-split] A2A gateway bring-up for sub-agents, moved out of
 * ChatViewModelSubAgentExt to keep that file under its architecture ceiling.
 */
/**
 * 子代理如果跑在内置 Agent2API 网关上，而网关进程在面板关闭后被杀，
 * 每条模型调用都会 connection refused。这里在创建 provider 之前把网关拉起来，
 * 并等健康检查通过；起不来就返回 false，让上层换一条模型重试。
 */
internal suspend fun ChatViewModel.ensureA2aGatewayForEntry(entry: ModelEntry): Boolean {
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return true
    if (instance.id != com.openminis.app.a2a.A2aProviderBridge.INSTANCE_ID) return true
    if (com.openminis.app.a2a.A2aGateway.isRunningFast()) return true
    withContext(Dispatchers.IO) {
        runCatching { com.openminis.app.a2a.A2aGateway.ensureStarted(context) }
            .onFailure { Log.w("SubAgentGateway", "ensureStarted failed: ${it.message}") }
        runCatching { com.openminis.app.a2a.A2aKeepAliveService.start(context) }
            .onFailure { Log.w("SubAgentGateway", "keepalive start failed: ${it.message}") }
    }
    repeat(24) {
        if (com.openminis.app.a2a.A2aGateway.isRunningFast()) return true
        delay(250)
    }
    Log.w("SubAgentGateway", "gateway still down after 6s for ${entry.model.displayName}")
    return false
}
