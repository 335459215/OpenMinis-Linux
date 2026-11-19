package com.openminis.app.gateway.store

/** Gateway UI 的账号健康读数；不包含任何路由内部状态。 */
data class GatewayAccountHealth(
    val accountId: String,
    val state: GatewayHealthState,
    val retryAtMs: Long?,
)

enum class GatewayHealthState {
    HEALTHY,
    BACKOFF,
    COOLDOWN,
    BREAKER_OPEN,
    RATE_LIMIT_SINK,
}
