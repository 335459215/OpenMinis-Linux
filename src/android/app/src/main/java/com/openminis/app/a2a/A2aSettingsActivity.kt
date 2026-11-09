package com.openminis.app.a2a

import com.openminis.app.R
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Agent2API 网关控制页：状态、启停、保活、悬浮球、授权、日志。
 */
class A2aSettingsActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var busy: ProgressBar
    private lateinit var keepAliveSwitch: Switch
    private lateinit var bubbleSwitch: Switch
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.WHITE)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = getString(R.string.a2a_action_back)
            textSize = 15f
            setTextColor(Color.parseColor("#0A84FF"))
            setPadding(0, 0, 0, 24)
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.a2a_gateway_title)
            textSize = 22f
            setTextColor(Color.parseColor("#222222"))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.a2a_gateway_subtitle)
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 0)
        })

        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, 28, 0, 0)
            setTextColor(Color.DKGRAY)
            setLineSpacing(8f, 1f)
        }
        root.addView(statusView)

        busy = ProgressBar(this).apply { visibility = ProgressBar.GONE }
        root.addView(busy)

        root.addView(button(getString(R.string.a2a_action_open_panel)) {
            startActivity(Intent(this, A2aPanelActivity::class.java))
        })
        root.addView(button(getString(R.string.a2a_action_start)) { action { A2aGateway.ensureStarted(applicationContext) } })
        root.addView(button(getString(R.string.a2a_action_stop)) {
            A2aGateway.stop()
            A2aKeepAliveService.stop(this)
            Toast.makeText(this, getString(R.string.a2a_toast_stopped), Toast.LENGTH_SHORT).show()
            refresh()
        })
        root.addView(button(getString(R.string.a2a_action_restart)) { action { A2aGateway.restart(applicationContext) } })

        // ── 保活 / 悬浮球 ──────────────────────────────────
        root.addView(TextView(this).apply {
            text = getString(R.string.a2a_section_background)
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 32, 0, 4)
        })

        keepAliveSwitch = Switch(this).apply {
            text = getString(R.string.a2a_keepalive_title)
            textSize = 15f
            isChecked = A2aPrefs.keepAlive(this@A2aSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                A2aPrefs.setKeepAlive(this@A2aSettingsActivity, checked)
                if (checked) {
                    A2aKeepAliveService.start(this@A2aSettingsActivity)
                } else {
                    A2aKeepAliveService.stop(this@A2aSettingsActivity)
                }
            }
        }
        root.addView(keepAliveSwitch)
        root.addView(TextView(this).apply {
            text = getString(R.string.a2a_keepalive_desc)
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 16)
        })

        bubbleSwitch = Switch(this).apply {
            text = getString(R.string.a2a_overlay_title)
            textSize = 15f
            isChecked = A2aPrefs.floatingBubble(this@A2aSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                A2aPrefs.setFloatingBubble(this@A2aSettingsActivity, checked)
                if (checked) {
                    if (canDrawOverlay()) {
                        // 与 PanelActivity.onStop 共用 A2aBubble.show()；
                        // 该入口内部按 bubbleAllowed() = !stopRequested && isRunning()
                        // 拦截，所以网关未运行/已停止时这里不加窗，也不自启。
                        A2aBubble.show(this@A2aSettingsActivity)
                    } else {
                        requestOverlay()
                    }
                } else {
                    A2aBubble.hide()
                }
            }
        }
        root.addView(bubbleSwitch)
        root.addView(TextView(this).apply {
            text = getString(R.string.a2a_overlay_desc)
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 16)
        })

        root.addView(button(getString(R.string.a2a_action_grant_overlay)) { requestOverlay() })
        root.addView(button(getString(R.string.a2a_action_clear_cookies)) { clearCookies() })
        root.addView(button(getString(R.string.a2a_action_view_log)) { showLog() })

        root.addView(TextView(this).apply {
            text = getString(R.string.a2a_endpoints)
            textSize = 13f
            setPadding(0, 28, 0, 0)
            setTextColor(Color.GRAY)
            setLineSpacing(6f, 1f)
        })

        setContentView(scroll)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 10 }
    }

    private fun canDrawOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        } else {
            Toast.makeText(this, getString(R.string.a2a_toast_overlay_granted), Toast.LENGTH_SHORT).show()
        }
    }

    private fun action(block: () -> Result<Unit>) {
        busy.visibility = ProgressBar.VISIBLE
        thread(name = "a2a-action") {
            val result = block()
            handler.post {
                busy.visibility = ProgressBar.GONE
                if (result.isFailure) {
                    Toast.makeText(
                        this,
                        result.exceptionOrNull()?.message ?: getString(R.string.a2a_error_action_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    if (A2aPrefs.keepAlive(this)) A2aKeepAliveService.start(this)
                    Toast.makeText(this, getString(R.string.a2a_toast_ready), Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
        }
    }

    private fun refresh() {
        thread(name = "a2a-refresh") {
            val running = A2aGateway.isRunning()
            val summary = StringBuilder()
            summary.append(
                if (running) getString(R.string.a2a_status_running)
                else getString(R.string.a2a_status_stopped),
            )
            if (running) {
                runCatching {
                    val connection =
                        URL("http://127.0.0.1:18901/health").openConnection() as HttpURLConnection
                    connection.connectTimeout = 1200
                    connection.readTimeout = 1200
                    val body = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()
                    val json = JSONObject(body)
                    val models = json.optInt("models", 0)
                    val login = json.optJSONObject("login")
                    val loggedIn = login?.optBoolean("loggedIn", false) ?: false
                    summary.append(getString(R.string.a2a_summary_models)).append(models)
                    summary.append(getString(R.string.a2a_summary_login))
                        .append(
                            if (loggedIn) getString(R.string.a2a_login_signed_in)
                            else getString(R.string.a2a_login_signed_out),
                        )
                }.onFailure {
                    summary.append(getString(R.string.a2a_summary_health_failed))
                        .append(it.message ?: getString(R.string.a2a_error_unknown))
                }
            } else {
                summary.append(getString(R.string.a2a_summary_hint))
            }
            val text = summary.toString()
            handler.post { statusView.text = text }
        }
    }

    private fun clearCookies() {
        CookieManager.getInstance().removeAllCookies {
            handler.post { Toast.makeText(this, getString(R.string.a2a_toast_cookies_cleared), Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showLog() {
        val file: File = A2aGateway.logFile(this)
        val text = runCatching {
            if (!file.isFile) getString(R.string.a2a_log_empty) else
                file.readLines().takeLast(300).joinToString("\n")
        }.getOrElse { getString(R.string.a2a_log_read_failed, it.message.orEmpty()) }
        val view = TextView(this).apply {
            this.text = text.takeLast(30_000)
            textSize = 11f
            setPadding(32, 16, 32, 16)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.a2a_log_title))
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton(getString(R.string.a2a_action_close), null)
            .show()
    }
}
