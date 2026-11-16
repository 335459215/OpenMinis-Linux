package com.openminis.app.provider.openai

import java.security.MessageDigest
import java.util.UUID

/**
 * [T-codex-client-fingerprint] Codex 客户端身份指纹生成器。
 *
 * 用途：让走自定义 OpenAI 兼容中转站（sub2api / codex2api / new-api / one-api 系）
 * 的 provider 也能以「官方 Codex 客户端」的身份出站，从而通过中转站的
 * `codex_cli_only` 准入校验。
 *
 * 规范来源（全部取自真实源码，不是猜的）：
 *  - openai/codex  codex-rs/login/src/auth/default_client.rs
 *        DEFAULT_ORIGINATOR = "codex_cli_rs"
 *        UA = "{originator}/{version} ({os_type} {os_version}; {arch}) {terminal}"
 *  - openai/codex  codex-rs/codex-api/src/requests/headers.rs
 *        build_session_headers -> "session-id" / "thread-id"
 *  - openai/codex  codex-rs/core/src/responses_metadata.rs
 *        turn metadata 键集合
 *  - james-6-23/codex2api  proxy/codex_fingerprint.go / proxy/useragent.go
 *        抓包实证：installation 是 UUIDv4；session/thread/window 是 UUIDv7；
 *        installation_id 只进 body 的 client_metadata，不作为独立请求头发送
 *  - james-6-23/codex2api  proxy/executor.go:1375
 *        部分中转按 x-codex-app-version 判定官方客户端，缺它直接 403
 *        {"error":{"type":"forbidden_error",
 *                  "message":"This account only allows Codex official clients"}}
 *  - icebear0828/codex-proxy  src/fingerprint/manager.ts
 *        头顺序固定；Authenticated 头集合
 */
object CodexFingerprint {

    /** 与 OpenAIProvider.CODEX_CLIENT_VERSION 保持同源，避免两处漂移。 */
    const val DEFAULT_VERSION = "0.169.0"

    /** 画像：originator 同时是 UA 前缀与 originator 头，真实客户端两者恒等。 */
    data class Profile(
        val originator: String,
        val osName: String,
        val osVersion: String,
        val arch: String,
        val terminal: String,
        val appName: String,
    ) {
        fun userAgent(version: String = DEFAULT_VERSION): String =
            "$originator/$version ($osName $osVersion; $arch) $terminal ($appName; $version)"
    }

    /** 抓包最常见的形态。 */
    val CLI_MACOS_ARM64 = Profile(
        originator = "codex-tui",
        osName = "Mac OS",
        osVersion = "15.5.0",
        arch = "arm64",
        terminal = "xterm-256color",
        appName = "codex-tui",
    )

    /** 官方 docs 与大量抓包里的经典形态。 */
    val CLI_RS_MACOS_ARM64 = Profile(
        originator = "codex_cli_rs",
        osName = "Mac OS",
        osVersion = "15.5.0",
        arch = "arm64",
        terminal = "xterm-256color",
        appName = "codex_cli_rs",
    )

    val DEFAULT_PROFILE: Profile = CLI_MACOS_ARM64

    /** 一次请求的完整身份集合。同 seed 稳定，turn_id 每轮新建。 */
    data class Identity(
        val installationId: String,
        val sessionId: String,
        val threadId: String,
        val windowId: String,
        val turnId: String,
        val contextWindowId: String,
        val turnStartedAtUnixMs: Long,
    ) {
        /** x-codex-turn-metadata 头的值，也是 body 里的同名字段。 */
        fun turnMetadataJson(): String = buildString {
            append('{')
            append("\"installation_id\":\"").append(installationId).append("\",")
            append("\"session_id\":\"").append(sessionId).append("\",")
            append("\"thread_id\":\"").append(threadId).append("\",")
            append("\"turn_id\":\"").append(turnId).append("\",")
            append("\"window_id\":\"").append(windowId).append("\",")
            append("\"turn_started_at_unix_ms\":").append(turnStartedAtUnixMs).append(',')
            append("\"context_window_id\":\"").append(contextWindowId).append('"')
            append('}')
        }
    }

    // ── 确定性 UUID 派生 ────────────────────────────────────────────────────

    private fun sha256(seed: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))

    /**
     * 确定性 UUIDv4。只用于 installation_id。
     *
     * 直接把 sha256 塞进 UUID 会得到 v5，版本位一错本身就是可识别特征。
     */
    fun uuid4FromSeed(seed: String): String {
        val b = sha256(seed).copyOf(16)
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()   // version 4
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()   // RFC 4122 variant
        return formatUuid(b)
    }

    /** 确定性 UUIDv7：48bit 毫秒时间戳 + 随机位，版本位 7。 */
    fun uuid7FromSeed(seed: String, unixMs: Long): String {
        val digest = sha256(seed)
        val b = ByteArray(16)
        // 前 6 字节 = 时间戳高 48 位
        b[0] = ((unixMs ushr 40) and 0xFFL).toByte()
        b[1] = ((unixMs ushr 32) and 0xFFL).toByte()
        b[2] = ((unixMs ushr 24) and 0xFFL).toByte()
        b[3] = ((unixMs ushr 16) and 0xFFL).toByte()
        b[4] = ((unixMs ushr 8) and 0xFFL).toByte()
        b[5] = (unixMs and 0xFFL).toByte()
        System.arraycopy(digest, 6, b, 6, 10)
        b[6] = ((b[6].toInt() and 0x0F) or 0x70).toByte()   // version 7
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()   // variant
        return formatUuid(b)
    }

    private fun formatUuid(b: ByteArray): String {
        val hex = StringBuilder(36)
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) hex.append('-')
            val v = b[i].toInt() and 0xFF
            hex.append(Character.forDigit(v shr 4, 16))
            hex.append(Character.forDigit(v and 0x0F, 16))
        }
        return hex.toString()
    }

    /** 把 seed 映到一个稳定但分散的毫秒时间戳，避免所有实例时间戳相同。 */
    private fun epochMsFor(seed: String): Long {
        val d = sha256(seed)
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xFFL)
        val spread = Math.floorMod(v, 180L * 24 * 3600 * 1000)
        return 1_735_689_600_000L + spread  // 2025-01-01 起
    }

    /** 按 seed 派生一整套稳定身份，turn_id 每次新建。 */
    fun newIdentity(seed: String, nowMs: Long = System.currentTimeMillis()): Identity {
        val session = uuid7FromSeed("codex:session:v1:$seed", epochMsFor(seed))
        return Identity(
            installationId = uuid4FromSeed("codex:installation:v1:$seed"),
            sessionId = session,
            threadId = session,
            windowId = "$session:0",
            turnId = UUID.randomUUID().toString(),  // 逐轮随机，真实客户端同为 now_v7
            contextWindowId = uuid7FromSeed("codex:context-window:v1:$seed", epochMsFor(seed) + 1),
            turnStartedAtUnixMs = nowMs,
        )
    }

    // ── 出站头 ──────────────────────────────────────────────────────────────

    /**
     * 自定义中转站用的 Codex 头集合（有序）。
     *
     * 顺序即协议的一部分：严格风控侧可观测到顺序差异，所以这里返回
     * List 而不是 Map，调用方按序 header() 写入。
     *
     * [accept] 默认两者并写 —— 中转站的 OpenAI 兼容层按这个值分流，
     * 只给 text/event-stream 会被当非法。
     */
    fun headers(
        identity: Identity,
        version: String = DEFAULT_VERSION,
        profile: Profile = DEFAULT_PROFILE,
        accept: String = "application/json, text/event-stream",
    ): List<Pair<String, String>> = listOf(
        "originator" to profile.originator,
        "User-Agent" to profile.userAgent(version),
        "Version" to version,
        "session-id" to identity.sessionId,
        "thread-id" to identity.threadId,
        "x-client-request-id" to identity.threadId,
        "x-codex-window-id" to identity.windowId,
        // 中转站按这个头判定官方客户端，缺它直接 403 forbidden_error
        "x-codex-app-version" to version,
        "x-codex-turn-metadata" to identity.turnMetadataJson(),
        "OpenAI-Beta" to "responses=experimental",
        "Accept" to accept,
    )

    /**
     * 给 Responses 请求体打上 Codex 的 client_metadata。
     *
     * installation_id 只在这里出现，不作为独立请求头发送 —— 真实 CLI 就是这样。
     * 只补真实客户端一定会带的键，不改其它字段。
     */
    fun applyToBody(body: org.json.JSONObject, identity: Identity) {
        val cm = body.optJSONObject("client_metadata") ?: org.json.JSONObject()
        if (!cm.has("session_id")) cm.put("session_id", identity.sessionId)
        if (!cm.has("thread_id")) cm.put("thread_id", identity.threadId)
        if (!cm.has("turn_id")) cm.put("turn_id", identity.turnId)
        if (!cm.has("x-codex-installation-id")) {
            cm.put("x-codex-installation-id", identity.installationId)
        }
        if (!cm.has("x-codex-turn-metadata")) {
            cm.put("x-codex-turn-metadata", identity.turnMetadataJson())
        }
        body.put("client_metadata", cm)
    }

    /** 由 provider 实例 id 派生稳定 seed：同一实例每次启动得到同一组身份。 */
    fun seedFor(instanceId: String?, modelId: String?): String =
        "minis-codex:" + (instanceId ?: "default") + ":" + (modelId ?: "model")
}
