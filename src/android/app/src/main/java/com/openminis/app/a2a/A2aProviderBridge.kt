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
    private fun gatewayReady(): Boolean = try {
        val connection = URL("${baseUrl()}/health").openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        connection.requestMethod = "GET"
        val code = connection.responseCode
        connection.disconnect()
        code in 200..299
    } catch (error: Throwable) {
        false
    }

    /**
     * 网关就绪后调用。幂等：已注册就只补刷新，不重复建实例。
     *
     * 必须在后台线程跑（内部已切 IO），调用方直接调用即可。
     */
    fun sync(repository: ProviderRepository, context: Context) {
        scope.launch {
            try {
                if (!gatewayReady()) {
                    Log.i(TAG, "网关未就绪，跳过服务商注册")
                    return@launch
                }
                val existing = synchronized(lock) {
                    repository.instances.find { it.id == INSTANCE_ID }
                }
                if (existing == null) {
                    val instance = ProviderInstance(
                        id = INSTANCE_ID,
                        label = LABEL,
                        providerType = ProviderType.openAI,
                        credentialType = ProviderCredential.apiKey,
                        customBaseURL = baseUrl(),
                        // 网关的 OpenAI 面在 /v1 下，必须让 Minis 补后缀
                        appendV1Suffix = true,
                        isEnabled = true,
                    )
                    synchronized(lock) { repository.addInstance(instance) }
                    Log.i(TAG, "已注册内置服务商「$LABEL」 → ${baseUrl()}")
                }
                // 无论新建还是已存在，都拉一次模型：网关侧的模型集合会变
                val instance = synchronized(lock) { repository.instances.find { it.id == INSTANCE_ID } }
                if (instance != null) {
                    val result = repository.refreshModels(instance)
                    Log.i(TAG, "模型刷新结果: $result")
                }
                A2aPrefs.setProviderSynced(context, System.currentTimeMillis())
            } catch (error: Throwable) {
                // 注册失败绝不能影响网关本身 —— 用户还能手动添加
                Log.w(TAG, "服务商同步失败", error)
            }
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
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val ids = ArrayList<String>(data.length())
        for (index in 0 until data.length()) {
            val id = data.optJSONObject(index)?.optString("id").orEmpty()
            if (id.isNotEmpty()) ids.add(id)
        }
        ids
    } catch (error: Throwable) {
        Log.w(TAG, "读取网关模型列表失败", error)
        emptyList()
    }
}
