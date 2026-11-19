package com.openminis.app.gateway

import com.openminis.app.gateway.router.Router
import com.openminis.app.gateway.store.GatewayRepository

/**
 * [G-P3] 进程级网关运行时装配点。
 *
 * 为什么是 object 而不是构造注入：ProviderFactory.createUncached 的签名是
 * 历史接口（instance+apiKey），网关实例的真正依赖（渠道池仓库 + 单例路由
 * 状态机）不在这条签名里。Router 必须全进程单例 —— 它的冷却/退避/熔断表
 * 是「接下来这一会儿怎么分号」的运行期事实，两个 GatewayProvider 实例各持
 * 一份就会互相看不见对方的 429 记账（上游 route_state 同样是进程单例）。
 * GatewayRepository 则由应用启动时（数据库就绪后）注入；null 期间任何网关
 * 请求都会在 GatewayProvider 入口被明确拒绝，而不是静默走空渠道。
 */
object GatewayRuntime {

    /** 渠道池数据面（P1）。由应用启动时注入；测试可替换为 fake。 */
    @Volatile
    var repository: GatewayRepository? = null

    /**
     * 路由状态机（P2）—— 全进程唯一。lazy 而不是 eager：冷启动路径不碰网关
     * 时不应初始化路由表。
     */
    val router: Router by lazy { Router() }
}
