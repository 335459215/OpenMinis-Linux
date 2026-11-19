package com.openminis.app.gateway.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * [G-P1] 网关四表的 DAO。一个文件即可（契约约定）：insert/update 全部
 * REPLACE/直更，delete 按 id，查询按 channelId 拉列表 ——
 * candidatesFor(alias) 的联表逻辑放 Repository，DAO 只做无业务的存取，
 * 和仓里 ProviderConfigDao 的分工方式一致。
 */
@Dao
interface GatewayDao {

    // ── 渠道 ──────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannel(channel: ChannelEntity)

    @Update
    suspend fun updateChannel(channel: ChannelEntity)

    @Query("DELETE FROM gateway_channels WHERE id = :id")
    suspend fun deleteChannelById(id: String)

    @Query("SELECT * FROM gateway_channels WHERE id = :id")
    suspend fun channelById(id: String): ChannelEntity?

    @Query("SELECT * FROM gateway_channels ORDER BY sortOrder ASC")
    suspend fun channelsAll(): List<ChannelEntity>

    // ── 账号 ──────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: ChannelAccountEntity)

    @Update
    suspend fun updateAccount(account: ChannelAccountEntity)

    @Query("DELETE FROM gateway_accounts WHERE id = :id")
    suspend fun deleteAccountById(id: String)

    @Query("SELECT * FROM gateway_accounts WHERE id = :id")
    suspend fun accountById(id: String): ChannelAccountEntity?

    @Query("SELECT * FROM gateway_accounts WHERE channelId = :channelId ORDER BY priority ASC")
    suspend fun accountsForChannel(channelId: String): List<ChannelAccountEntity>

    // ── 模型 ──────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModel(model: ChannelModelEntity)

    @Update
    suspend fun updateModel(model: ChannelModelEntity)

    @Query("DELETE FROM gateway_models WHERE id = :id")
    suspend fun deleteModelById(id: String)

    @Query("SELECT * FROM gateway_models WHERE id = :id")
    suspend fun modelById(id: String): ChannelModelEntity?

    @Query("SELECT * FROM gateway_models WHERE channelId = :channelId")
    suspend fun modelsForChannel(channelId: String): List<ChannelModelEntity>

    // ── 映射 ──────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMapping(mapping: ChannelMappingEntity)

    @Update
    suspend fun updateMapping(mapping: ChannelMappingEntity)

    @Query("DELETE FROM gateway_mappings WHERE id = :id")
    suspend fun deleteMappingById(id: String)

    @Query("SELECT * FROM gateway_mappings WHERE id = :id")
    suspend fun mappingById(id: String): ChannelMappingEntity?

    @Query("SELECT * FROM gateway_mappings WHERE channelId = :channelId")
    suspend fun mappingsForChannel(channelId: String): List<ChannelMappingEntity>

    /** 按 alias 跨渠道取全部映射（candidatesFor 的入口查询）。 */
    @Query("SELECT * FROM gateway_mappings WHERE alias = :alias")
    suspend fun mappingsByAlias(alias: String): List<ChannelMappingEntity>
}
