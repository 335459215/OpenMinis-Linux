package com.openminis.app.a2a

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
            text = "← 返回"
            textSize = 15f
            setTextColor(Color.parseColor("#0A84FF"))
            setPadding(0, 0, 0, 24)
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "Agent2API 网关"
            textSize = 22f
            setTextColor(Color.parseColor("#222222"))
        })
        root.addView(TextView(this).apply {
            text = "本地 OpenAI 兼容网关，供 Minis Ultra 与其它客户端共用登录态"
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

        root.addView(button("打开面板") {
            startActivity(Intent(this, A2aPanelActivity::class.java))
        })
        root.addView(button("启动网关") { action { A2aGateway.ensureStarted(applicationContext) } })
        root.addView(button("停止网关") {
            A2aGateway.stop()
            A2aKeepAliveService.stop(this)
            Toast.makeText(this, "网关已停止", Toast.LENGTH_SHORT).show()
            refresh()
        })
        root.addView(button("重启网关") { action { A2aGateway.restart(applicationContext) } })

        // ── 保活 / 悬浮球 ──────────────────────────────────
        root.addView(TextView(this).apply {
            text = "后台运行"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 32, 0, 4)
        })

        keepAliveSwitch = Switch(this).apply {
            text = "跟随 Minis Ultra 保活"
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
            text = "用前台服务托管网关：Minis 不被杀，网关与插件进程就不会被系统回收。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 16)
        })

        bubbleSwitch = Switch(this).apply {
            text = "离开页面后显示悬浮小图标"
            textSize = 15f
            isChecked = A2aPrefs.floatingBubble(this@A2aSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                A2aPrefs.setFloatingBubble(this@A2aSettingsActivity, checked)
                if (checked) {
                    if (canDrawOverlay()) {
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
            text = "面板离开即冻结（暂停 JS 与渲染），点小图标瞬间解冻回到原页面。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 16)
        })

        root.addView(button("授权「显示在其他应用上层」") { requestOverlay() })
        root.addView(button("清理面板 Cookie") { clearCookies() })
        root.addView(button("查看网关日志") { showLog() })

        root.addView(TextView(this).apply {
            text = "接口地址：http://127.0.0.1:18901/v1\n" +
                "面板地址：http://127.0.0.1:18901/m"
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
            Toast.makeText(this, "已获得悬浮窗权限", Toast.LENGTH_SHORT).show()
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
                        result.exceptionOrNull()?.message ?: "操作失败",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    if (A2aPrefs.keepAlive(this)) A2aKeepAliveService.start(this)
                    Toast.makeText(this, "网关已就绪", Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
        }
    }

    private fun refresh() {
        thread(name = "a2a-refresh") {
            val running = A2aGateway.isRunning()
            val summary = StringBuilder()
            summary.append(if (running) "状态：运行中" else "状态：已停止")
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
                    summary.append("\n模型数量：").append(models)
                    summary.append("\n上游登录：").append(if (loggedIn) "已登录" else "未登录")
                }.onFailure {
                    summary.append("\n健康检查失败：").append(it.message ?: "未知")
                }
            } else {
                summary.append("\n点击「启动网关」后即可打开面板")
            }
            val text = summary.toString()
            handler.post { statusView.text = text }
        }
    }

    private fun clearCookies() {
        CookieManager.getInstance().removeAllCookies {
            handler.post { Toast.makeText(this, "面板 Cookie 已清理", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showLog() {
        val file: File = A2aGateway.logFile(this)
        val text = runCatching {
            if (!file.isFile) "还没有网关日志。" else
                file.readLines().takeLast(300).joinToString("\n")
        }.getOrElse { "读取日志失败：${it.message}" }
        val view = TextView(this).apply {
            this.text = text.takeLast(30_000)
            textSize = 11f
            setPadding(32, 16, 32, 16)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle("网关日志")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("关闭", null)
            .show()
    }
}
