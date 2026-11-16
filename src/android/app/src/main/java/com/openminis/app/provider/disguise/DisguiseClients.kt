package com.openminis.app.provider.disguise

import com.openminis.app.provider.openai.CodexFingerprint

/**
 * [T-client-disguise-app-level] 可伪装的客户端清单。
 *
 * 「客户端伪装」板块的多选项、左边抽屉、右边抽屉的勾选状态全部来自这里，
 * 是 UI 与请求层共用的唯一真相源。新增一个可伪装客户端 = 在 [ALL] 里加一项
 * + 在对应 provider 的出站头构造里读 [id]。
 *
 * 每个条目只描述「这个客户端长什么样」，不描述「怎么改请求」—— 后者属于
 * 各 provider 的实现（OpenAI 走 Codex 头，Anthropic 走 Claude Code 头）。
 */
data class DisguiseClient(
    val id: String,
    val displayName: String,
    /** 一句说明，UI 里做副标题，讲清它冒充的是哪个官方客户端。 */
    val subtitle: String,
    /** 该客户端适用的 provider 家族，用来过滤右抽屉里的渠道。 */
    val family: Family,
) {
    enum class Family {
        /** OpenAI 兼容（含所有 sub2api / new-api / one-api 中转）。 */
        OPENAI,
        /** Anthropic 兼容（Claude Code 系中转）。 */
        ANTHROPIC,
        /** 任意 provider 都能套的通用身份。 */
        ANY,
    }
}

object DisguiseClients {

    val CODEX_CLI = DisguiseClient(
        id = "codex-cli",
        displayName = "Codex CLI",
        subtitle = "codex-tui · 官方命令行客户端",
        family = DisguiseClient.Family.OPENAI,
    )

    val CODEX_CLI_RS = DisguiseClient(
        id = "codex-cli-rs",
        displayName = "Codex CLI (codex_cli_rs)",
        subtitle = "codex_cli_rs · 经典 UA 前缀",
        family = DisguiseClient.Family.OPENAI,
    )

    val CODEX_DESKTOP = DisguiseClient(
        id = "codex-desktop",
        displayName = "Codex Desktop",
        subtitle = "Codex Desktop · 桌面端",
        family = DisguiseClient.Family.OPENAI,
    )

    val CLAUDE_CODE = DisguiseClient(
        id = "claude-code",
        displayName = "Claude Code",
        subtitle = "claude-cli · Anthropic 官方命令行",
        family = DisguiseClient.Family.ANTHROPIC,
    )

    val OPENCODE = DisguiseClient(
        id = "opencode",
        displayName = "opencode",
        subtitle = "opencode · 第三方开源客户端",
        family = DisguiseClient.Family.OPENAI,
    )

    /** 多选抽屉按这个顺序展示。 */
    val ALL: List<DisguiseClient> = listOf(
        CODEX_CLI,
        CODEX_CLI_RS,
        CODEX_DESKTOP,
        CLAUDE_CODE,
        OPENCODE,
    )

    fun byId(id: String?): DisguiseClient? = ALL.firstOrNull { it.id == id }

    /** 右抽屉里能绑给该客户端的渠道家族。 */
    fun familyOf(id: String?): DisguiseClient.Family =
        byId(id)?.family ?: DisguiseClient.Family.ANY

    /**
     * 把 [DisguiseClient] 映射到 Codex 画像。非 Codex 客户端返回 null ——
     * OpenAI 路径只认 Codex 画像，别的一律不改头。
     */
    fun codexProfileFor(clientId: String?): CodexFingerprint.Profile? = when (clientId) {
        CODEX_CLI.id, OPENCODE.id -> CodexFingerprint.CLI_MACOS_ARM64
        CODEX_CLI_RS.id -> CodexFingerprint.CLI_RS_MACOS_ARM64
        CODEX_DESKTOP.id -> CodexFingerprint.DESKTOP_MACOS_ARM64
        else -> null
    }
}
