package com.openminis.app.gateway.router

/**
 * 一次上游失败怎么记账 —— P3 GatewayProvider 的 failover 循环判定后报给 Router。
 * 对照上游 provider_loop.rs 的 UpstreamErrorClass 分类（adapter.rs 290-329）：
 *
 * - [RateLimited]：上游限额/风控（429 / code 6004）→ 按上游 Retry-After 记
 *   resetAtMs；解析不出时 Router 用缺省冷却（route_state / store_admin 的
 *   兜底口径）。对应 QuotaLimited{reset_at}，走「冷却 + 换号」，不进退避/熔断
 *   （provider_loop.rs 动作 1，1683-1690：429 有自己的冷却机制，「等重置」比
 *   「退避」精确）。
 * - [Retryable]：暂时性失败（网络抖动 / 5xx）→ 退避 + 熔断计数
 *   （provider_loop.rs 动作 3，1820-1860）。
 * - [Fatal]：不可恢复错误（4xx 语义错）→ 上游同款直接换号，不罚账号路由态
 *   （ContentBlocked「不罚账号」同口径，adapter.rs 307-313；上游 Fatal 不调
 *   backoff_mark/breaker_failure）。
 * - [StreamBroken]：流建立**之后**断开（本仓 P3 特有的判定，上游是长连接由
 *   客户端兜底；这里按可重试类同 [Retryable] 记退避，让下一个候选接手）。
 */
sealed class UpstreamVerdict {
    /** 限额冷却：[resetAtMs] 是恢复时刻（毫秒 epoch），[Retry-After] 缺失时调用方不传。 */
    class RateLimited(val resetAtMs: Long? = null) : UpstreamVerdict()

    /** 暂时性失败：退避（连续翻倍）+ 熔断计数。 */
    object Retryable : UpstreamVerdict()

    /** 不可恢复：不罚账号（上游 ContentBlocked / Fatal 不进路由状态）。 */
    object Fatal : UpstreamVerdict()

    /** 流中断开：按可重试记账（退避），由下一候选重开流。 */
    object StreamBroken : UpstreamVerdict()
}
