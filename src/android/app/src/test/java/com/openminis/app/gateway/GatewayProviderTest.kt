package com.openminis.app.gateway

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.gateway.router.RouteCandidate
import com.openminis.app.gateway.router.Router
import com.openminis.app.gateway.store.ChannelAccountEntity
import com.openminis.app.gateway.store.ChannelMappingEntity
import com.openminis.app.gateway.store.ChannelModelEntity
import com.openminis.app.gateway.store.GatewayDao
import com.openminis.app.gateway.store.GatewayKeyStore
import com.openminis.app.gateway.store.GatewayRepository
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [G-P3] GatewayProvider failover 状态机的 JVM 单测：fake repository +
 * 真 Router + 脚本化 fake 底层 provider，覆盖「首块前换号」「流中断重开」
 * 「全候选耗尽聚合错误」「成功粘性绑定」四条主路径。
 */
class GatewayProviderTest {

    // ── fakes ─────────────────────────────────────────────────────────

    /** 全 TODO 的 DAO：测试只走被覆写的 repo 方法，Room 永不触达。 */
    private open class FakeDao : GatewayDao {
        override suspend fun upsertChannel(channel: com.openminis.app.gateway.store.ChannelEntity) = TODO()
        override suspend fun updateChannel(channel: com.openminis.app.gateway.store.ChannelEntity) = TODO()
        override suspend fun deleteChannelById(id: String) = TODO()
        override suspend fun channelById(id: String) = TODO()
        override suspend fun channelsAll() = TODO()
        override suspend fun upsertAccount(account: ChannelAccountEntity) = TODO()
        override suspend fun updateAccount(account: ChannelAccountEntity) = TODO()
        override suspend fun deleteAccountById(id: String) = TODO()
        override suspend fun accountById(id: String) = TODO()
        override suspend fun accountsForChannel(channelId: String) = TODO()
        override suspend fun upsertModel(model: ChannelModelEntity) = TODO()
        override suspend fun updateModel(model: ChannelModelEntity) = TODO()
        override suspend fun deleteModelById(id: String) = TODO()
        override suspend fun modelById(id: String) = TODO()
        override suspend fun modelsForChannel(channelId: String) = TODO()
        override suspend fun upsertMapping(mapping: ChannelMappingEntity) = TODO()
        override suspend fun updateMapping(mapping: ChannelMappingEntity) = TODO()
        override suspend fun deleteMappingById(id: String) = TODO()
        override suspend fun mappingById(id: String) = TODO()
        override suspend fun mappingsForChannel(channelId: String) = TODO()
        override suspend fun mappingsByAlias(alias: String) = TODO()
    }

    private class FakeKeyStore(private val keys: Map<String, String>) : GatewayKeyStore {
        override fun get(accountId: String): String? = keys[accountId]
        override fun put(accountId: String, key: String) = TODO()
        override fun delete(accountId: String) = TODO()
    }

    /** 覆写三个查询面；明文 key 走内存 map，验证「key 不进 Room」的注入面。 */
    private class FakeRepo(
        private val candidates: Map<String, List<RouteCandidate>>,
        private val channels: Map<String, com.openminis.app.gateway.store.ChannelEntity>,
        keys: Map<String, String>,
    ) : GatewayRepository(FakeDao(), FakeKeyStore(keys)) {
        var candidatesCalls = 0
        override suspend fun candidatesFor(alias: String): List<RouteCandidate> {
            candidatesCalls++
            return candidates[alias] ?: emptyList()
        }

        override suspend fun channelById(id: String) = channels[id]
        override fun apiKey(accountId: String): String? = "sk-$accountId"
    }

    /** 脚本化底层 provider：每次 stream 按脚本弹出一段行为。 */
    private class ScriptedProvider(
        private val accountKey: String,
        script: MutableList<Script>,
    ) : LLMProvider {
        override val name: String = accountKey
        override var model: LLMModel = LLMModel(id = "alias", displayName = "alias", provider = "gateway")

        val script = script

        sealed class Script {
            data class FailBeforeFirst(val e: Throwable) : Script()
            data class BreakMidStream(val partial: String, val e: Throwable) : Script()
            data class Ok(val text: String) : Script()
        }

        var streamCount = 0

        override suspend fun sendMessageClamped(
            messages: List<com.openminis.app.data.model.LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<com.openminis.app.data.model.LLMMessage.ImagePart>,
            tools: List<com.openminis.app.data.model.AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
            extraHeaders: Map<String, String>,
        ): LLMResponse {
            val step = script.removeAt(0)
            when (step) {
                is Script.FailBeforeFirst -> throw step.e
                is Script.BreakMidStream -> throw step.e
                is Script.Ok -> return LLMResponse(text = step.text, stopReason = "stop", usage = null)
            }
        }

        override fun streamMessageClamped(
            messages: List<com.openminis.app.data.model.LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<com.openminis.app.data.model.LLMMessage.ImagePart>,
            tools: List<com.openminis.app.data.model.AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
            systemStablePrefixLen: Int,
            extraHeaders: Map<String, String>,
        ): Flow<LLMStreamChunk> = flow {
            streamCount++
            val step = script.removeAt(0)
            when (step) {
                is Script.FailBeforeFirst -> throw step.e
                is Script.BreakMidStream -> {
                    emit(LLMStreamChunk.Started)
                    emit(LLMStreamChunk.Text(step.partial))
                    throw step.e
                }
                is Script.Ok -> {
                    emit(LLMStreamChunk.Started)
                    emit(LLMStreamChunk.Text(step.text))
                    emit(LLMStreamChunk.Finished("stop"))
                }
            }
        }
    }

    // ── harness ───────────────────────────────────────────────────────

    private fun instance() = ProviderInstance(
        id = "gw1",
        label = "GW",
        providerType = ProviderType.gateway,
        credentialType = ProviderCredential.apiKey,
    )

    private fun candidate(accountId: String, priority: Int) = RouteCandidate(
        accountId = accountId,
        channelId = "ch1",
        wireModel = "alias",
        priority = priority,
        channelOrder = 1,
        maxConcurrent = 0,
    )

    private fun channel(id: String) = com.openminis.app.gateway.store.ChannelEntity(
        id = id,
        name = "ch",
        protocol = "openai",
        baseUrl = "https://example.invalid",
        sortOrder = 1,
        createdAt = 0L,
    )

    private fun repo(candidates: Map<String, List<RouteCandidate>>) = FakeRepo(
        candidates = candidates,
        channels = mapOf("ch1" to channel("ch1")),
        keys = mapOf("a" to "sk-a", "b" to "sk-b"),
    )

    /** 按 apiKey 分发脚本：key = sk-<accountId>。 */
    private fun provider(
        repo: FakeRepo,
        router: Router,
        scripts: Map<String, MutableList<ScriptedProvider.Script>>,
    ) = GatewayProvider(
        instance = instance(),
        repository = repo,
        router = router,
        model = LLMModel(id = "alias", displayName = "alias", provider = "gateway"),
        underlyingFactory = { _, apiKey, _, _ ->
            val accountKey = apiKey.removePrefix("sk-")
            val script = scripts[accountKey] ?: error("no script for $accountKey")
            ScriptedProvider(accountKey, script)
        },
    )

    private fun stream(gp: GatewayProvider): Flow<LLMStreamChunk> =
        gp.streamMessageClamped(
            emptyList(), null, 128, null,
            emptyList(), emptyList(), ThinkingLevel.OFF, -1, emptyMap(),
        )

    // ── tests ─────────────────────────────────────────────────────────

    @Test
    fun `failover before first chunk tries next candidate`() = runTest {
        val scripts = mapOf(
            "a" to mutableListOf<ScriptedProvider.Script>(
                ScriptedProvider.Script.FailBeforeFirst(RuntimeException("boom-a")),
            ),
            "b" to mutableListOf<ScriptedProvider.Script>(
                ScriptedProvider.Script.Ok("hello-b"),
            ),
        )
        val repo = repo(mapOf("alias" to listOf(candidate("a", 1), candidate("b", 2))))
        val gp = provider(repo, Router(), scripts)

        val chunks = stream(gp).toList()
        val texts = chunks.filterIsInstance<LLMStreamChunk.Text>().map { it.text }
        assertEquals(listOf("hello-b"), texts)
        // a 的脚本被消费（打过一次且失败），b 的脚本也被消费且成功
        assertTrue(scripts["a"]!!.isEmpty())
        assertTrue(scripts["b"]!!.isEmpty())
    }

    @Test
    fun `stream broken mid-stream restarts full stream on next candidate`() = runTest {
        val scripts = mapOf(
            "a" to mutableListOf<ScriptedProvider.Script>(
                ScriptedProvider.Script.BreakMidStream("partial-a", RuntimeException("reset-a")),
            ),
            "b" to mutableListOf<ScriptedProvider.Script>(
                ScriptedProvider.Script.Ok("full-b"),
            ),
        )
        val repo = repo(mapOf("alias" to listOf(candidate("a", 1), candidate("b", 2))))
        val gp = provider(repo, Router(), scripts)

        val chunks = stream(gp).toList()
        // 已发出的 a 部分块不撤回；随后 b 的整段流（含 Started）重开
        assertEquals(LLMStreamChunk.Started, chunks.first())
        val texts = chunks.filterIsInstance<LLMStreamChunk.Text>().map { it.text }
        assertEquals(listOf("partial-a", "full-b"), texts)
        assertEquals(0, scripts["a"]!!.size) // 断流脚本已被消费
        assertEquals(0, scripts["b"]!!.size)
    }

    @Test
    fun `all candidates failing throws aggregate error naming accounts`() = runTest {
        val scripts = mapOf(
            "a" to mutableListOf<ScriptedProvider.Script>(
                ScriptedProvider.Script.FailBeforeFirst(LLMError.InvalidApiKey("bad key")),
            ),
            "b" to mutableListOf<ScriptedProvider.Script>(
                ScriptedProvider.Script.FailBeforeFirst(RuntimeException("boom-b")),
            ),
        )
        val repo = repo(mapOf("alias" to listOf(candidate("a", 1), candidate("b", 2))))
        val gp = provider(repo, Router(), scripts)

        try {
            stream(gp).toList()
            fail("expected aggregate LLMError")
        } catch (e: LLMError) {
            val msg = e.message ?: ""
            assertTrue(msg.contains("alias"))
            assertTrue(msg.contains("a@"))
            assertTrue(msg.contains("b@"))
            assertTrue(msg.contains("fatal"))
            assertTrue(msg.contains("retryable"))
        }
    }

    @Test
    fun `success binds sticky and next plan prefers that account`() = runTest {
        val scripts = mapOf(
            "a" to mutableListOf<ScriptedProvider.Script>(
                ScriptedProvider.Script.FailBeforeFirst(RuntimeException("boom-a")),
            ),
            "b" to mutableListOf<ScriptedProvider.Script>(
                ScriptedProvider.Script.Ok("hello-b"),
            ),
        )
        // a 基础序在前（priority 1 < 2）：a 先试失败，b 成功后粘性绑定
        val repo = repo(mapOf("alias" to listOf(candidate("a", 1), candidate("b", 2))))
        val router = Router()
        val gp = provider(repo, router, scripts)

        val chunks = stream(gp).toList()
        val texts = chunks.filterIsInstance<LLMStreamChunk.Text>().map { it.text }
        assertEquals(listOf("hello-b"), texts)

        // 粘性验证：下一次 plan，a 未被 try 过、b 优先级更差，但 b 粘性命中应提到最前
        val ordered = router.plan("gateway:gw1:alias", listOf(candidate("a", 1), candidate("b", 2)))
        assertEquals("b", ordered.first().accountId)
    }
}
