package com.openminis.app.a2a

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Full-screen WebView for the local Agent2API panel.
 *
 * Loads `/m` (the official phone shell: bottom tab bar, five sections) instead
 * of `/` — the desktop shell renders a sidebar that a phone cannot reach, which
 * is exactly the "只能看到首页、切不了功能" symptom.
 */
class A2aPanelActivity : Activity() {
    private var webView: WebView? = null
    private var titleView: TextView? = null
    private var progress: ProgressBar? = null

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
        val back = TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 24, 0)
            setOnClickListener {
                val web = webView
                if (web != null && web.canGoBack()) web.goBack() else finish()
            }
        }
        header.addView(back)
        titleView = TextView(this).apply {
            text = "Agent2API 网关"
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
        }
        header.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val reload = TextView(this).apply {
            text = "刷新"
            textSize = 14f
            setTextColor(Color.parseColor("#0A84FF"))
            setOnClickListener { webView?.reload() }
        }
        header.addView(reload)
        root.addView(
            header,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 104, Gravity.TOP),
        )

        val web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = false
            loadWithOverviewMode = false
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 强制移动端 UA：服务端靠 UA 判定是否分配 `/m` 手机壳。
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 MinisA2A/1.0"
        }
        web.webViewClient = WebViewClient()
        web.setBackgroundColor(Color.WHITE)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
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
        progress = spinner

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
        val web = webView
        if (web != null && web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
