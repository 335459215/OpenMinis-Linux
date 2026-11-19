package com.openminis.app.gateway.catalog

import com.openminis.app.gateway.router.RouteCandidate
import com.openminis.app.gateway.store.ChannelEntity
import com.openminis.app.gateway.store.GatewayRepository

/**
 * [G-P3] 网关目录：把所有 enabled 渠道的 models + mappings 合并成一张
 * 按 alias 聚合的虚拟目录 —— 一个 `GatewayModel` 就是一个对外模型名，
 * 它的 [GatewayModel.candidates] 是该名字背后的全部路由候选（跨渠道、
 * 每渠道的全部 enabled 账号），供 failover 循环逐个换号。
 *
 * 「同名去重保留 channelOrder 小的渠道」（上游 §4.4 语义）作用于**目录
 * 展示语义**：决定一个 alias 的默认归属渠道（displayName/能力等将来取
 * 自该渠道的声明）；但候选全集**不去重**——failover 需要每一双能接手的
 * 手（上游 catalog 聚合同样保留全部渠道）。所以这里的实现是：候选全集
 * 按 `repository.candidatesFor(alias)` 原样保留，`primaryChannelId` 取
 * 候选中 channelOrder 最小者的渠道。
 */
data class GatewayModel(
    /** 对外别名 —— UI 模型选择器里展示的名字。 */
    val alias: String,
    /** primary channel（channelOrder 最小）的上游真名。 */
    val wireModel: String,
    /** 候选全集（跨渠道、含每渠道全部 enabled 账号），failover 用。 */
    val candidates: List<RouteCandidate>,
    /** 同名去重后保留的主渠道 id（channelOrder 最小者）。 */
    val primaryChannelId: String,
)

/**
 * 目录装配：suspend 读 repository（Room），纯 JVM 无 Android 依赖，
 * UI/测试可直接调用。聚合规则：
 *
 * 1. 遍历 enabled 渠道的 enabled 映射（跨渠道 alias 可重名）；
 * 2. 每个 alias 的候选经 [GatewayRepository.candidatesFor] 拼装
 *    （四表 enabled join、脏映射跳过 —— 规则见其文档）；
 * 3. alias 相同的映射合成一个 [GatewayModel]，主渠道取 channelOrder
 *    最小者（稳定：同 channelOrder 时按 channelId 字典序）。
 */
object ModelCatalog {

    suspend fun models(repository: GatewayRepository): List<GatewayModel> {
        val channels: List<ChannelEntity> = repository.channels().filter { it.enabled == 1 }
        if (channels.isEmpty()) return emptyList()

        // alias → (primary 映射来源渠道, 候选全集)。候选由 repository 拼，
        // 这里只做「同 alias 跨渠道聚合 + 主渠道挑选」。
        val byAlias = LinkedHashMap<String, MutableList<RouteCandidate>>()
        val primaryByAlias = HashMap<String, PrimaryPick>()

        for (channel in channels) {
            val mappings = repository.mappingsForChannel(channel.id).filter { it.enabled == 1 }
            if (mappings.isEmpty()) continue
            // 渠道模型表必须声明映射 target，脏映射在 candidatesFor 已跳过；
            // 目录这边同口径再校验一次，保证 alias 只在真有可用候选时出现。
            val modelIds = repository.modelsForChannel(channel.id)
                .filter { it.enabled == 1 }
                .mapTo(HashSet()) { it.modelId }
            for (mapping in mappings) {
                if (mapping.target !in modelIds) continue
                val candidates = repository.candidatesFor(mapping.alias)
                if (candidates.isEmpty()) continue
                val list = byAlias.getOrPut(mapping.alias) { ArrayList() }
                // 同渠道同 alias 的映射在目录里只聚合一次（id = channelId:alias
                // 已保证渠道内唯一；多渠道重名是预期路径）。
                if (list.none { it.channelId == channel.id }) {
                    list.addAll(candidates.filter { it.channelId == channel.id })
                }
                val pick = PrimaryPick(channel.sortOrder, channel.id, mapping.target)
                val current = primaryByAlias[mapping.alias]
                if (current == null || pick < current) primaryByAlias[mapping.alias] = pick
            }
        }

        return byAlias.map { (alias, candidates) ->
            val primary = primaryByAlias[alias]
            GatewayModel(
                alias = alias,
                wireModel = primary?.target ?: candidates.first().wireModel,
                candidates = candidates,
                primaryChannelId = primary?.channelId ?: candidates.first().channelId,
            )
        }.sortedBy { it.alias }
    }

    /** 主渠道挑选键：channelOrder 小者胜，同序按 channelId 稳定。 */
    private data class PrimaryPick(
        val channelOrder: Int,
        val channelId: String,
        val target: String,
    ) : Comparable<PrimaryPick> {
        override fun compareTo(other: PrimaryPick): Int = compareValuesBy(
            this,
            other,
            { it.channelOrder },
            { it.channelId },
        )
    }
}
