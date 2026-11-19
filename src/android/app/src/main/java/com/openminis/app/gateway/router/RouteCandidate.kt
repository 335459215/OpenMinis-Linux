package com.openminis.app.gateway.router

/**
 * 一条选路候选 —— P1 数据面（Repository.candidatesFor）拼出来交给 P2 Router 的
 * 纯数据。字段与契约逐字一致，语义对照上游：
 *
 * - [accountId] 唯一去重键：tried 剔除、退避/垫底/熔断/并发计数都按它
 *   （上游 plan_route 的 `excluded` 与 route_state.rs 三张表均以账号 id 为键，
 *   routing.rs 640-720）。
 * - [wireModel] 映射后的**上游真名**，是限额冷却的键。上游规则 1 里的「该模型」
 *   指上游真名而不是请求名（routing.rs 模块头「冷却键」一节），同账号两个
 *   wireModel 的冷却互不影响。
 * - [priority] 渠道内小者优先（上游 compare_by_priority 的第一键，routing.rs
 *   240-330）；同档时按 [channelOrder]（渠道注册序）——上游第二键是 addedAt，
 *   本仓语义里渠道注册序承担同一职责（先注册的先出）。
 * - [maxConcurrent] 0 = 不限（上游 at_concurrency_limit，routing.rs 273-278）。
 */
data class RouteCandidate(
    val accountId: String,
    val channelId: String,
    val wireModel: String,
    val priority: Int,
    val channelOrder: Int,
    val maxConcurrent: Int = 0,
)
