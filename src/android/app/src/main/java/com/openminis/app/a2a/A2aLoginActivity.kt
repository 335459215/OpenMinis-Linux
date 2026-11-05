package com.openminis.app.a2a

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.openminis.app.browser.WebViewEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Agent2API 账号登录容器。
 *
 * ── 它存在的理由 ────────────────────────────────────────────
 * 面板里的「添加账号」调 `window.open(authUrl)` 打开发卡方的授权页。
 * 在桌面浏览器里这一步是普通新标签页；在 Android WebView 里它落到
 * `WebChromeClient.onCreateWindow`，而面板原来的实现是**在同一个 WebView
 * 实例里**开弹窗 —— 于是授权页和面板共用一份 Cookie 存储。
 *
 * 这有两个后果，都是用户实际踩到的：
 *  1. 一次登录留下的 Cookie 会影响下一次登录（同一个账号反复被认成
 *     已登录，换个账号则可能串号）。
 *  2. 清不掉也隔离不了：想「干净地登录第二个账号」在面板里做不到。
 *
 * 所以登录必须走独立容器：**每次登录新建一份全新的 Cookie/Storage
 * 环境，桌面 UA，横屏**。这个 Activity 就是那个容器。
 *
 * ── 隔离是怎么做到的 ────────────────────────────────────────
 * `androidx.webkit` 1.12.1 提供了多 Profile（`WebViewCompat.setProfile`），
 * 但它要求 WebView 提供方支持 `MULTI_PROFILE`，且同一进程内所有
 * WebView 共享 `CookieManager` 的**默认** profile。本 App 的 WebView 版本
 * 由用户设备决定（minSdk 26，可能是很老的 System WebView），不能假定支持。
 *
 * 因此这里用**每次清空 + 同步等待**的可靠路径：
 *  - `CookieManager.removeAllCookies(latch)` 并**阻塞等到回调**（关键：
 *    不清完就 loadUrl，会带着上一次的会话去请求，等于没隔离）；
 *  - `WebStorage.deleteAllData()` 清 localStorage/IndexedDB；
 *  - `WebViewDatabase.clearFormData()` / `clearHttpAuthUsernamePassword()`
 *    清表单与 HTTP 认证缓存；
 *  - 清完后再显式 `setAcceptThirdPartyCookies(true)`（OAuth 跳转必需）。
 *
 * 代价是登录期间**不能同时用面板**（面板的 Cookie 也被清了，它会在下次
 * 请求时重新登录）。这是有意的取舍：账号隔离比「边登录边看面板」重要。
 */
class A2aLoginActivity : Activity() {

    private var web: WebView? = null
    private var spinner: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val target = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (target.isEmpty()) {
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        // 顶部说明条：让人知道这是「一次性隔离环境」，不是普通浏览器
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F2F2F7"))
            setPadding(dp(20), 0, dp(20), 0)
        }
        bar.addView(
            TextView(this).apply {
                text = "关闭"
                textSize = 15f
                setTextColor(Color.parseColor("#0A84FF"))
                setPadding(0, 0, dp(24), 0)
                setOnClickListener { finish() }
            },
        )
        bar.addView(
            TextView(this).apply {
                text = "隔离登录环境 · 桌面版"
                textSize = 14f
                setTextColor(Color.parseColor("#333333"))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val statusTop = runCatching {
            val res = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (res > 0) resources.getDimensionPixelSize(res) else dp(28)
        }.getOrDefault(dp(28))

        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
                Gravity.TOP,
            ).apply { topMargin = statusTop },
        )

        spinner = ProgressBar(this).apply { visibility = ProgressBar.VISIBLE }
        root.addView(
            spinner,
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER),
        )

        setContentView(root)

        // 先清环境，再创建 WebView 并加载 —— 顺序不能反：
        // WebView 构造后可能已经开始读 Cookie 存储。
        wipeIsolatedEnvironment {
            if (isFinishing || isDestroyed) return@wipeIsolatedEnvironment
            val view = buildWebView()
            web = view
            root.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply { topMargin = statusTop + dp(48) },
            )
            view.loadUrl(target)
        }
    }

    /**
     * 清空当前进程的 WebView 存储，并**等到真的清完**再回调。
     *
     * `removeAllCookies` 是异步的，不等待就继续会导致新页面带着旧 Cookie
     * 发出去。Cookie 清理最多等 3 秒，超时也放行（宁可慢，不能卡死）。
     */
    private fun wipeIsolatedEnvironment(onReady: () -> Unit) {
        val latch = CountDownLatch(1)
        runCatching {
            CookieManager.getInstance().removeAllCookies { latch.countDown() }
        }.onFailure { latch.countDown() }

        Thread {
            runCatching { latch.await(3, TimeUnit.SECONDS) }
            runOnUiThread {
                runCatching {
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    WebViewDatabase.getInstance(this).clearFormData()
                    WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
                }
                onReady()
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val view = WebView(this)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // ── 桌面版渲染 ──────────────────────────────────────
            // useWideViewPort + loadWithOverviewMode 让 WebView 按桌面
            // 宽度解析页面；再配合桌面 UA，站点才会发桌面版布局。
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            // ── 伪装 ────────────────────────────────────────────
            // 每次登录随机一个主流桌面 UA。不是无痕特征：无痕模式会让
            // 站点看到「新会话 + 无历史」，反而更容易被风控判定为异常；
            // 换 UA 则是正常用户在换浏览器，风险低得多。
            userAgentString = DesktopUa.random()
        }
        view.setBackgroundColor(Color.WHITE)
        // setInitialScale 是 WebView 的方法，不在 WebSettings 上。
        // 站点若写死 viewport，用它把版面压到屏幕宽度；useWideViewPort +
        // loadWithOverviewMode 已经让 WebView 按桌面宽度排版了。
        runCatching { view.setInitialScale(desktopInitialScale()) }

        // OAuth 跳转跨域，必须允许第三方 Cookie，否则 Google/微软的回调链会断
        runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(view, true) }
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }

        // 兼容层：把 WebView 的已知兼容问题（老内核、UA 缩减等）补齐
        runCatching { WebViewEngine.applyCompat(view) }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                v: WebView,
                request: WebResourceRequest,
            ): Boolean = false

            override fun onPageFinished(v: WebView, url: String?) {
                spinner?.visibility = ProgressBar.GONE
                maybeEmitCallback(url)
            }
        }
        return view
    }

    /**
     * 桌面版面在窄屏上会溢出，`initialScale` 取一个能把 980px 桌面宽度
     * 压进屏幕的比例。WebView 的 initialScale 是百分比整数。
     */
    private fun desktopInitialScale(): Int {
        val widthPx = resources.displayMetrics.widthPixels
        val density = resources.displayMetrics.density
        val widthDp = if (density > 0) widthPx / density else 400f
        // 目标桌面视口 980dp，换算成百分比并夹在 30%–100%
        val percent = (widthDp / 980f * 100f).toInt()
        return percent.coerceIn(30, 100)
    }

    /**
     * 登录完成的两类信号：
     *  1. 自定义 scheme（`agent2api://` 等）—— 优先，最明确；
     *  2. 指回本机的回调地址（`http://127.0.0.1:18901/...callback...`）——
     *     部分 provider 的 OAuth 重定向落在网关上。
     *
     * 命中后把**完整 URL** 交回给面板，由面板去调
     * `POST /api/session/login/callback`（schema 见后端 session.rs 的
     * `login_callback`：`{ state, callbackUrl }`）。这里不直接发请求：
     * 登录任务是网关侧的，回调必须带着面板手里的那个 state。
     */
    private fun maybeEmitCallback(url: String?) {
        if (url.isNullOrEmpty()) return
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val scheme = parsed.scheme?.lowercase().orEmpty()
        val isCustomScheme = scheme.isNotEmpty() &&
            scheme != "http" && scheme != "https" && scheme != "about"
        val isLocalCallback = (scheme == "http" || scheme == "https") &&
            (parsed.host == "127.0.0.1" || parsed.host == "localhost") &&
            parsed.port == A2aGateway.PORT

        if (!isCustomScheme && !isLocalCallback) return

        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_CALLBACK_URL, url),
        )
        finish()
    }

    override fun onDestroy() {
        // 退出即清：这个容器的全部价值就是「用完不留痕」。
        runCatching {
            web?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
        }
        web = null
        runCatching { CookieManager.getInstance().flush() }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_CALLBACK_URL = "callbackUrl"

        /** 供编码用：从 Activity 里拿到结果。 */
        const val REQUEST_ISOLATED_LOGIN = 0x2A2A

        /**
         * 打开隔离登录环境。调用方用 `registerForActivityResult` 或
         * `startActivityForResult` 接 `EXTRA_CALLBACK_URL`。
         */
        fun intent(context: Context, url: String): Intent =
            Intent(context, A2aLoginActivity::class.java)
                .putExtra(EXTRA_URL, url)

        /** 从返回的 Intent 里读回调 URL。 */
        fun callbackFrom(data: Intent?): String? =
            data?.getStringExtra(EXTRA_CALLBACK_URL)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

/**
 * 桌面浏览器 UA 池。
 *
 * 取当前主流桌面 Chrome / Edge / Firefox 的稳定版形态。要点：
 *  - 必须是**桌面** UA（带 `Windows NT` / `Macintosh`，不带 `Mobile`），
 *    否则站点发移动版，授权页会走另一条风控链路；
 *  - 不能是 HeadlessChrome，那是最直接的爬虫特征；
 *  - 每次登录随机取一条，同一个账号反复登录也会换环境。
 */
internal object DesktopUa {

    private val POOL = listOf(
        // Chrome / Windows
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36",
        // Chrome / macOS
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36",
        // Edge / Windows
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0",
        // Firefox / Windows
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
        // Safari / macOS
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
            "Version/18.1 Safari/605.1.15",
    )

    fun random(): String = POOL.random()
}
