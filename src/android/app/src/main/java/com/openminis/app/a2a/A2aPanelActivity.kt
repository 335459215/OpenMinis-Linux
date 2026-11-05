package com.openminis.app.a2a

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Gravity
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
import kotlin.concurrent.thread

/**
 * 本地 Agent2API 面板。
 *
 * 两个关键点：
 * 1. 加载 `/m` 而不是 `/` —— `/` 是桌面外壳，左侧边栏在手机上够不着；
 * 2. 必须打开多窗口支持。面板点「打开官方授权页」用的是 `window.open(...)`，
 *    WebView 默认会**静默丢弃**这个请求，于是登录页根本不弹，账号自然写不进去。
 *    这里用 `onCreateWindow` 起一个覆盖层 WebView 承载授权页，cookie 罐与主
 *    WebView 同进程共享，授权完成后网关自己的回调（`/api/session/login/
 *    remote-callback` 或 `callback`）落库，面板轮询 `/login/wait` 后自动刷新账号。
 */
class A2aPanelActivity : Activity() {
    private var webView: WebView? = null
    private var titleView: TextView? = null
    private var popupHost: FrameLayout? = null
    private var popupView: WebView? = null

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
            setOnClickListener { webView?.reload() }
        })
        root.addView(
            header,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 104, Gravity.TOP),
        )

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
        root.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply { topMargin = 104 },
        )

        val spinner = ProgressBar(this).apply { visibility = ProgressBar.VISIBLE }
        root.addView(spinner, FrameLayout.LayoutParams(96, 96, Gravity.CENTER))

        setContentView(root)
        webView = web

        thread(name = "a2a-start") {
            val result = A2aGateway.ensureStarted(applicationContext)
            runOnUiThread {
                spinner.visibility = ProgressBar.GONE
                if (result.isSuccess) {
                    web.loadUrl("http://127.0.0.1:${A2aGateway.PORT}/m")
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
            // 面板与授权页都要 window.open 才能拉起官方登录页。
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
     * `shouldOverrideUrlLoading` 兜底：面板自己跳出去的外链也放进覆盖层，
     * 避免把面板本身导航走、回来还得重新加载。
     */
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
        host.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 104, Gravity.TOP),
        )
        host.addView(
            popup,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply { topMargin = 104 },
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
        // 登录多半刚完成，回主面板刷一下账号。
        webView?.evaluateJavascript("window.dispatchEvent(new Event('a2a-resume'))", null)
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

    override fun onDestroy() {
        popupView?.destroy()
        popupView = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
