package com.openminis.app.a2a

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 悬浮小图标（动漫花朵）。
 *
 * 刻意**不依赖 A2aKeepAliveService**：原来的实现要走服务实例或
 * `startService(Intent)`，而它在 Activity `onStop`（进程已后台）时被调用，
 * Android 会丢掉后台 startService，服务根本收不到命令 —— 表现就是
 * 「退出 A2A 面板后没有球，只有彻底退出 Minis 才有」。
 *
 * 这里由 Activity 直接持有 WindowManager 加窗，跟生命周期同步，进程内可用即可。
 * 服务只负责保活，不再管绘制。
 */
object A2aBubble {
    private const val SIZE_DP = 38

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    fun canDraw(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

    fun isShown(): Boolean = view != null

    /** 显示；已存在则直接返回 true。 */
    fun show(context: Context): Boolean {
        if (!A2aGateway.bubbleAllowed()) {
            hide()
            return false
        }
        if (view != null) return true
        if (!canDraw(context)) {
            A2aLog.write(context, "bubble: cannotDrawOverlay")
            return false
        }
        // 用 Application context 加窗：Activity 停掉后它的 context 不适合长期持有窗口。
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val size = dp(context, SIZE_DP)
        val bubble = FlowerView(app)
        bubble.setOnClickListener {
            hide()
            app.startActivity(
                Intent(app, A2aPanelActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
        }

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
            x = dp(context, 8)
            y = dp(context, 200)
        }

        // 拖动 + 点击：超过阈值算拖动，否则算点击（由 setOnClickListener 处理）。
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        bubble.setOnTouchListener { v, event ->
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
                    if (abs(dx) > dp(context, 4) || abs(dy) > dp(context, 4)) moved = true
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        true
                    } else {
                        v.performClick()
                        true
                    }
                }
                else -> false
            }
        }

        return runCatching {
            wm.addView(bubble, lp)
            view = bubble
            params = lp
            windowManager = wm
            A2aLog.write(context, "bubble: shown size=${size}px")
            true
        }.getOrElse {
            A2aLog.write(context, "bubble: addView failed ${it.javaClass.simpleName}: ${it.message}")
            view = null
            params = null
            false
        }
    }

    fun hide() {
        val current = view ?: return
        runCatching { windowManager?.removeView(current) }
        view = null
        params = null
        windowManager = null
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** 六瓣小花 + 黄芯 + 眼睛，纯 Canvas 绘制，不依赖任何图片资源。 */
    private class FlowerView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3A2A33.toInt()
            style = Paint.Style.FILL
        }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        private val cheekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FF7FA8
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f
            val petals = 6
            val petalR = r * 0.40f
            val dist = r * 0.52f

            paint.style = Paint.Style.FILL
            for (i in 0 until petals) {
                val angle = Math.PI * 2 * i / petals - Math.PI / 2
                val px = cx + (dist * cos(angle)).toFloat()
                val py = cy + (dist * sin(angle)).toFloat()
                paint.shader = RadialGradient(
                    px,
                    py,
                    petalR,
                    intArrayOf(0xFFFFF2F8.toInt(), 0xFFFF8FC0.toInt()),
                    floatArrayOf(0.15f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(px, py, petalR, paint)
            }

            // 花芯
            paint.shader = RadialGradient(
                cx,
                cy,
                r * 0.34f,
                intArrayOf(0xFFFFF6C2.toInt(), 0xFFFFC23F.toInt()),
                floatArrayOf(0.2f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r * 0.31f, paint)

            // 眼睛
            val eyeR = r * 0.085f
            val eyeDx = r * 0.12f
            canvas.drawCircle(cx - eyeDx, cy - r * 0.02f, eyeR, eyePaint)
            canvas.drawCircle(cx + eyeDx, cy - r * 0.02f, eyeR, eyePaint)
            canvas.drawCircle(cx - eyeDx + eyeR * 0.3f, cy - r * 0.02f - eyeR * 0.3f, eyeR * 0.34f, highlightPaint)
            canvas.drawCircle(cx + eyeDx + eyeR * 0.3f, cy - r * 0.02f - eyeR * 0.3f, eyeR * 0.34f, highlightPaint)

            // 腮红
            canvas.drawCircle(cx - r * 0.26f, cy + r * 0.10f, r * 0.075f, cheekPaint)
            canvas.drawCircle(cx + r * 0.26f, cy + r * 0.10f, r * 0.075f, cheekPaint)

            // 嘴
            paint.shader = null
            paint.color = 0xFF3A2A33.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r * 0.045f
            paint.strokeCap = Paint.Cap.ROUND
            val m = r * 0.075f
            canvas.drawArc(cx - m, cy + r * 0.03f, cx + m, cy + r * 0.03f + m * 1.4f, 20f, 140f, false, paint)
        }
    }
}
