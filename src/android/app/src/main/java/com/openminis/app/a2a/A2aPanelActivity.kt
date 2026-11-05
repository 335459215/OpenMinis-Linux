package com.openminis.app.a2a

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.concurrent.thread

/**
 * 本地 Agent2API 面板。
 *
 * 四个关键点：
 * 1. 加载 `/m`（官方手机壳，底部五标签），不是 `/`（桌面侧边栏，手机上够不着）；
 * 2. 打开 WebView 多窗口，否则「打开官方授权页」的 window.open 被静默丢弃；
 * 3. 顶栏按系统状态栏内边距下移，避免标题被状态栏盖住；
 * 4. 离开页面即冻结 WebView（暂停 JS/定时器/渲染），回到页面瞬时解冻，
 *    同时在通知栏留一个前台服务、在桌面留一个悬浮小球，点击直接回面板。
 */
class A2aPanelActivity : Activity() {
    private var webView: WebView? = null
    private var titleView: TextView? = null
    private var popupHost: FrameLayout? = null
    private var popupView: WebView? = null
    private var headerHeight: Int = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                WebView.setDataDirectorySuffix("a2a")
            } catch (_: Throwable) {
                // 同进程内已设置过分区后缀，忽略。
            }
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        // ── 顶栏（会按状态栏高度下移）───────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F7F7F8"))
            setPadding(28, 0, 28, 0)
        }
        header.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 24, 0)
            setOnClickListener {
                when {
                    closePopupIfAny() -> Unit
                    webView?.canGoBack() == true -> webView?.goBack()
                    else -> finish()
                }
            }
        })
        titleView = TextView(this).apply {
            text = "Agent2API 网关"
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
        }
        header.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(TextView(this).apply {
            text = "刷新"
            textSize = 14f
            setTextColor(Color.parseColor("#0A84FF"))
            setPadding(0, 0, 0, 0)
            setOnClickListener { webView?.reload() }
        })

        val headerParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            104,
            Gravity.TOP,
        )
        root.addView(header, headerParams)

        // ── WebView ────────────────────────────────────────────
        val web = WebView(this)
        configure(web)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()
                return !isLocal(url) && routeToPopup(url)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val popup = WebView(this@A2aPanelActivity)
                configure(popup)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest,
                    ): Boolean = false
                }
                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) {
                        closePopupIfAny()
                    }
                }
                showPopup(popup)
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                closePopupIfAny()
            }
        }
        val webParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { topMargin = 104 }
        root.addView(web, webParams)

        val spinner = ProgressBar(this).apply { visibility = ProgressBar.VISIBLE }
        root.addView(spinner, FrameLayout.LayoutParams(96, 96, Gravity.CENTER))

        setContentView(root)
        webView = web

        // 状态栏内边距：不把标题顶到状态栏下面去。
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            headerParams.topMargin = top
            headerParams.height = dp(52)
            webParams.topMargin = top + dp(52)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        thread(name = "a2a-start") {
            val result = A2aGateway.ensureStarted(applicationContext)
            runOnUiThread {
                spinner.visibility = ProgressBar.GONE
                if (result.isSuccess) {
                    web.loadUrl("http://127.0.0.1:${A2aGateway.PORT}/m")
                    if (A2aPrefs.keepAlive(this@A2aPanelActivity)) {
                        A2aKeepAliveService.start(this@A2aPanelActivity)
                    }
                } else {
                    titleView?.text = "网关启动失败"
                    web.loadDataWithBaseURL(
                        null,
                        failureHtml(result.exceptionOrNull()?.message ?: "未知错误"),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = false
            loadWithOverviewMode = false
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 MinisA2A/1.0"
        }
        view.setBackgroundColor(Color.WHITE)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    }

    private fun isLocal(url: String): Boolean =
        url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")

    private fun routeToPopup(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val popup = WebView(this)
        configure(popup)
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) {
                closePopupIfAny()
            }
        }
        showPopup(popup)
        popup.loadUrl(url)
        return true
    }

    private fun showPopup(popup: WebView) {
        val host = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F7F7F8"))
            setPadding(28, 0, 28, 0)
        }
        bar.addView(TextView(this).apply {
            text = "关闭"
            textSize = 15f
            setTextColor(Color.parseColor("#0A84FF"))
            setPadding(0, 0, 28, 0)
            setOnClickListener { closePopupIfAny() }
        })
        bar.addView(
            TextView(this).apply {
                text = "登录 / 授权"
                textSize = 15f
                setTextColor(Color.parseColor("#333333"))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val statusTop = runCatching {
            val res = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (res > 0) resources.getDimensionPixelSize(res) else dp(28)
        }.getOrDefault(dp(28))
        host.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
                Gravity.TOP,
            ).apply { topMargin = statusTop },
        )
        host.addView(
            popup,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply { topMargin = statusTop + dp(52) },
        )
        (webView?.parent as? ViewGroup)?.addView(
            host,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        popupHost = host
        popupView = popup
    }

    private fun closePopupIfAny(): Boolean {
        val host = popupHost ?: return false
        (host.parent as? ViewGroup)?.removeView(host)
        popupView?.destroy()
        popupView = null
        popupHost = null
        webView?.reload()
        return true
    }

    private fun failureHtml(reason: String): String = """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          body{font-family:system-ui,-apple-system,sans-serif;margin:0;padding:32px;
               background:#fff;color:#333;line-height:1.7}
          h2{font-size:18px;margin:0 0 12px}
          code{background:#f3f3f5;padding:2px 6px;border-radius:4px;font-size:13px;word-break:break-all}
          p{font-size:14px;color:#666}
        </style></head><body>
        <h2>Agent2API 网关未能启动</h2>
        <p>原因：<code>${reason.replace("<", "&lt;")}</code></p>
        <p>可以返回设置页，点「查看网关日志」看最后 300 行输出。</p>
        </body></html>
    """.trimIndent()

    // ── 冻结 / 解冻 ─────────────────────────────────────────────

    /** 页面不可见：暂停 JS 定时器与渲染，几乎不占 CPU。 */
    private fun freeze() {
        val web = webView ?: return
        runCatching {
            web.onPause()
            web.pauseTimers()
            web.settings.javaScriptEnabled = false
        }
    }

    /** 回来：恢复 JS 与渲染。 */
    private fun unfreeze() {
        val web = webView ?: return
        runCatching {
            web.settings.javaScriptEnabled = true
            web.onResume()
            web.resumeTimers()
        }
    }

    override fun onResume() {
        super.onResume()
        unfreeze()
        A2aKeepAliveService.hideBubble(this)
        // 前台时才允许启动前台服务（Android 12+ 的后台启动限制）。
        if (A2aPrefs.keepAlive(this)) A2aKeepAliveService.start(this)
    }

    override fun onPause() {
        freeze()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // 离开面板（回到聊天 / 桌面）：按开关留悬浮球，并确保前台服务在跑。
        if (!isFinishing && A2aPrefs.keepAlive(this)) {
            if (A2aPrefs.floatingBubble(this) && canDrawOverlay()) {
                // 同进程直调：此时进程已在后台，走 Intent 会被系统丢掉。
                val shown = A2aKeepAliveService.showBubbleDirect()
                if (!shown) A2aKeepAliveService.showBubble(this)
            }
        }
    }

    private fun canDrawOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (closePopupIfAny()) return
        val web = webView
        if (web != null && web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        unfreeze()
    }

    override fun onDestroy() {
        popupView?.destroy()
        popupView = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
