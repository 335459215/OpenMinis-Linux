package com.openminis.app.gateway.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [G-P1] 网关渠道：一个上游端点（协议 + baseUrl + 鉴权头模板）。
 * 对应上游 account_store 的 Channel 聚合根；契约要求逐字一致的四张表之一。
 *
 * - id 形如 "ch_" + 随机串（生成在 Repository，实体不管）；
 * - sortOrder 是「渠道注册序」，P2 路由在同档 priority 下按它稳定排序，
 *   目录聚合（P3）同名模型去重也用它保留 channelOrder 小的渠道，所以它必须落库；
 * - enabled 用 Int 0/1（本仓 Room 惯例，见 ProviderInstanceEntity.isEnabled）；
 * - headersJson 是渠道级公共请求头（如组织级代理头），JSON 字符串直存，
 *   解析职责在调用方（P3 Provider 构造时合并），数据层不猜结构。
 */
@Entity(
    tableName = "gateway_channels",
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** "openai" | "anthropic"，P3 据此选底层 Provider 构造分支。 */
    val protocol: String,
    val baseUrl: String,
    /** URL 路径后缀（如 "/v1"），默认空串。 */
    val urlSuffix: String = "",
    /** 渠道级公共请求头 JSON，默认 "{}"。 */
    val headersJson: String = "{}",
    /** 渠道注册序：聚合去重与 P2 基础序都按它，小者优先。 */
    val sortOrder: Int,
    val enabled: Int = 1,
    val createdAt: Long,
)

/**
 * [G-P1] 渠道账号：渠道下的一个鉴权身份（一个 API key 槽位）。
 *
 * 明文 key 一律不进 Room —— 只存 keyRef（EncryptedSharedPreferences 里的
 * 键名，`gateway_key_<id>`），Room 里只有展示用的 keyTail（末 4 位）。
 * 这是契约红线，也和 ProviderConfigStore 的 apikey_<instanceId> 惯例同构：
 * 结构化数据在 provider.db，机密在 Tink 加密的 prefs。
 *
 * priority 渠道内小者优先，允许重复（同档）；P2 排序是
 * (priority asc, channelOrder asc, accountId 稳定序)。
 */
@Entity(
    tableName = "gateway_accounts",
    indices = [Index("channelId")],
)
data class ChannelAccountEntity(
    @PrimaryKey val id: String,
    /** 所属渠道；索引：candidatesFor 要按渠道联表取账号。 */
    val channelId: String,
    val name: String,
    /** EncryptedSharedPreferences 键名 `gateway_key_<id>`，明文 key 不落 Room。 */
    val keyRef: String,
    /** 展示用末 4 位，默认空串。 */
    val keyTail: String = "",
    /** 渠道内小者优先；允许重复=同档（同档再按渠道注册序排）。 */
    val priority: Int,
    val enabled: Int = 1,
    /** 0=不限并发；P2 plan 里 inFlight ≥ maxConcurrent>0 时跳过。 */
    val maxConcurrent: Int = 0,
    /** 预留扩展（如权重、并发语义微调），JSON 直存默认 "{}"。 */
    val extraJson: String = "{}",
    val createdAt: Long,
)

/**
 * [G-P1] 渠道模型：某渠道实际可调的上游模型真名清单。
 *
 * id 是 "$channelId:$modelId" 复合主键 —— 渠道内 modelId 唯一，
 * 跨渠道同名模型各自成行（目录聚合靠这个保留全部候选渠道供 failover）。
 */
@Entity(
    tableName = "gateway_models",
    indices = [Index("channelId")],
)
data class ChannelModelEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    /** 渠道内唯一（id 已保证）。 */
    val modelId: String,
    val enabled: Int = 1,
    /** 能力标签（reasoning/vision/…），JSON 直存默认 "{}"。 */
    val capabilitiesJson: String = "{}",
)

/**
 * [G-P1] 模型映射：对外别名 → 上游真名。
 *
 * id 是 "$channelId:$alias"；alias 渠道内唯一、跨渠道可重名 ——
 * 目录（P3）按 alias 聚合多个渠道的同名映射成一组候选。
 *
 * target 是上游真名，P2 的冷却键用它（wireModel），429 记在
 * (accountId, wireModel) 上：同一账号换模型互不牵连。
 */
@Entity(
    tableName = "gateway_mappings",
    indices = [Index("channelId")],
)
data class ChannelMappingEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    /** 对外展示名；渠道内唯一，跨渠道可重名。 */
    val alias: String,
    /** 上游真名 —— 冷却键（RouteCandidate.wireModel）用它。 */
    val target: String,
    val enabled: Int = 1,
    /** 预留：该映射的推理参数覆盖，JSON 直存默认 ""。 */
    val reasoning: String = "",
)
