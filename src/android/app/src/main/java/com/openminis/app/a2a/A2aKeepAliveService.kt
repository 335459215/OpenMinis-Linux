package com.openminis.app.a2a

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import kotlin.math.abs

/**
 * A2A 网关的前台服务。
 *
 * 两个职责：
 * 1. **保活**：网关是 app 进程的子进程，把 app 放进前台服务就不会被系统
 *    按「后台空闲」回收，Minis 活着 → 网关活着 → 插件子进程活着。
 * 2. **悬浮小球**：离开面板时留一个可拖动的小图标，点一下回到面板。
 */
class A2aKeepAliveService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BUBBLE -> {
                ensureForeground()
                showBubble()
            }
            ACTION_HIDE_BUBBLE -> {
                hideBubble()
                if (!A2aPrefs.keepAlive(this) && !A2aGateway.isRunning()) {
                    stopSelf()
                }
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
                val channel = NotificationChannel(
                    channelId,
                    "Agent2API 网关",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持本地网关运行，供客户端调用"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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

    private fun showBubble() {
        if (bubble != null) return
        if (!canDrawOverlay()) return

        val size = dp(52)
        val view = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1F6FEB"))
                setStroke(dp(2), Color.parseColor("#3399FF"))
            }
            elevation = dp(6).toFloat()
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

        val lp = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(240)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { windowManager?.updateViewLayout(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) openPanel()
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager?.addView(view, lp) }
            .onSuccess {
                bubble = view
                params = lp
            }
    }

    private fun hideBubble() {
        val view = bubble ?: return
        runCatching { windowManager?.removeView(view) }
        bubble = null
        params = null
    }

    private fun openPanel() {
        // 收起小球，回到面板；面板 onResume 会解冻 WebView。
        hideBubble()
        startActivity(
            Intent(this, A2aPanelActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        hideBubble()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "a2a_gateway"
        private const val NOTIFICATION_ID = 0x2A2A

        const val ACTION_SHOW_BUBBLE = "com.openminis.app.a2a.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.openminis.app.a2a.HIDE_BUBBLE"
        const val ACTION_STOP = "com.openminis.app.a2a.STOP"

        fun showBubble(context: Context) {
            val intent = Intent(context, A2aKeepAliveService::class.java)
                .setAction(ACTION_SHOW_BUBBLE)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun hideBubble(context: Context) {
            context.startService(
                Intent(context, A2aKeepAliveService::class.java)
                    .setAction(ACTION_HIDE_BUBBLE),
            )
        }

        fun start(context: Context) {
            val intent = Intent(context, A2aKeepAliveService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, A2aKeepAliveService::class.java)
                    .setAction(ACTION_STOP),
            )
        }
    }
}
