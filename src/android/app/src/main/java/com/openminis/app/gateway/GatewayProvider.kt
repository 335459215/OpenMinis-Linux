package com.openminis.app.gateway

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.gateway.router.RouteCandidate
import com.openminis.app.gateway.router.Router
import com.openminis.app.gateway.router.UpstreamVerdict
import com.openminis.app.gateway.store.GatewayRepository
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * [G-P3] 网关聚合 Provider：把一个 `ProviderType.gateway` 的虚拟实例
 * 变成 LLMProvider。它自己不说话 —— 每次请求按 alias 从渠道池取候选
 * （[GatewayRepository.candidatesFor]），交给进程单例 [Router] 排序，
 * 然后按序逐个候选构造底层 provider（openai→[OpenAIProvider]、
 * anthropic→[AnthropicProvider]，**直接 new 绕开 Factory 缓存**——
 * 渠道账号的 key/URL 是每请求级事实，进 64 条的 LRU 只会互相挤掉
 * 真正长活的 provider）并转发请求。
 *
 * Failover 状态机（契约 P3 节，对照上游 provider_loop.rs）：
 *
 * 1. `plan(aliasKey, candidates, tried)` 空 → 全部候选耗尽，抛聚合错误
 *    （每候选一条 `accountId: verdict 摘要`）；
 * 2. 头部候选 `acquire`（并发席位），开始打；
 * 3. **流首块前**错误按 [UpstreamVerdict] 分类记账：
 *    Retry-After 有值 → RateLimited(resetAt)；401/403 → Fatal（不罚
 *    账号路由态）；其余 → Retryable。markFailure + tried + release +
 *    continue（下一候选）；
 * 4. **流已 emitted 之后**断开 → StreamBroken 记账，对下一候选**重开流**
 *    （已发给下游的块不撤回，UI 侧 stallWatchdog 兜底）；
 * 5. 正常完成 → markSuccess + release。
 *
 * 会话粘性键（aliasKey）用 `gateway:<instanceId>:<model alias>` ——
 * 同一实例同一模型的会话尽量粘同一账号（Router 的 sticky 语义）。
 */
class GatewayProvider(
    private val instance: ProviderInstance,
    private val repository: GatewayRepository,
    private val router: Router,
    /** Factory 注入的当前模型（id = 目录 alias，displayName 供 UI）。 */
    override var model: LLMModel = LLMModel(id = "", displayName = "Gateway", provider = "gateway"),
    /** 底层 provider 构造（测试注入 fake）。 */
    private val underlyingFactory: (
        protocol: String,
        apiKey: String,
        baseUrl: String,
        model: String,
    ) -> LLMProvider = Companion::defaultUnderlyingFactory,
) : LLMProvider {

    override val name: String = instance.label.ifBlank { "Gateway" }

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
        extraHeaders: Map<String, String>,
    ): LLMResponse = withCandidate { provider, candidate, aliasKey ->
        provider.sendMessageClamped(
            messages, systemPrompt, maxTokens, temperature,
            imageParts, tools, thinkingLevel, extraHeaders,
        )
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
        systemStablePrefixLen: Int,
        extraHeaders: Map<String, String>,
    ): Flow<LLMStreamChunk> = channelFlow {
        val aliasKey = stickyKey()
        val candidates = repository.candidatesFor(model.id)
        val tried = HashSet<String>()
        val failures = ArrayList<String>()

        while (true) {
            val candidate = router.plan(aliasKey, candidates, tried = tried).firstOrNull()
                ?: throw aggregateError(model.id, failures)
            val provider = buildProvider(candidate)
            router.acquire(candidate.accountId)
            var emitted = false
            try {
                provider.streamMessageClamped(
                    messages, systemPrompt, maxTokens, temperature,
                    imageParts, tools, thinkingLevel, systemStablePrefixLen, extraHeaders,
                ).collect { chunk ->
                    emitted = true
                    send(chunk)
                }
                router.markSuccess(candidate)
                router.release(candidate.accountId)
                return@channelFlow
            } catch (e: Throwable) {
                router.release(candidate.accountId)
                val verdict = classify(e, emitted)
                router.markFailure(candidate, verdict)
                tried.add(candidate.accountId)
                failures.add(failureSummary(candidate, verdict, e))
                // 首块前失败与 StreamBroken 同一循环：tried 剔除后 plan
                // 自然给出下一候选；空了由循环顶部抛聚合错误。
            }
        }
    }

    // ── 内部：候选迭代与记账 ──────────────────────────────────────

    /**
     * 非流路径的候选迭代：逐候选尝试 [block]（acquire → block →
     * markSuccess + release），失败按 verdict 记账换号，全部耗尽抛聚合
     * 错误。成功时返回该候选的结果。
     */
    private suspend fun <T> withCandidate(
        block: suspend (LLMProvider, RouteCandidate, String) -> T,
    ): T {
        val aliasKey = stickyKey()
        val candidates = repository.candidatesFor(model.id)
        val tried = HashSet<String>()
        val failures = ArrayList<String>()
        while (true) {
            val candidate = router.plan(aliasKey, candidates, tried = tried).firstOrNull()
                ?: throw aggregateError(model.id, failures)
            val provider = buildProvider(candidate)
            router.acquire(candidate.accountId)
            try {
                return block(provider, candidate, aliasKey).also {
                    router.markSuccess(candidate)
                    router.release(candidate.accountId)
                }
            } catch (e: Throwable) {
                router.release(candidate.accountId)
                val verdict = classify(e, false)
                router.markFailure(candidate, verdict)
                tried.add(candidate.accountId)
                failures.add(failureSummary(candidate, verdict, e))
            }
        }
    }

    /** 按候选渠道构造底层 provider（绕开 Factory 缓存，直接 new）。 */
    private suspend fun buildProvider(candidate: RouteCandidate): LLMProvider {
        val channel = repository.channelById(candidate.channelId)
            ?: throw LLMError.ProviderError("gateway channel ${candidate.channelId} vanished")
        val apiKey = repository.apiKey(candidate.accountId)
            ?: throw LLMError.InvalidApiKey(
                "no API key stored for gateway account ${candidate.accountId}",
            )
        val base = channel.baseUrl.trimEnd('/') + channel.urlSuffix
        return underlyingFactory(channel.protocol, apiKey, base, candidate.wireModel)
    }

    private fun stickyKey(): String = "gateway:${instance.id}:${model.id}"

    companion object {
        /** 默认底层构造：openai→OpenAIProvider、anthropic→AnthropicProvider。 */
        fun defaultUnderlyingFactory(
            protocol: String,
            apiKey: String,
            baseUrl: String,
            model: String,
        ): LLMProvider {
            val wireModel = LLMModel(
                id = model,
                displayName = model,
                provider = protocol,
            )
            return when (protocol) {
                "anthropic" -> AnthropicProvider(apiKey, wireModel, baseUrl)
                else -> OpenAIProvider(apiKey = apiKey, model = wireModel, basePath = baseUrl)
            }
        }

        /**
         * 错误 → [UpstreamVerdict]（契约口径）：
         * - [LLMError.RateLimited] → RateLimited（Retry-After 秒数换算成恢复
         *   时刻；缺失传 null，Router 用缺省冷却）；
         * - [LLMError.InvalidApiKey]（401/403 类）→ Fatal：不可恢复，换号，
         *   不罚账号路由态（上游 ContentBlocked 同口径）；
         * - 其余（网络/5xx/解码/ProviderError…）→ Retryable；
         * - [emitted] 后断 → StreamBroken（退避记账，下一候选重开流）。
         */
        fun classify(e: Throwable, emitted: Boolean): UpstreamVerdict {
            if (emitted) return UpstreamVerdict.StreamBroken
            return when (e) {
                is LLMError.RateLimited -> UpstreamVerdict.RateLimited(
                    resetAtMs = e.retryAfterSeconds?.let { sec ->
                        System.currentTimeMillis() + sec * 1000L
                    },
                )
                is LLMError.InvalidApiKey -> UpstreamVerdict.Fatal
                else -> UpstreamVerdict.Retryable
            }
        }

        /**
         * 聚合错误：头部一句总结 + 每候选一行 `accountId: verdict 摘要`
         * （候选全空时也明确说出原因，而不是空列表）。
         */
        fun aggregateError(alias: String, failures: List<String>): LLMError {
            val lines = failures.ifEmpty {
                listOf("no routeable candidate (channel disabled or no enabled account)")
            }
            return LLMError.ProviderError(
                "All gateway candidates failed for \"$alias\" — " + lines.joinToString("; "),
            )
        }

        private fun failureSummary(
            candidate: RouteCandidate,
            verdict: UpstreamVerdict,
            e: Throwable,
        ): String {
            val verdictText = when (verdict) {
                is UpstreamVerdict.RateLimited -> "rate-limited"
                UpstreamVerdict.Retryable -> "retryable"
                UpstreamVerdict.Fatal -> "fatal(401/403)"
                UpstreamVerdict.StreamBroken -> "stream-broken"
            }
            val detail = e.message?.take(120)?.replace('\n', ' ') ?: e.javaClass.simpleName
            return "${candidate.accountId}@$verdictText: $detail"
        }
    }
}
