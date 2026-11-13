package com.openminis.app.a2a

import com.openminis.app.R
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import kotlin.math.abs

/**
 * A2A 网关的前台服务：保活 + 悬浮小球。
 *
 * 悬浮球失败一律写进 `files/a2a-gateway/bubble.log`，并同时尝试
 * TYPE_APPLICATION_OVERLAY → TYPE_PHONE 两种窗口类型。Honor / 部分国产 ROM
 * 上 appops 的 SYSTEM_ALERT_WINDOW 为 allow、但厂商安全中心仍会拦窗，
 * 这种时候只有日志能说清原因。
 */
class A2aKeepAliveService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null

    /**
     * 网关存活监护。
     *
     * ── 为什么必须有 ────────────────────────────────────────────
     * 网关是个独立子进程（`liba2a_server.so`）。前台服务只把**本进程**
     * 钉在前台，对子进程没有任何约束：内存压力或厂商省电策略把子进程
     * 收掉之后，面板还在、通知还在，但 18901 已经没人监听 —— 用户看到
     * 的是「网关服务冻结了」。
     *
     * 所以前台服务顺带做一件事：定期探活，掉了就拉起来。间隔 15s 兼顾
     * 「发现得快」与「不把电池拖垮」；探活本身丢到后台线程跑 ——
     * `ensureStarted` 内部有锁且最长等待 20s，放主线程会卡住悬浮球。
     */
    private val watchdog = Handler(Looper.getMainLooper())
    private val watchdogTick = object : Runnable {
        override fun run() {
            if (A2aGateway.stopRequested) return
            if (!A2aPrefs.keepAlive(this@A2aKeepAliveService)) return
            // 探活放到后台线程：ensureStarted 内部要 synchronized + 最多 20s
            // 等待，放主线程会卡住 UI（悬浮球都能被拖住）。
            Thread {
                // 用户点过停止后绝不能拉起——否则网关会被 watchdog 在下个周期复活。
                if (A2aGateway.stopRequested) return@Thread
                // 连续两次失败才算「掉了」：单次 isRunning 只有 800ms 超时，
                // 网关正忙（大批量请求/模型刷新）时会短暂探活失败，误判会
                // 触发一次没必要的重启。
                val alive = A2aGateway.isRunning() || run {
                    Thread.sleep(1200)
                    A2aGateway.isRunning()
                }
                if (A2aGateway.stopRequested) return@Thread
                if (!alive) {
                    if (A2aGateway.stopRequested) return@Thread
                    if (A2aGateway.isStartingNow()) return@Thread
                    log("watchdog: gateway offline, respawning")
                    if (A2aGateway.stopRequested) return@Thread
                    if (A2aGateway.isStartingNow()) return@Thread
                    runCatching { A2aGateway.ensureStarted(applicationContext) }
                }
            }.start()
            watchdog.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WATCHDOG -> {
                // AlarmManager 兜底唤醒：厂商省电（MagicOS 的「应用启动管理」/
                // 「省电优化」）会在进程还活着时就把前台服务摘掉，此时既没有
                // START_STICKY 重建、也没有任何回调 —— 只有闹钟能把我们叫回来。
                // 醒来先把前台通知 / FGS 绑定补回去，再重挂快速探活链，最后
                // 续期下一次闹钟，形成闭环。
                ensureForeground()
                watchdog.removeCallbacks(watchdogTick)
                watchdog.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS)
                scheduleWatchdog(this)
                log("WATCHDOG alarm fired: foreground + watchdog rearmed")
                return START_STICKY
            }
            ACTION_SHOW_BUBBLE -> {
                ensureForeground()
                val ok = showBubbleInternal()
                log("SHOW_BUBBLE ok=$ok canDraw=${canDrawOverlay()} sdk=${Build.VERSION.SDK_INT}")
            }
            ACTION_HIDE_BUBBLE -> {
                hideBubble()
                log("HIDE_BUBBLE")
                if (!A2aPrefs.keepAlive(this) && !A2aGateway.isRunningFast()) stopSelf()
            }
            ACTION_STOP -> {
                // 用户主动停止 = 持久意图：冷启动、开机广播、兜底闹钟都不得复活。
                A2aPrefs.setGatewayWanted(this, false)
                // 用户主动停止：必须撤掉兜底闹钟，否则 10 分钟后 ACTION_WATCHDOG
                // 会把刚停掉的服务又拉起来，用户以为「关不掉」。
                cancelWatchdog(this)
                A2aBubble.hide()
                hideBubble()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> ensureForeground()
        }
        // 服务每次被拉起（包括系统回收后 START_STICKY 重启）都重新挂上守护，
        // 先移除旧的避免重复上链。
        watchdog.removeCallbacks(watchdogTick)
        watchdog.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS)
        // 每次被拉起都续期 AlarmManager 兜底，避免「服务被厂商清掉 → 没有
        // 下一次唤醒」的死局。
        scheduleWatchdog(this)
        return START_STICKY
    }

    /**
     * 用户从最近任务里把 App 划掉。
     *
     * Honor / MagicOS 上这不是「只杀 Activity」：前台服务会被一起收掉，而且
     * 不会走 START_STICKY 重建，网关的子进程也随之变成孤儿。这里做两件事：
     * 1. 重新排一次 AlarmManager 兜底闹钟（后台限制下它是最稳的唤醒通道）；
     * 2. 立刻用 startForegroundService 把服务拉回来。
     * 两步都可能被 Android 12+ 的后台启动限制拒绝，所以整段 runCatching 静默。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        runCatching {
            scheduleWatchdog(applicationContext)
            val restart = Intent(this, A2aKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restart)
            } else {
                startService(restart)
            }
            log("onTaskRemoved: watchdog rearmed + service restarted")
        }
    }

    // ── 前台通知 ────────────────────────────────────────────────

    private fun ensureForeground() {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        getString(R.string.a2a_gateway_title),
                        // 抬到 DEFAULT：LOW 的渠道在通知栏里会被压成最底部的
                        // 一行「静默通知」，MagicOS 甚至直接折叠掉，前台服务的
                        // 存在感归零之后厂商省电就会顺手清掉它。
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = getString(R.string.a2a_notif_channel_desc)
                        // 无声音 / 无震动 / 无角标：抬到 DEFAULT 只是为了「可见」，
                        // 绝不能在每次网关重启时响一声打扰用户。
                        // 注意：渠道一旦建成，重要性就只能由用户在系统设置里改，
                        // App 再 createNotificationChannel 不会生效 —— 所以老用户
                        // 的 a2a_gateway 渠道可能仍是 LOW。真正兜底的是下面的
                        // AlarmManager 自唤醒，通知可见性只是锦上添花。
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    },
                )
            }
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, A2aPanelActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle(getString(R.string.a2a_gateway_title))
            .setContentText(getString(R.string.a2a_notif_text_running, A2aGateway.PORT.toString()))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, 0)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Throwable) {
            log("startForeground failed: ${error.javaClass.simpleName} ${error.message}")
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ── 悬浮小球 ────────────────────────────────────────────────

    private fun canDrawOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    /** 供控制页「立即显示悬浮球」直接调用，返回是否加窗成功。 */
    fun showBubbleInternal(): Boolean {
        // 与 A2aBubble.show() 共用同一门禁：网关未运行时不允许加任何悬浮窗。
        // 这条是服务内第二套悬浮球实现（A2 圆球），旧版缺失此拦截，
        // ACTION_SHOW_BUBBLE / showBubbleDirect 都会在网关已停时仍把圆球冒出来。
        if (!A2aGateway.bubbleAllowed()) {
            log("bubble blocked: gateway not running or stopRequested")
            return false
        }
        if (bubble != null) return true
        if (!canDrawOverlay()) {
            log("cannot draw overlay (permission denied)")
            return false
        }

        val size = dp(52)
        val view = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1F6FEB"))
                setStroke(dp(2), Color.parseColor("#3399FF"))
            }
        }
        view.addView(
            TextView(this).apply {
                text = "A2"
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val lp = buildParams(size)

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { windowManager?.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) openPanel()
                    true
                }
                else -> false
            }
        }

        val wm = windowManager ?: return false
        // 先 TYPE_APPLICATION_OVERLAY，被 ROM 拦了就退到 TYPE_PHONE。
        val types: List<Int> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            listOf(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                LEGACY_PHONE_TYPE,
            )
        } else {
            listOf(LEGACY_PHONE_TYPE)
        }
        for (type in types) {
            lp.type = type
            val result = runCatching { wm.addView(view, lp) }
            if (result.isSuccess) {
                bubble = view
                params = lp
                log("bubble added type=$type x=${lp.x} y=${lp.y}")
                return true
            } else {
                log("addView failed type=$type: ${result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }}")
            }
        }
        return false
    }

    private fun buildParams(size: Int) = WindowManager.LayoutParams(
        size,
        size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(12)
        y = dp(240)
    }

    private fun hideBubble() {
        val view = bubble ?: return
        runCatching { windowManager?.removeView(view) }
        bubble = null
        params = null
    }

    private fun openPanel() {
        hideBubble()
        startActivity(
            Intent(this, A2aPanelActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun log(line: String) {
        val text = "${System.currentTimeMillis()} $line\n"
        // 内部目录 shell 读不到；同时写一份到 Download，方便直接查看排查。
        runCatching {
            val internal = File(A2aGateway.rootDir(this), "bubble.log")
            internal.parentFile?.mkdirs()
            internal.appendText(text)
        }
        runCatching {
            val external = File("/sdcard/Download/a2a-bubble.log")
            external.appendText(text)
        }
    }

    override fun onDestroy() {
        watchdog.removeCallbacks(watchdogTick)
        // 只在「用户已关掉保活」时撤闹钟；被系统回收时闹钟必须留着 ——
        // 它是服务被清掉之后唯一还能把自己叫醒的东西。
        if (!A2aPrefs.keepAlive(this)) cancelWatchdog(this)
        hideBubble()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "a2a_gateway"

        /** 网关探活间隔。15s：掉线能及时发现，又不至于把电池拖垮。 */
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        @Suppress("DEPRECATION")
        private const val LEGACY_PHONE_TYPE = WindowManager.LayoutParams.TYPE_PHONE

        @Volatile
        var instance: A2aKeepAliveService? = null
            private set
        private const val NOTIFICATION_ID = 0x2A2A

        const val ACTION_SHOW_BUBBLE = "com.openminis.app.a2a.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.openminis.app.a2a.HIDE_BUBBLE"
        const val ACTION_STOP = "com.openminis.app.a2a.STOP"

        /** AlarmManager 兜底自唤醒：把被厂商清掉的前台服务重新拉回来。 */
        const val ACTION_WATCHDOG = "com.openminis.app.a2a.WATCHDOG"

        /**
         * 兜底闹钟的周期。10 分钟：前台服务被 MagicOS 之类清掉之后，
         * 最长 10 分钟内一定能自动复活；相比 15s 的 Handler 探活，它对
         * 电池几乎无影响，也是 Doze 白名单外仍能触发的粒度。
         */
        private const val WATCHDOG_ALARM_INTERVAL_MS = 10 * 60 * 1000L
        private const val WATCHDOG_REQUEST_CODE = 0x2A2B

        /**
         * 排下一次兜底自唤醒。
         *
         * 用 `setAndAllowWhileIdle` 而不是 `setExactAndAllowWhileIdle`：后者需要
         * SCHEDULE_EXACT_ALARM 特权（Android 12+ 默认不授予，Honor 上更是弹窗
         * 也未必批），而我们要的只是「十分钟内醒一次」，允许系统合并/推迟。
         * PendingIntent 用 getService + FLAG_IMMUTABLE（Android 12+ 对可变量
         * PendingIntent 直接抛异常）+ FLAG_UPDATE_CURRENT（重复排程时刷新）。
         */
        fun scheduleWatchdog(context: Context) = runCatching {
            val app = context.applicationContext
            if (!A2aPrefs.keepAlive(app)) return@runCatching
            val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return@runCatching
            val intent = Intent(app, A2aKeepAliveService::class.java)
                .setAction(ACTION_WATCHDOG)
            val pending = PendingIntent.getService(
                app,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_ALARM_INTERVAL_MS
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
        }

        /** 撤掉兜底自唤醒（用户主动停止 / 关掉保活开关时调用）。 */
        fun cancelWatchdog(context: Context) = runCatching {
            val app = context.applicationContext
            val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return@runCatching
            val intent = Intent(app, A2aKeepAliveService::class.java)
                .setAction(ACTION_WATCHDOG)
            val pending = PendingIntent.getService(
                app,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.cancel(pending)
            pending.cancel()
        }

        /**
         * 启动前台服务。**必须在 App 处于前台时调用**（面板 onResume/onCreate），
         * 否则 Android 12+ 会抛 ForegroundServiceStartNotAllowedException。
         */
        fun start(context: Context) = runCatching {
            val intent = Intent(context, A2aKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            // 用户打开保活开关时就把兜底闹钟排上，不等服务跑到 onStartCommand。
            scheduleWatchdog(context)
        }

        /**
         * 显示悬浮球。走普通 startService —— 此时服务已在前台运行，
         * 后台启动普通服务不受限制；用 startForegroundService 反而会被拦。
         */
        fun showBubble(context: Context) = runCatching {
            context.startService(
                Intent(context, A2aKeepAliveService::class.java).setAction(ACTION_SHOW_BUBBLE),
            )
        }

        /**
         * 同进程直接显示悬浮球。
         *
         * 走 Intent 时，Activity 已经 `onStop`（进程进入后台），Android 会静默
         * 丢掉后台 `startService`，服务收不到 SHOW_BUBBLE —— 这就是「球不出现」
         * 的直接原因。服务和 Activity 同进程，直接拿实例调用最可靠。
         */
        fun showBubbleDirect(): Boolean {
            val service = instance ?: return false
            return runCatching { service.showBubbleInternal() }.getOrElse {
                service.log("showBubbleDirect failed: ${it.javaClass.simpleName} ${it.message}")
                false
            }
        }

        fun hideBubble(context: Context) = runCatching {
            context.startService(
                Intent(context, A2aKeepAliveService::class.java).setAction(ACTION_HIDE_BUBBLE),
            )
        }

        fun stop(context: Context) = runCatching {
            A2aBubble.hide()
            // 先撤闹钟再停服务：反过来的话，stopSelf 之后闹钟仍会把服务叫回来。
            cancelWatchdog(context)
            context.startService(
                Intent(context, A2aKeepAliveService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
