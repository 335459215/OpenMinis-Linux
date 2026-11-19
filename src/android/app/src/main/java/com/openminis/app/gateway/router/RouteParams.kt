package com.openminis.app.gateway.router

/**
 * 路由面全部可调参数，集中一处（对应上游散在 route_state.rs / config/types.rs
 * 的常量与 RoutingSettings 默认值）。语义与取值照抄上游实证值；
 * 「上游实测」列注明出处行号。改动前先读每条注释里的上游理由。
 */
object RouteParams {

    // ── 冷却（429 / 限额）──────────────────────────────────────────
    // 上游 store_admin.rs mark_rate_limited（237-300）的四条纪律里，本仓
    // Router 只需「缺省时长」这一条：GatewayProvider 传 verdict.resetAtMs
    // 时以上游给的为准（apply/只延不缩在 markFailure 内做）。
    /** 上游没给恢复时刻时的缺省冷却（契约口径 60s；上游文本兜底 parse 不出时由 store 层记，缺省档照此）。 */
    const val RATE_LIMIT_DEFAULT_MS: Long = 60_000L

    // ── 垫底（Sink）────────────────────────────────────────────────
    /** 有额度仍 429 的账号垫底时长 30min（route_state.rs SINK_TTL_MS:59）—— 不翻倍：标记「疑似共享池被抢」，重犯不该加重。 */
    const val SINK_TTL_MS: Long = 30 * 60_000L

    // ── 退避（Backoff）─────────────────────────────────────────────
    /** 单次失败退避起步 15min（route_state.rs BACKOFF_BASE_MS:38）。 */
    const val BACKOFF_BASE_MS: Long = 15 * 60_000L
    /** 连续失败翻倍上限 60min（route_state.rs BACKOFF_MAX_MS:39）；系数 1x/2x/4x/8x 后被它夹住 → 曲线 15/30/60/60。 */
    const val BACKOFF_MAX_MS: Long = 60 * 60_000L

    // ── 熔断（Breaker）─────────────────────────────────────────────
    /** 连续失败 ≥ 此值 → open（config/types.rs breaker_threshold 默认 5:1230）。 */
    const val BREAKER_THRESHOLD: Int = 5
    /** open 时长 60s（config/types.rs breaker_timeout_seconds 默认 60:1231）；到点半开放行试一次。 */
    const val BREAKER_TIMEOUT_MS: Long = 60_000L
    /** 半开连续成功 ≥ 此值才真正关闭（config/types.rs breaker_success_threshold 默认 2:1232）。 */
    const val BREAKER_SUCCESS_THRESHOLD: Int = 2

    // ── 粘性（Sticky）──────────────────────────────────────────────
    /** Auto 档粘性 TTL 5min 滑动续期（config/types.rs StickyMode::ttl_ms:1199）。 */
    const val STICKY_TTL_MS: Long = 5 * 60_000L
    /** 粘性表容量上限（route_sticky.rs bind 的 10_000 条 + 淘汰最旧）。 */
    const val STICKY_MAX_ENTRIES: Int = 10_000
}
