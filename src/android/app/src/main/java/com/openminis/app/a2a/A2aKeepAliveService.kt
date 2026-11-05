package com.openminis.app.a2a

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
import android.os.IBinder
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BUBBLE -> {
                ensureForeground()
                val ok = showBubbleInternal()
                log("SHOW_BUBBLE ok=$ok canDraw=${canDrawOverlay()} sdk=${Build.VERSION.SDK_INT}")
            }
            ACTION_HIDE_BUBBLE -> {
                hideBubble()
                log("HIDE_BUBBLE")
                if (!A2aPrefs.keepAlive(this) && !A2aGateway.isRunning()) stopSelf()
            }
            ACTION_STOP -> {
                hideBubble()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> ensureForeground()
        }
        return START_STICKY
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
                        "Agent2API 网关",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "保持本地网关运行，供客户端调用"
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
            .setContentTitle("Agent2API 网关")
            .setContentText("运行中 · 127.0.0.1:${A2aGateway.PORT}")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
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
        hideBubble()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "a2a_gateway"
        @Suppress("DEPRECATION")
        private const val LEGACY_PHONE_TYPE = WindowManager.LayoutParams.TYPE_PHONE

        @Volatile
        var instance: A2aKeepAliveService? = null
            private set
        private const val NOTIFICATION_ID = 0x2A2A

        const val ACTION_SHOW_BUBBLE = "com.openminis.app.a2a.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.openminis.app.a2a.HIDE_BUBBLE"
        const val ACTION_STOP = "com.openminis.app.a2a.STOP"

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
            context.startService(
                Intent(context, A2aKeepAliveService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
