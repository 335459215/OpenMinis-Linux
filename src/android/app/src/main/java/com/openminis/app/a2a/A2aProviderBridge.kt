package com.openminis.app.a2a

import android.content.Context
import android.util.Log
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把本机 Agent2API 网关注册成 Minis Ultra 里的一个 AI 服务商。
 *
 * ── 为什么需要它 ────────────────────────────────────────────
 * 网关起了，但用户还得手动去「AI 服务商 → 添加」填一遍
 * `http://127.0.0.1:18901` 才能用 —— 这两件事明明是同一个 App 里的一体两面。
 * 这里让网关一就绪就自动把对方接上，用户在服务商列表里直接看到
 * 「Agent2API」，展开就是网关当前可用的模型。
 *
 * ── 几个容易踩的点 ──────────────────────────────────────────
 * 1. `appendV1Suffix = true`：网关的 OpenAI 面在 `/v1` 之下（`/v1/models`、
 *    `/v1/chat/completions`），而 Minis 存的是**裸 host**，拼接发生在
 *    请求时。设 false 会打到 `/models` 上 404。
 * 2. `providerType = openAI` + 自定义 baseURL 会让 `addInstance` 走
 *    「第三方 OpenAI 兼容」分支，**故意不种内置模型**，改由
 *    `refreshModels()` 从 `/v1/models` 拉 —— 这正是我们要的：
 *    模型列表跟着网关实时变（用户在网关里加了 provider，这边刷新就有）。
 * 3. 不写 API Key：网关 `AGENT2API_ALLOW_NO_KEY=1` 起，且
 *    `allowsEmptyAPIKey` 对「apiKey + 自定义 baseURL + openAI」返回 true。
 *    写空串反而会让 `usableApiKey()` 判定异常，所以干脆不调 `saveApiKey`。
 * 4. 复用固定 id：重复注册同一 id 会被判定为已存在而跳过，避免每次
 *    启动在服务商列表里堆一串「Agent2API」「Agent2API (2)」。
 */
object A2aProviderBridge {

    private const val TAG = "A2aProviderBridge"

    /** 固定实例 id：换掉它等于换一个服务商，老引用（默认模型槽位）会全部失效。 */
    const val INSTANCE_ID = "builtin-agent2api-local"

    /** 给用户看的名字。 */
    const val LABEL = "Agent2API"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** 网关的 base URL（不带 /v1 —— Minis 会自己拼）。 */
    private fun baseUrl(): String = "http://127.0.0.1:${A2aGateway.PORT}"

    /**
     * 网关没起来时不要注册：否则用户会看到一个连不上的服务商，
     * 模型列表空着，刷新报 connection refused，比没有更糟。
     */
    private fun gatewayReady(): Boolean = A2aHealthProbe.check("${baseUrl()}/health")

    data class SyncResult(val outcome: com.openminis.app.data.repository.ModelRefreshResult, val modelCount: Int)
    private val syncMutex = kotlinx.coroutines.sync.Mutex()

    /** Existing fire-and-forget callers keep their API; interactive callers can await the outcome. */
    fun sync(repository: ProviderRepository, context: Context) {
        scope.launch { syncAwait(repository, context.applicationContext) }
    }

    /** Serializes automatic and manual refreshes and records only a real API success. */
    suspend fun syncAwait(repository: ProviderRepository, context: Context): SyncResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            syncMutex.lock()
            try {
                repository.awaitConfigLoaded()
                val ready = gatewayReady()
                kotlin.coroutines.coroutineContext.ensureActive()
                if (!ready) return@withContext SyncResult(
                    com.openminis.app.data.repository.ModelRefreshResult.CONNECTION_FAILED, 0,
                )
                val instance = synchronized(lock) {
                    repository.instances.find { it.id == INSTANCE_ID } ?: ProviderInstance(
                        id = INSTANCE_ID,
                        label = LABEL,
                        providerType = ProviderType.openAI,
                        credentialType = ProviderCredential.apiKey,
                        customBaseURL = baseUrl(),
                        appendV1Suffix = true,
                        isEnabled = true,
                    ).also { repository.addInstance(it) }
                }
                val outcome = repository.refreshModels(instance, forceRefresh = true)
                val count = repository.visibleEntries(INSTANCE_ID).size
                if (outcome == com.openminis.app.data.repository.ModelRefreshResult.SUCCESS_API) {
                    A2aPrefs.setProviderSynced(context, System.currentTimeMillis())
                }
                SyncResult(outcome, count)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "provider sync failed: ${error.javaClass.simpleName}")
                SyncResult(com.openminis.app.data.repository.ModelRefreshResult.FAILURE, 0)
            } finally {
                syncMutex.unlock()
            }
        }

    /**
     * 从网关 `/v1/models` 读出当前模型 id，仅用于诊断/日志。
     *
     * 真正的模型落库由 `refreshModels()` 完成（它同时处理鉴权、分页、
     * 能力标记），这里不重复实现，只提供一个「网关到底给了什么」的探针。
     */
    fun probeModelIds(): List<String> = try {
        val connection = URL("${baseUrl()}/v1/models").openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 5000
        val body = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val ids = ArrayList<String>(data.length())
        for (index in 0 until data.length()) {
            val id = data.optJSONObject(index)?.optString("id").orEmpty()
            if (id.isNotEmpty()) ids.add(id)
        }
        ids
    } catch (error: Throwable) {
        Log.w(TAG, "failed to read gateway model list", error)
        emptyList()
    }
}
