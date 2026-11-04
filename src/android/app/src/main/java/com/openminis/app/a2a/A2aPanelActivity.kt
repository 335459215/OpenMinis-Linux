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
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Full-screen WebView for the local Agent2API panel. The gateway is started
 * lazily; the panel stays usable while the app process is alive.
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
                // Another WebView in this process already set a suffix.
            }
        }

        val root = FrameLayout(this)
        val web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString MinisA2A/1.0"
        }
        web.webViewClient = WebViewClient()
        web.setBackgroundColor(Color.TRANSPARENT)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        root.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val title = TextView(this).apply {
            text = "Agent2API"
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 0, 32, 0)
            setTextColor(Color.parseColor("#444444"))
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }
        root.addView(
            title,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                96,
                Gravity.TOP,
            ),
        )

        val spinner = ProgressBar(this).apply { visibility = ProgressBar.VISIBLE }
        root.addView(spinner, FrameLayout.LayoutParams(96, 96, Gravity.CENTER))

        setContentView(root)
        webView = web
        titleView = title
        progress = spinner

        thread(name = "a2a-start") {
            val result = A2aGateway.ensureStarted(applicationContext)
            runOnUiThread {
                spinner.visibility = ProgressBar.GONE
                if (result.isSuccess) {
                    web.loadUrl("http://127.0.0.1:${A2aGateway.PORT}/")
                } else {
                    title.text = "Agent2API failed: ${result.exceptionOrNull()?.message ?: "unknown"}"
                }
            }
        }
    }

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
