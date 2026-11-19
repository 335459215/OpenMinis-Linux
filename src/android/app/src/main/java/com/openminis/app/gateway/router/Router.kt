package com.openminis.app.gateway.router

/**
 * 账号路由的**运行期状态机** —— P2 路由面核心。
 *
 * 语义逐条对照上游（/tmp/a2a-src，v3.5.0-beta.1）：
 *   - 状态分四张内存表：冷却 / 退避 / 垫底 / 熔断（route_state.rs RouteState:74-89），
 *     外加粘性表（route_sticky.rs）与在途计数（connections.rs 同款思想）。
 *     全部进程内存、不落盘：重启清零是**正确**语义（route_state.rs 模块头
 *     「真正要跨重启保留的读数是账号记录里的 rateLimits」—— 本仓那部分在 P1
 *     数据面，Router 只管「接下来这一会儿怎么分」）。
 *   - 决策与状态分开：[plan] 是纯读（对照上游 routing.rs plan_route 的
 *     「输入即输出」），mark_* 是写（route_state.rs 的记账钩子）。
 *
 * 上游把粘性键/游标放在 HTTP 层（会话头、body 哈希）；本仓 Router 收到的
 * [aliasKey] 是调用方（GatewayProvider）已经算好的会话键，Router 只负责
 * 「命中 → 置顶 → 续期 → 成功后绑定」这一段运行期行为。
 */
class Router(private val clock: () -> Long = System::currentTimeMillis) {

    // ── 冷却（429 / 限额）────────────────────────────────────────
    // 键是「accountId → wireModel → 恢复时刻」两级：上游按上游真名记额度
    // （provider_loop.rs 动作 1，1658-1690 的「冷却键 = 上游真名」），同账号
    // 两个 wireModel 的冷却互不影响。到点自动出列（快照时按 now 过滤）。
    private val rateLimits = HashMap<String, HashMap<String, Long>>()

    // ── 退避（route_state.rs backoff:44-47）──────────────────────
    private class BackoffEntry(var until: Long, var strikes: Int)

    private val backoff = HashMap<String, BackoffEntry>()

    // ── 垫底（route_state.rs sink:82；值 = 沉底时刻，TTL 判定用它）──
    private val sink = HashMap<String, Long>()

    // ── 熔断（route_state.rs BreakerEntry:59-68）─────────────────
    private class BreakerEntry {
        var failures: Int = 0
        var openUntil: Long = 0
        var halfOpenSuccesses: Int = 0
    }

    private val breaker = HashMap<String, BreakerEntry>()

    // ── 粘性（route_sticky.rs StickyTable；键 = 调用方给的会话键）──
    private class StickyEntry(var accountId: String, var lastSeen: Long)

    private val sticky = HashMap<String, StickyEntry>()

    // ── 在途计数（acquire/release 维护；plan 过滤并发满）─────────
    private val inFlight = HashMap<String, Int>()

    /**
     * markSuccess 时的绑定目标：[plan] 记下本次会话键，成功的那条候选绑上去。
     * 上游是在转发成功处调 sticky_bind（provider_loop.rs 751-756）；本仓把
     * 「哪个会话键」放在 Router 里记，调用方按「plan → markSuccess」成对使用。
     */
    private var pendingStickyKey: String? = null

    // ── plan：选路（纯读 + 粘性续期）──────────────────────────────

    /**
     * 排出一份可用候选清单。过滤与排序对照上游 routing.rs plan_route
     * （640-780，rotate.rs 200-320 的调用侧）：
     *
     * 1. 过滤 tried（429 换号不回试，由调用方传入）；
     * 2. 过滤冷却中（rateLimits[accountId][wireModel] > now）；
     * 3. 过滤并发满（inFlight ≥ maxConcurrent > 0；0 = 不限，上游
     *    at_concurrency_limit 同口径）；
     * 4. 基础序：priority asc → channelOrder asc → accountId 稳定序
     *    （上游 compare_by_priority 的第二键 addedAt 在本仓由渠道注册序承担）；
     * 5. 粘性命中的可用候选提最前（**必须在基础序之后**，上游 2026-10-13 修：
     *    放在 sort 之前会被冲回原位）；
     * 6. 三层稳定修饰（routing.rs 750-756）：没垫底的在前 → 没退避的在前 →
     *    没熔断的在前。垫底/退避只影响位次不出列；熔断同理 —— 只有全场都熔断
     *    时才轮到它试一次，且按「冷却最早到期」优先（rotate.rs
     *    earliest_limit_reset_at 687-698 的偏好：优先选最可能马上恢复的）。
     */
    fun plan(
        aliasKey: String,
        candidates: List<RouteCandidate>,
        inFlight: Map<String, Int> = emptyMap(),
        tried: Set<String> = emptySet(),
    ): List<RouteCandidate> {
        val now = clock()
        pendingStickyKey = aliasKey

        // 粘性命中 + 滑动续期（route_sticky.rs lookup:45-59：命中即刷新
        // last_seen，TTL 因此按「最近一次往来」滑动）。
        val stickyHit = lookupSticky(aliasKey, now)

        val ordered = candidates.asSequence()
            .filter { it.accountId !in tried }
            .filter { (rateLimits[it.accountId]?.get(it.wireModel) ?: 0L) <= now }
            .filter { !isConcurrentFull(it, inFlight) }
            .sortedWith(
                compareBy({ it.priority }, { it.channelOrder }, { it.accountId })
            )
            .toMutableList()

        // 粘性命中的**可用**候选提最前：不可用的（冷却/并发满/tried）在上面
        // 已被挡掉，走到这里的命中项一定是本轮真能用的（上游 2026-11-03 修：
        // 「有命中」不等于「这次真的用它」）。
        if (stickyHit != null) {
            val index = ordered.indexOfFirst { it.accountId == stickyHit }
            if (index > 0) {
                val hit = ordered.removeAt(index)
                ordered.add(0, hit)
            }
        }

        // 三层稳定修饰（Kotlin 的 sortedWith 是稳定排序，同键保留基础序）。
        val sunkNow = sunkSnapshot(now)
        val restingNow = restingSnapshot(now)
        val openNow = openSnapshot(now)
        var arranged = ordered.sortedBy { sunkNow.contains(it.accountId) }
        arranged = arranged.sortedBy { restingNow.contains(it.accountId) }
        arranged = arranged.sortedBy { openNow.contains(it.accountId) }

        // 全场只剩熔断号：仍试一个 —— 按熔断到期最早者优先（与上游
        // earliest_limit_reset_at「恢复最早先试」同一偏好：宁可打一次试试，
        // 也不把请求直接拒掉，route_state.rs 模块头「宁可打一次」段）。
        if (arranged.isNotEmpty() && arranged.all { openNow.contains(it.accountId) }) {
            arranged = arranged.sortedBy { breaker[it.accountId]?.openUntil ?: Long.MAX_VALUE }
        }
        return arranged
    }

    // ── 并发席位 ────────────────────────────────────────────────

    /** 占一个并发席位。P3 的 failover 循环在开始打这个账号前调用。 */
    fun acquire(accountId: String) {
        inFlight[accountId] = (inFlight[accountId] ?: 0) + 1
    }

    /** 还一个并发席位（acquire 的逆操作，floor 0 防呆）。 */
    fun release(accountId: String) {
        val current = inFlight[accountId] ?: 0
        if (current <= 1) inFlight.remove(accountId) else inFlight[accountId] = current - 1
    }

    /**
     * 并发满判定：maxConcurrent ≤ 0 = 不限（上游 at_concurrency_limit 的
     * 「0 不限」）。计数取 `参数快照` 与 `内部席位` 的较大者 —— 两处是同一事实
     * 的两种来源（调用方自管计数 / 用 acquire-release），任一达到上限即满，
     * 取 max 而不是相加可以避免同一批在途被算两遍。
     */
    private fun isConcurrentFull(candidate: RouteCandidate, inFlight: Map<String, Int>): Boolean {
        if (candidate.maxConcurrent <= 0) return false
        val count = maxOf(inFlight[candidate.accountId] ?: 0, this.inFlight[candidate.accountId] ?: 0)
        return count >= candidate.maxConcurrent
    }

    // ── 记账钩子（上游 provider_loop.rs 的三个动作）──────────────

    /**
     * 一笔失败，按 [UpstreamVerdict] 分类记账（对照 provider_loop.rs
     * 动作 1 / 动作 3 的分派，1683-1690 与 1837-1860）：
     *
     * - [UpstreamVerdict.RateLimited]：冷却（键 = wireModel）+ 垫底。
     *   429 有自己的冷却机制，「等重置」比「退避」精确，**不进**退避/熔断。
     * - [UpstreamVerdict.Retryable] / [UpstreamVerdict.StreamBroken]：
     *   退避（连续翻倍）+ 熔断计数。
     * - [UpstreamVerdict.Fatal]：不可恢复，不罚账号（上游 Fatal 不调
     *   backoff_mark / breaker_failure）。
     */
    fun markFailure(candidate: RouteCandidate, verdict: UpstreamVerdict) {
        val now = clock()
        when (verdict) {
            is UpstreamVerdict.RateLimited -> {
                markRateLimited(candidate, verdict.resetAtMs, now)
                // 垫底（sink）：还有额度仍 429 的账号排到队尾 30min（不翻倍 ——
                // 标记「疑似共享池被抢」，重犯不该加重，route_state.rs SINK_TTL
                // 注释「为什么取 30 分钟」段）。只影响排序，不出列。
                sink[candidate.accountId] = now
            }
            UpstreamVerdict.Retryable, UpstreamVerdict.StreamBroken -> {
                backoffMark(candidate.accountId, now)
                breakerFailure(candidate.accountId, now)
            }
            UpstreamVerdict.Fatal -> Unit
        }
    }

    /**
     * 一笔成功：清退避 + 解垫底 + 熔断恢复计数 + 粘性绑定
     * （provider_loop.rs 1398-1401 的 backoff_clear/sink_clear/breaker_success
     * 三连，与 751-756 的 sticky_bind）。
     */
    fun markSuccess(candidate: RouteCandidate) {
        val now = clock()
        backoff.remove(candidate.accountId)
        sink.remove(candidate.accountId)
        breakerSuccess(candidate.accountId)

        // 粘性绑定：本次 plan 记下的会话键绑到真正成功的那条候选上。上游在
        // 转发成功处调 sticky_bind；失败顺延后成功的往往不是队首那条 ——
        // 绑实际答话的账号，下轮才接得上。
        val key = pendingStickyKey
        if (!key.isNullOrEmpty()) bindSticky(key, candidate.accountId, now)
    }

    /** Auto 档粘性 TTL（上游 StickyMode::Auto → ttl_ms 5min）。 */
    fun stickyTtlMs(): Long = RouteParams.STICKY_TTL_MS

    /**
     * 只读账号健康快照，不改变路由表与选路状态。
     * 多状态共存时熔断 > 退避 > 限额冷却 > 垫底；过期状态等同健康。
     * 同一账号多个模型的冷却取最晚恢复时刻。
     */
    fun accountHealthSnapshot(
        accountIds: Collection<String>,
        nowMs: Long = clock(),
    ): List<RouterAccountHealth> = accountIds.map { accountId ->
        val breakerUntil = breaker[accountId]?.openUntil ?: 0L
        if (breakerUntil > nowMs) {
            return@map RouterAccountHealth(accountId, RouterHealthState.BREAKER_OPEN, breakerUntil)
        }

        val backoffUntil = backoff[accountId]?.until ?: 0L
        if (backoffUntil > nowMs) {
            return@map RouterAccountHealth(accountId, RouterHealthState.BACKOFF, backoffUntil)
        }

        val cooldownUntil = rateLimits[accountId]?.values?.filter { it > nowMs }?.maxOrNull()
        if (cooldownUntil != null) {
            return@map RouterAccountHealth(accountId, RouterHealthState.COOLDOWN, cooldownUntil)
        }

        val sunkAt = sink[accountId]
        if (sunkAt != null && nowMs - sunkAt < RouteParams.SINK_TTL_MS) {
            return@map RouterAccountHealth(accountId, RouterHealthState.RATE_LIMIT_SINK, sunkAt + RouteParams.SINK_TTL_MS)
        }

        RouterAccountHealth(accountId, RouterHealthState.HEALTHY, null)
    }

    // ── 内部：冷却 ──────────────────────────────────────────────

    /**
     * 记一笔限额冷却。上游 Retry-After 给了恢复时刻就用它（[resetAtMs]），
     * 缺省用 60s。**只延不缩**：已有的更晚恢复时刻不缩水（同账号同模型再吃
     * 一次更短的 429 不该把窗口缩短）。
     */
    private fun markRateLimited(candidate: RouteCandidate, resetAtMs: Long?, now: Long) {
        val reset = resetAtMs ?: (now + RouteParams.RATE_LIMIT_DEFAULT_MS)
        val perAccount = rateLimits.getOrPut(candidate.accountId) { HashMap() }
        val existing = perAccount[candidate.wireModel] ?: 0L
        // 只在过期或更晚时写入；0 占位条目顺带清掉。
        if (reset > existing) perAccount[candidate.wireModel] = reset
    }

    // ── 内部：退避 ──────────────────────────────────────────────

    /** 连续失败翻倍（1x/2x/4x/8x 被 MAX 夹住 → 15/30/60/60min），返回休息截止。 */
    private fun backoffMark(accountId: String, now: Long): Long {
        val entry = backoff.getOrPut(accountId) { BackoffEntry(0, 0) }
        entry.strikes += 1
        val factor = 1L shl (entry.strikes - 1).coerceAtMost(3)
        entry.until = now + minOf(RouteParams.BACKOFF_BASE_MS * factor, RouteParams.BACKOFF_MAX_MS)
        return entry.until
    }

    // ── 内部：熔断 ──────────────────────────────────────────────

    /**
     * 一笔失败。半开期再失败 → 重新打开（route_state.rs breaker_failure
     * 495-522 逐条对应：was_open 判定 / 连续计数 / half_open 清零 / 半开重开）。
     */
    private fun breakerFailure(accountId: String, now: Long): Boolean {
        val entry = breaker.getOrPut(accountId) { BreakerEntry() }
        val wasOpen = entry.openUntil > now
        entry.failures += 1
        entry.halfOpenSuccesses = 0
        if (!wasOpen && entry.failures >= RouteParams.BREAKER_THRESHOLD) {
            entry.openUntil = now + RouteParams.BREAKER_TIMEOUT_MS
            return true
        }
        // 半开期再失败：重新打开（openUntil 落在过去但条目还在 = 半开试探）
        if (entry.openUntil > 0 && entry.openUntil <= now) {
            entry.openUntil = now + RouteParams.BREAKER_TIMEOUT_MS
            return true
        }
        return false
    }

    /** 成功：连续失败清零；开过的条目要连续成功 ≥ 阈值才真正关闭。 */
    private fun breakerSuccess(accountId: String) {
        val entry = breaker[accountId] ?: return
        entry.failures = 0
        if (entry.openUntil > 0) {
            entry.halfOpenSuccesses += 1
            if (entry.halfOpenSuccesses >= RouteParams.BREAKER_SUCCESS_THRESHOLD) {
                breaker.remove(accountId)
            }
        }
    }

    // ── 内部：快照（route_state.rs snapshot_for_model 566-616 的过滤口径）──

    /** 垫底快照：TTL 未过才算沉底（上游 2026-10-13 修的自锁）。 */
    private fun sunkSnapshot(now: Long): Set<String> =
        sink.filterValues { now - it < RouteParams.SINK_TTL_MS }.keys.toHashSet()

    /** 退避快照：休息到未来的才算休息中。 */
    private fun restingSnapshot(now: Long): Set<String> =
        backoff.filterValues { it.until > now }.keys.toHashSet()

    /** 熔断快照：open_until 在未来的才挡；过期即半开，不按 open 排序。 */
    private fun openSnapshot(now: Long): Set<String> =
        breaker.filterValues { it.openUntil > now }.keys.toHashSet()

    // ── 内部：粘性 ──────────────────────────────────────────────

    /**
     * 粘性命中 + 滑动续期（route_sticky.rs lookup:45-59 逐条对应）：
     * 空键/零 TTL 不粘；过期条目顺手清掉；命中即刷新 last_seen（TTL 滑动）。
     */
    private fun lookupSticky(key: String, now: Long): String? {
        if (key.isEmpty()) return null
        val entry = sticky[key] ?: return null
        if (now - entry.lastSeen > RouteParams.STICKY_TTL_MS) {
            sticky.remove(key)
            return null
        }
        entry.lastSeen = now
        return entry.accountId
    }

    /** 绑定（route_sticky.rs bind:61-84：容量上限 + 淘汰最旧）。 */
    private fun bindSticky(key: String, accountId: String, now: Long) {
        if (key.isEmpty() || accountId.isEmpty()) return
        if (sticky.size >= RouteParams.STICKY_MAX_ENTRIES && !sticky.containsKey(key)) {
            sticky.entries.minByOrNull { it.value.lastSeen }?.let { sticky.remove(it.key) }
        }
        val entry = sticky.getOrPut(key) { StickyEntry(accountId, now) }
        entry.accountId = accountId
        entry.lastSeen = now
    }
}
