package com.openminis.app.gateway.router

/** Router 内部健康快照；对 UI 暴露时由 GatewayRepository 映射为 store DTO。 */
data class RouterAccountHealth(
    val accountId: String,
    val state: RouterHealthState,
    val retryAtMs: Long?,
)

enum class RouterHealthState {
    HEALTHY,
    BACKOFF,
    COOLDOWN,
    BREAKER_OPEN,
    RATE_LIMIT_SINK,
}
