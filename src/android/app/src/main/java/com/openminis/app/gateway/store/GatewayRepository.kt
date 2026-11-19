package com.openminis.app.gateway.store

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.data.db.ProviderDatabase
import com.openminis.app.gateway.router.RouteCandidate
import com.openminis.app.util.EncryptedPrefsFactory

/**
 * [G-P1] 网关四表 + 机密 prefs 的仓库层：suspend CRUD 走 Room DAO，
 * API key 走独立加密文件（gateway_secrets），candidatesFor(alias) 把
 * P1 数据拼成 P2 的 [RouteCandidate] 列表。
 *
 * 机密红线：明文 key 一律不进 Room —— [GatewayKeyStore] 是唯一读写面，
 * Room 里的 [ChannelAccountEntity.keyRef] 只是加密 prefs 的键名
 * （`gateway_key_<id>`）。JVM 单测注入 Fake 即可覆盖该红线断言，
 * 不需要 AndroidKeystore。
 */
open class GatewayRepository(
    private val dao: GatewayDao,
    private val keyStore: GatewayKeyStore,
) {

    // ── 机密（EncryptedSharedPreferences） ────────────────────────────

    /**
     * 返回该账号的明文 API key；未存过返回 null。只读加密 prefs，
     * 不落任何缓存 —— 机密读取点保持单一，便于审计。
     */
    open fun apiKey(accountId: String): String? = keyStore.get(accountId)

    /**
     * 存/覆盖该账号的明文 API key（加密 prefs 里，键 gateway_key_<id>）。
     * 明文 key 不写 Room；展示用的末 4 位（keyTail）由调用方在
     * upsertAccount 时一并写入实体，本方法不重复维护。
     */
    fun putApiKey(accountId: String, key: String) = keyStore.put(accountId, key)

    /** 删除该账号的明文 key（删账号时调用，避免遗留孤儿机密）。 */
    fun deleteApiKey(accountId: String) = keyStore.delete(accountId)

    // ── 渠道 ──────────────────────────────────────────────────────────

    suspend fun upsertChannel(channel: ChannelEntity) = dao.upsertChannel(channel)
    suspend fun deleteChannel(id: String) = dao.deleteChannelById(id)
    open suspend fun channelById(id: String): ChannelEntity? = dao.channelById(id)
    suspend fun channels(): List<ChannelEntity> = dao.channelsAll()

    // ── 账号 ──────────────────────────────────────────────────────────

    /**
     * 写入账号；keyRef 为空时按契约补成 `gateway_key_<id>`，
     * 保证 Room 行与加密 prefs 的键名永远对齐。
     */
    suspend fun upsertAccount(account: ChannelAccountEntity) {
        val normalized =
            if (account.keyRef.isBlank()) {
                account.copy(keyRef = keyRefOf(account.id))
            } else {
                account
            }
        dao.upsertAccount(normalized)
    }

    suspend fun deleteAccount(id: String) {
        dao.deleteAccountById(id)
        // [T-gateway-orphan-secret] 删行同时清机密：加密 prefs 里
        // 没有引用完整性，Room 行没了 key 还躺着就是泄漏面。
        keyStore.delete(id)
    }

    suspend fun accountById(id: String): ChannelAccountEntity? = dao.accountById(id)
    suspend fun accountsForChannel(channelId: String): List<ChannelAccountEntity> =
        dao.accountsForChannel(channelId)

    // ── 渠道模型 ──────────────────────────────────────────────────────

    suspend fun upsertModel(model: ChannelModelEntity) = dao.upsertModel(model)
    suspend fun deleteModel(id: String) = dao.deleteModelById(id)
    suspend fun modelsForChannel(channelId: String): List<ChannelModelEntity> =
        dao.modelsForChannel(channelId)

    // ── 映射 ──────────────────────────────────────────────────────────

    suspend fun upsertMapping(mapping: ChannelMappingEntity) = dao.upsertMapping(mapping)
    suspend fun deleteMapping(id: String) = dao.deleteMappingById(id)
    suspend fun mappingsForChannel(channelId: String): List<ChannelMappingEntity> =
        dao.mappingsForChannel(channelId)

    // ── P1 → P2：候选拼装 ────────────────────────────────────────────

    /**
     * 按 alias 拼出全部路由候选。join 规则（四张表全 enabled 才算数）：
     *
     * 1. `gateway_mappings` 里 alias = 入参 且 enabled=1 的行
     *    （跨渠道可重名，所以一个 alias 对应多渠道多映射）；
     * 2. 其 channelId 必须命中一张 enabled=1 的 [ChannelEntity]（渠道关了
     *    整渠道下线，连模型带账号都不出候选）；
     * 3. 映射的 [ChannelMappingEntity.target] 必须出现在该渠道 enabled=1 的
     *    [ChannelModelEntity.modelId] 里 —— 防脏数据：映射指向渠道并没有
     *    声明的上游模型时静默跳过，而不是发一笔注定 404 的请求；
     * 4. 该渠道 enabled=1 的 [ChannelAccountEntity] 每个账号 × 该映射
     *    产出一条 [RouteCandidate]：
     *    - wireModel = mapping.target（上游真名 = 冷却键）；
     *    - priority = 账号在渠道内的优先级（小者优先，允许同档）；
     *    - channelOrder = 渠道注册序 sortOrder（同档时的稳定序）；
     *    - maxConcurrent = 账号并发上限（0=不限）。
     *
     * 排序不在这里做 —— P2 Router 负责 (priority, channelOrder, accountId)
     * 全序；这里只保证"该出的都出、不该出的都不出"。
     */
    open suspend fun candidatesFor(alias: String): List<RouteCandidate> {
        val mappings = dao.mappingsByAlias(alias).filter { it.enabled == 1 }
        if (mappings.isEmpty()) return emptyList()

        val channels = dao.channelsAll().filter { it.enabled == 1 }.associateBy { it.id }
        val candidates = ArrayList<RouteCandidate>()
        // 每渠道只查一次模型/账号列表，避免同渠道多映射时的 N+1。
        val modelCache = HashMap<String, Set<String>>()
        val accountCache = HashMap<String, List<ChannelAccountEntity>>()

        for (mapping in mappings) {
            val channel = channels[mapping.channelId] ?: continue
            val modelIds = modelCache.getOrPut(channel.id) {
                dao.modelsForChannel(channel.id).filter { it.enabled == 1 }
                    .mapTo(HashSet()) { it.modelId }
            }
            // 规则 3：target 不在渠道模型表（或模型被禁用）→ 脏数据，跳过。
            if (mapping.target !in modelIds) continue

            val accounts = accountCache.getOrPut(channel.id) {
                dao.accountsForChannel(channel.id).filter { it.enabled == 1 }
            }
            for (account in accounts) {
                candidates.add(
                    RouteCandidate(
                        accountId = account.id,
                        channelId = channel.id,
                        wireModel = mapping.target,
                        priority = account.priority,
                        channelOrder = channel.sortOrder,
                        maxConcurrent = account.maxConcurrent,
                    ),
                )
            }
        }
        return candidates
    }

    companion object {
        /** 加密 prefs 里的键名前缀，与 Room 行的 keyRef 对齐。 */
        const val KEY_REF_PREFIX = "gateway_key_"

        /** 加密 prefs 文件名：与 provider_secrets 平行的独立机密文件。 */
        private const val SECRETS_FILE = "gateway_secrets"

        internal fun keyRefOf(accountId: String): String = KEY_REF_PREFIX + accountId

        @Volatile
        private var INSTANCE: GatewayRepository? = null

        /**
         * 进程级单例入口（仿 ProviderDatabase.getInstance）：DAO 取自
         * provider.db 单例，机密走 [EncryptedPrefsFactory.safeCreate] 的
         * 自愈式 gateway_secrets 文件 —— 损坏时只重建该文件，不级联。
         */
        fun getInstance(context: Context): GatewayRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: GatewayRepository(
                    dao = ProviderDatabase.getInstance(context).gatewayDao(),
                    keyStore = EncryptedKeyStore(context.applicationContext),
                ).also { INSTANCE = it }
            }
    }
}

/**
 * 明文 API key 的读写面。抽成接口是为了 JVM 单测：EncryptedSharedPreferences
 * 需要 AndroidKeystore，在 Robolectric 之外的 JVM 测试里跑不起来，测试注入
 * Fake 即可验证「明文不进 Room」的红线。
 */
interface GatewayKeyStore {
    fun get(accountId: String): String?
    fun put(accountId: String, key: String)
    fun delete(accountId: String)
}

/**
 * 生产实现：独立加密文件 gateway_secrets（Tink AES-GCM，键
 * `gateway_key_<accountId>`），走 [EncryptedPrefsFactory.safeCreate] 的
 * 自愈包装 —— 与 ProviderConfigStore 的 provider_secrets 同一惯例，
 * keystore 主键损坏时不会把启动打崩。
 */
internal class EncryptedKeyStore(context: Context) : GatewayKeyStore {
    private val prefs: SharedPreferences = EncryptedPrefsFactory.safeCreate(context, "gateway_secrets")

    override fun get(accountId: String): String? = prefs.getString(GatewayRepository.keyRefOf(accountId), null)

    override fun put(accountId: String, key: String) {
        prefs.edit().putString(GatewayRepository.keyRefOf(accountId), key).apply()
    }

    override fun delete(accountId: String) {
        prefs.edit().remove(GatewayRepository.keyRefOf(accountId)).apply()
    }
}
