package com.openminis.app.a2a

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Message
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
                    if (A2aGateway.uiDeployed) {
                        // 面板 UI 刚被重铺：清掉旧缓存与旧 Service Worker
                        // （旧 sw.js 会拦截请求，导致新页面永远到不了眼前）。
                        runCatching {
                            web.clearCache(true)
                            CookieManager.getInstance().flush()
                        }
                    }
                    web.loadUrl("http://127.0.0.1:${A2aGateway.PORT}/m?rev=${A2aGateway.uiRevision()}")
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

    /**
     * 面板里 `window.open(authUrl)` 的落点。
     *
     * ── 为什么不再就地开弹窗 ────────────────────────────────────
     * 原来这里用同一个 WebView 实例开弹窗，授权页与面板**共享 Cookie**：
     * 上一个账号留的会话会被下一个账号带进去，想「干净地登第二个号」
     * 在面板里根本做不到。授权页也一直是移动 UA，很多发卡方会直接
     * 把它导向移动版甚至拒绝服务。
     *
     * 现在统一交给 [A2aLoginActivity]：独立环境、桌面 UA、横屏、
     * 每次登录重新清空。回调 URL 由它回传，这里负责提交给网关。
     */
    private fun routeToPopup(url: String): Boolean {
        if (!url.startsWith("http")) return false
        return try {
            startActivityForResult(
                A2aLoginActivity.intent(this, url),
                A2aLoginActivity.REQUEST_ISOLATED_LOGIN,
            )
            true
        } catch (error: Throwable) {
            A2aLog.write(this, "隔离登录容器启动失败: ${error.message}")
            false
        }
    }

    /**
     * 隔离登录回来了：把回调 URL 交给网关收尾。
     *
     * 网关侧 `POST /api/session/login/callback` 要 `{ state, callbackUrl }`，
     * 其中 `state` 是面板 `login/start` 拿到、存在页面 JS 里的那个值 ——
     * 这里拿不到，所以交给页面自己去提交：往 WebView 注入一段脚本，
     * 让它用**自己手里的 state** 完成回调。
     *
     * 这样不必在原生侧复制一份登录状态机，也不会因为 state 对不上被网关拒。
     */
    private fun onIsolatedLoginCallback(callbackUrl: String) {
        val web = webView ?: return
        // 用 JSONObject 转义，避免 URL 里的引号/反斜杠把脚本打断
        val literal = org.json.JSONObject.quote(callbackUrl)
        val script = """
            (function () {
              var url = $literal;
              try {
                window.dispatchEvent(new CustomEvent('a2a-isolated-login', { detail: { callbackUrl: url } }));
              } catch (e) {}
              if (typeof window.__a2aSubmitLoginCallback === 'function') {
                try { window.__a2aSubmitLoginCallback(url); } catch (e) {}
              }
            })();
        """.trimIndent()
        web.evaluateJavascript(script) { result ->
            A2aLog.write(this, "隔离登录回调已注入: $result")
        }
        // 刷新一次让账号列表跟上
        web.reload()
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

    /**
     * 页面不可见：只停渲染与 JS 定时器，**不动网关进程**。
     *
     * ── 这里踩过的坑 ────────────────────────────────────────────
     * 早期实现调了 `WebView.pauseTimers()` 并关掉 `javaScriptEnabled`。
     * `pauseTimers()` 是**进程级静态开关**，一按下去同进程里所有 WebView
     * 的定时器都停 —— 面板自己那套轮询也跟着停，回到前台时若页面已经
     * 断在半路，就只能看到一堆不再更新的数字。
     *
     * 网关是独立的子进程（`liba2a_server.so`），本来就与这里无关；
     * 真正需要保的是**别让面板把 CPU 一直占着**，而不是去停一个只影响
     * 渲染层的定时器。所以现在只做 `onPause()`（停渲染管线 +
     * 释放暂停期间的动画），JS 保持可用，回来直接续上。
     */
    private fun freeze() {
        val web = webView ?: return
        runCatching { web.onPause() }
    }

    /**
     * 回来：恢复渲染，并按需刷新一次页面。
     *
     * 离开期间网关在跑，账号状态、请求计数都在变；面板里的数字是上次
     * 拉取的快照，回来不刷会一直显示旧值。刷新策略：
     *  - 离开超过 [STALE_AFTER_MS] 才刷，避免只是切一下输入法/通知栏
     *    就重载整个面板（重载会丢滚动位置和当前 Tab）；
     *  - 有登录弹窗还开着时不刷（会打断正在进行的授权流程）。
     */
    private fun unfreeze() {
        val web = webView ?: return
        runCatching { web.onResume() }
        val away = if (pausedAt == 0L) 0L else System.currentTimeMillis() - pausedAt
        pausedAt = 0L
        if (popupView != null) return
        if (away < STALE_AFTER_MS) return
        runCatching { web.reload() }
    }

    /** 回到前台时，离开超过这个时长就重载面板（毫秒）。 */
    private val STALE_AFTER_MS = 20_000L

    /** 上次进入后台的时间戳；0 表示当前没在后台。 */
    private var pausedAt = 0L

    override fun onResume() {
        super.onResume()
        unfreeze()
        A2aBubble.hide()
        // 前台时才允许启动前台服务（Android 12+ 的后台启动限制）。
        if (A2aPrefs.keepAlive(this)) A2aKeepAliveService.start(this)
    }

    override fun onPause() {
        // 只有真正不可见时才记时间，多窗口/分屏下 onPause 不一定意味着离开。
        if (!isChangingConfigurations) pausedAt = System.currentTimeMillis()
        freeze()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // 离开面板（回到聊天 / 桌面）：按开关留悬浮球，并确保前台服务在跑。
        // 只要离开面板就显示悬浮球：返回键回 Minis Ultra、切到别的 App、
        // 按 Home 都算。**不判断 isFinishing** —— 按返回键时面板正在结束，
        // 但用户只是回到 Minis Ultra，并不是退出网关，球应该留下。
        // 直接由 Activity 走 WindowManager 加窗，不经过服务：后台
        // startService 会被系统丢弃，那条路根本到不了。
        if (A2aPrefs.floatingBubble(this)) {
            A2aBubble.show(this)
        }
    }

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

    /** 隔离登录容器回来了：把回调交给页面收尾。 */
    @Deprecated("Activity Result API 之外的最短路径，面板本身没有 Fragment 宿主")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != A2aLoginActivity.REQUEST_ISOLATED_LOGIN) return
        if (resultCode != RESULT_OK) {
            A2aLog.write(this, "隔离登录被取消")
            return
        }
        val callback = A2aLoginActivity.callbackFrom(data)
        if (callback.isNullOrEmpty()) {
            A2aLog.write(this, "隔离登录结束但没有回调 URL")
            return
        }
        onIsolatedLoginCallback(callback)
    }

    override fun onDestroy() {
        popupView?.destroy()
        popupView = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
