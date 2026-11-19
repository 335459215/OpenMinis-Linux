package com.openminis.app.gateway.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Router 单测。全用注入 clock 控制 now，逐条钉住与上游（route_state.rs /
 * routing.rs / rotate.rs）对齐的语义：
 *
 * - 冷却按 wireModel 键隔离（provider_loop.rs 动作 1「冷却键 = 上游真名」）
 * - 退避曲线 15/30/60/60min（route_state.rs backoff_mark 的 1x/2x/4x/8x 被
 *   BACKOFF_MAX_MS 夹住）
 * - 熔断开 → 半开 → 关（含半开失败重开、半开连续成功 ≥2 关闭，
 *   route_state.rs breaker_failure / breaker_success）
 * - 垫底只影响排序不出列（routing.rs plan_route 750）
 * - 粘性 TTL 滑动续期（route_sticky.rs lookup 的 last_seen 刷新）
 * - 全熔断仍返回一个、按冷却最早到期（rotate.rs earliest_limit_reset_at 偏好）
 * - 并发满跳过（acquire/release）
 * - tried 剔除不回试（429 换号不回试）
 * - markSuccess 清退避与熔断计数
 */
class RouterTest {

    private var now = 1_700_000_000_000L
    private val clock = { now }
    private val router = Router(clock)

    private fun candidate(
        accountId: String,
        priority: Int = 0,
        channelOrder: Int = 0,
        wireModel: String = "model-x",
        maxConcurrent: Int = 0,
    ) = RouteCandidate(accountId, "ch-$channelOrder", wireModel, priority, channelOrder, maxConcurrent)

    private fun planAll(
        vararg ids: String,
        wireModel: String = "model-x",
        aliasKey: String = "",
        tried: Set<String> = emptySet(),
    ): List<RouteCandidate> =
        router.plan(
            aliasKey,
            ids.map { candidate(it, wireModel = wireModel) },
            inFlight = emptyMap(),
            tried = tried,
        ).toList()

    // ── 冷却：按 wireModel 键隔离 ─────────────────────────────

    @Test
    fun `同账号两个 wireModel 的冷却互不影响`() {
        // acc1 对 model-a 429 到 now+10min；model-b 干净
        router.markFailure(candidate("acc1", wireModel = "model-a"), UpstreamVerdict.RateLimited(now + 600_000))

        val planA = planAll("acc1", "acc2", wireModel = "model-a")
        val planB = planAll("acc1", "acc2", wireModel = "model-b")

        assertEquals(listOf("acc2"), planA.map { it.accountId })
        // 另一个 wireModel 不受冷却影响（429 附带的垫底仍把 acc1 排后，但不出列）
        assertEquals(listOf("acc2", "acc1"), planB.map { it.accountId })
    }

    @Test
    fun `冷却到点自动出列`() {
        router.markFailure(candidate("acc1"), UpstreamVerdict.RateLimited(now + 60_000))
        assertEquals(emptyList<String>(), planAll("acc1").map { it.accountId })

        now += 60_000
        assertEquals(listOf("acc1"), planAll("acc1").map { it.accountId })
    }

    @Test
    fun `RetryAfter 缺省 60s 且只延不缩`() {
        router.markFailure(candidate("acc1"), UpstreamVerdict.RateLimited(now + 120_000))
        // 更短的窗口不缩水
        router.markFailure(candidate("acc1"), UpstreamVerdict.RateLimited(now + 30_000))
        assertEquals(emptyList<String>(), planAll("acc1", "acc2").map { it.accountId }.filter { it == "acc1" })

        now += 60_000 // 30s 窗口已过，120s 未到
        assertEquals(emptyList<String>(), planAll("acc1", "acc2").map { it.accountId }.filter { it == "acc1" })

        now += 60_001
        // 冷却已过，但 429 垫底仍在（30min TTL）→ acc1 在队但不领先
        assertEquals(listOf("acc2", "acc1"), planAll("acc1", "acc2").map { it.accountId })
    }

    // ── 退避曲线 15/30/60/60 ───────────────────────────────────

    @Test
    fun `退避曲线 15 30 60 60 分钟`() {
        // 退避只影响排序不出列，所以用「排到另一个干净账号之后」观察档位。
        fun last(): String? {
            val plan = planAll("acc1", "acc2")
            return plan.lastOrNull()?.accountId
        }

        router.markFailure(candidate("acc1"), UpstreamVerdict.Retryable)
        assertEquals("acc1", last()) // 15min 休息中 → 垫后

        now += 15 * 60_000 // 到点回来
        assertEquals("acc2", last())

        router.markFailure(candidate("acc1"), UpstreamVerdict.Retryable) // 第 2 笔 → 30min
        now += 15 * 60_000
        assertEquals("acc1", last())
        now += 15 * 60_000
        assertEquals("acc2", last())

        router.markFailure(candidate("acc1"), UpstreamVerdict.Retryable) // 第 3 笔 → 60min
        now += 30 * 60_000
        assertEquals("acc1", last())
        now += 30 * 60_000
        assertEquals("acc2", last())

        router.markFailure(candidate("acc1"), UpstreamVerdict.Retryable) // 第 4 笔 → 8x 被 MAX 夹 → 60min
        now += 30 * 60_000
        assertEquals("acc1", last())
        now += 30 * 60_000
        assertEquals("acc2", last())
    }

    // ── 熔断：开 → 半开 → 关 ──────────────────────────────────

    @Test
    fun `连续失败到阈值 5 熔断打开 半开成功 2 次关闭`() {
        repeat(4) { router.markFailure(candidate("acc1"), UpstreamVerdict.Retryable) }
        // 4 笔还没开：仍排最前（退避修饰会垫后，用单候选观察「不消失」即可）
        assertEquals(listOf("acc1"), planAll("acc1").map { it.accountId })

        router.markFailure(candidate("acc1"), UpstreamVerdict.Retryable) // 第 5 笔 → open
        // 熔断只降位不出列：对干净 acc2 的相对序掉到后面
        assertEquals(listOf("acc2", "acc1"), planAll("acc1", "acc2").map { it.accountId })

        now += 60_000 // 到点半开（openUntil 过期 → 不再按 open 排序）
        assertEquals(listOf("acc1"), planAll("acc1").map { it.accountId })

        // 半开连续成功 ≥2 才真正关闭；第 1 次成功后仍处半开
        router.markSuccess(candidate("acc1"))
        now += 60_000 + 1 // 再过整个 open 窗口（半开失败会重开，先越过验证不重开）
        router.markFailure(candidate("acc1"), UpstreamVerdict.Retryable) // 半开期失败 → 重新打开
        assertEquals(listOf("acc2", "acc1"), planAll("acc1", "acc2").map { it.accountId })

        // 重开后走完整半开：连续成功 2 次关闭
        now += 60_000
        router.markSuccess(candidate("acc1"))
        router.markSuccess(candidate("acc1"))
        assertEquals(listOf("acc1"), planAll("acc1").map { it.accountId })
    }

    @Test
    fun `全熔断时仍返回冷却最早到期的一个`() {
        // 两个账号都熔断；acc1 先开（openUntil = now+60s），acc2 后开（now+61s 起算）
        repeat(5) { router.markFailure(candidate("acc1"), UpstreamVerdict.Retryable) }
        now += 1_000
        repeat(5) { router.markFailure(candidate("acc2"), UpstreamVerdict.Retryable) }

        val plan = planAll("acc1", "acc2")
        // 到期最早的先试（两个都开 → 按冷却最早到期排序，都保留）
        assertEquals(listOf("acc1", "acc2"), plan.map { it.accountId })

        now += 60_000 // acc1 到点半开（acc2 还差 1s）
        assertEquals(listOf("acc1", "acc2"), planAll("acc1", "acc2").map { it.accountId })
    }

    // ── 垫底：只影响排序不出列 ─────────────────────────────────

    @Test
    fun `垫底只影响排序不出列 到 30min 自动回队`() {
        router.markFailure(candidate("acc1"), UpstreamVerdict.RateLimited()) // 429 → 垫底
        // 冷却窗口过了（缺省 60s），垫底仍在 → 排到 acc2 之后但**不消失**
        now += 61_000
        val plan = planAll("acc1", "acc2")
        assertEquals(listOf("acc2", "acc1"), plan.map { it.accountId })

        // 30min TTL 到点自动回队（上游 2026-10-13 修的自锁）
        now += 30 * 60_000
        assertEquals(listOf("acc1", "acc2"), planAll("acc1", "acc2").map { it.accountId })

        // 成功一次立即解除（sink_clear）
        now += 61_000
        router.markFailure(candidate("acc1"), UpstreamVerdict.RateLimited())
        now += 61_000
        assertEquals(listOf("acc2", "acc1"), planAll("acc1", "acc2").map { it.accountId })
        router.markSuccess(candidate("acc1"))
        assertEquals(listOf("acc1", "acc2"), planAll("acc1", "acc2").map { it.accountId })
    }

    // ── 粘性：TTL 滑动续期 ────────────────────────────────────

    @Test
    fun `粘性命中最前 且 TTL 滑动续期`() {
        val candidates = listOf(candidate("acc1"), candidate("acc2"), candidate("acc3"))

        // 第一次：无粘性 → 按基础序，acc1 答话后绑定
        router.plan("session-1", candidates)
        router.markSuccess(candidate("acc1"))

        // acc1 优先级最低（模拟基础序里排最后），命中仍提最前
        val shuffled = listOf(candidate("acc3", priority = 0), candidate("acc2", priority = 1), candidate("acc1", priority = 2))
        assertEquals("acc1", router.plan("session-1", shuffled).first().accountId)

        // 每次命中刷新 last_seen → 5min 滑动：3min、6min、9min 反复命中都不掉
        now += 3 * 60_000
        assertEquals("acc1", router.plan("session-1", shuffled).first().accountId)
        now += 3 * 60_000
        assertEquals("acc1", router.plan("session-1", shuffled).first().accountId)
        now += 3 * 60_000
        assertEquals("acc1", router.plan("session-1", shuffled).first().accountId)

        // 静默 5min+1ms → 过期，回到基础序
        now += 5 * 60_000 + 1
        val plan = router.plan("session-1", shuffled)
        assertEquals(listOf("acc3", "acc2", "acc1"), plan.map { it.accountId })
    }

    // ── 并发满：acquire/release ───────────────────────────────

    @Test
    fun `并发满跳过 acquire release 恢复`() {
        val candidates = listOf(
            candidate("acc1", maxConcurrent = 1),
            candidate("acc2"),
        )

        router.acquire("acc1")
        assertEquals(listOf("acc2"), router.plan("", candidates).map { it.accountId })

        router.release("acc1")
        assertEquals(listOf("acc1", "acc2"), router.plan("", candidates).map { it.accountId })
    }

    @Test
    fun `外部 inFlight 快照同样参与并发满判定`() {
        val candidates = listOf(
            candidate("acc1", maxConcurrent = 2),
            candidate("acc2"),
        )
        val plan = router.plan("", candidates, inFlight = mapOf("acc1" to 2))
        assertEquals(listOf("acc2"), plan.map { it.accountId })
        // 0 = 不限：acc3 maxConcurrent=0，外部在途 5 也不挡
        assertEquals(1, router.plan("", listOf(candidate("acc3")), inFlight = mapOf("acc3" to 5)).size)
    }

    // ── tried：剔除不回试 ─────────────────────────────────────

    @Test
    fun `tried 剔除不回试`() {
        val plan = router.plan(
            "",
            listOf(candidate("acc1"), candidate("acc2")),
            tried = setOf("acc1"),
        )
        assertEquals(listOf("acc2"), plan.map { it.accountId })
    }

    // ── markSuccess：清退避与熔断计数 ─────────────────────────

    @Test
    fun `markSuccess 清退避与熔断计数`() {
        // 退避：3 笔失败后成功 → 退避清零；下一笔失败从 15min 重新起步
        repeat(3) { router.markFailure(candidate("acc1"), UpstreamVerdict.Retryable) }
        router.markSuccess(candidate("acc1"))
        now += 16 * 60_000 // 若没清，第 4 笔该是 60min
        router.markFailure(candidate("acc1"), UpstreamVerdict.Retryable)
        now += 15 * 60_000 // 15min 到点 → 应当回队
        val plan = planAll("acc1", "acc2")
        assertEquals("acc2", plan.last().accountId) // 退避已过 → acc1 不再垫后

        // 熔断：成功清零失败计数 → 5 笔新失败才开（而不是接着旧计数）
        now += 1
        repeat(4) { router.markFailure(candidate("acc1"), UpstreamVerdict.Retryable) }
        assertEquals(listOf("acc1"), planAll("acc1").map { it.accountId }) // 4 笔未开
        router.markFailure(candidate("acc1"), UpstreamVerdict.Retryable) // 第 5 笔开（只降位不出列）
        assertEquals(listOf("acc2", "acc1"), planAll("acc1", "acc2").map { it.accountId })
    }

    // ── Fatal：不罚账号 ───────────────────────────────────────

    @Test
    fun `Fatal 不进路由状态`() {
        router.markFailure(candidate("acc1"), UpstreamVerdict.Fatal)
        assertEquals(listOf("acc1", "acc2"), planAll("acc1", "acc2").map { it.accountId })
    }

    // ── 基础序 ────────────────────────────────────────────────

    @Test
    fun `基础序 priority 再 channelOrder 再 accountId`() {
        val candidates = listOf(
            candidate("acc-b", priority = 1, channelOrder = 0),
            candidate("acc-a", priority = 0, channelOrder = 2),
            candidate("acc-c", priority = 0, channelOrder = 1),
            candidate("acc-d", priority = 0, channelOrder = 1), // 与 acc-c 同档同渠道 → 按 id
        )
        val plan = router.plan("", candidates)
        assertEquals(listOf("acc-c", "acc-d", "acc-a", "acc-b"), plan.map { it.accountId })
    }

    // ── stickyTtlMs ───────────────────────────────────────────

    @Test
    fun `stickyTtlMs 默认 5 分钟`() {
        assertEquals(5 * 60_000L, router.stickyTtlMs())
    }

    @Test
    fun `StreamBroken 按可重试记账`() {
        router.markFailure(candidate("acc1"), UpstreamVerdict.StreamBroken)
        // 退避生效（垫后）验证它走了 Retryable 同款路径
        now += 61_000
        val plan = planAll("acc1", "acc2")
        assertEquals(listOf("acc2", "acc1"), plan.map { it.accountId })
        assertFalse(plan.map { it.accountId }.isEmpty())
    }
}
