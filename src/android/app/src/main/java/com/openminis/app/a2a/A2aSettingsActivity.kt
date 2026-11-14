package com.openminis.app.a2a

import com.openminis.app.R
import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.GridLayout
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
        val scroll = ScrollView(this).apply { setBackgroundColor(bgColor()) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(28))
            setBackgroundColor(bgColor())
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "\u2190  " + getString(R.string.a2a_action_back)
            textSize = 13f
            setTextColor(accentColor())
            setPadding(0, 0, 0, dp(10))
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.a2a_gateway_title)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textColor())
            setPadding(0, 0, 0, dp(12))
        })

        // 状态卡：三行紧凑信息，号码靠右对齐由 refresh() 填入 statusView。
        val statusCard = card()
        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(textColor())
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        statusCard.addView(statusView)
        busy = ProgressBar(this).apply { visibility = ProgressBar.GONE }
        statusCard.addView(busy)
        root.addView(statusCard, sectionParams())

        // 操作区：2x2 网格，不再四个全宽大按钮。
        val grid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
        }
        fun gridChip(label: String, onClick: () -> Unit) {
            grid.addView(
                actionChip(label, onClick),
                GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(0, dp(4), dp(8), dp(4))
                },
            )
        }
        gridChip(getString(R.string.a2a_action_open_panel)) {
            startActivity(Intent(this, A2aPanelActivity::class.java))
        }
        gridChip(getString(R.string.a2a_action_start)) {
            action { A2aGateway.ensureStarted(applicationContext) }
        }
        gridChip(getString(R.string.a2a_action_stop)) {
            A2aGateway.stop()
            A2aKeepAliveService.stop(this)
            Toast.makeText(this, getString(R.string.a2a_toast_stopped), Toast.LENGTH_SHORT).show()
            refresh()
        }
        gridChip(getString(R.string.a2a_action_restart)) {
            action { A2aGateway.restart(applicationContext) }
        }
        root.addView(grid, sectionParams())

        // 后台运行：两张开关行收进同一张卡。
        root.addView(sectionLabel(getString(R.string.a2a_section_background)))
        val runCard = card()
        keepAliveSwitch = Switch(this).apply {
            text = getString(R.string.a2a_keepalive_title)
            textSize = 15f
            isChecked = A2aPrefs.keepAlive(this@A2aSettingsActivity)
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked ->
                A2aPrefs.setKeepAlive(this@A2aSettingsActivity, checked)
                if (checked) {
                    A2aKeepAliveService.start(this@A2aSettingsActivity)
                } else {
                    A2aKeepAliveService.stop(this@A2aSettingsActivity)
                }
            }
        }
        runCard.addView(keepAliveSwitch)
        bubbleSwitch = Switch(this).apply {
            text = getString(R.string.a2a_overlay_title)
            textSize = 15f
            isChecked = A2aPrefs.floatingBubble(this@A2aSettingsActivity)
            setPadding(0, dp(6), 0, dp(6))
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
        runCard.addView(bubbleSwitch)
        root.addView(runCard, sectionParams())

        // 授权悬浮窗：整行可点，不再占一个大按钮。
        val overlayCard = card()
        overlayCard.addView(
            actionChip(getString(R.string.a2a_action_grant_overlay)) { requestOverlay() },
        )
        root.addView(overlayCard, sectionParams())

        // 弱化操作：并排小字，避免和主线操作抢注意力。
        val weakRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        weakRow.addView(
            textButton(getString(R.string.a2a_action_view_log)) { showLog() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(weakRow, sectionParams())


        root.addView(TextView(this).apply {
            text = getString(R.string.a2a_endpoints)
            textSize = 11f
            setTextColor(mutedColor())
            setPadding(dp(4), dp(18), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
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
        textSize = 13f
        setTextColor(Color.WHITE)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(accentColor())
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
    }

    // [T-a2a-chrome] 与 Compose 页面同一组语义色：夜/昼双色板，圆角胶囊按钮。
    private fun isNightChrome(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun bgColor(): Int = if (isNightChrome()) Color.parseColor("#141416") else Color.parseColor("#FAFAFB")
    private fun textColor(): Int = if (isNightChrome()) Color.parseColor("#ECECEF") else Color.parseColor("#1A1A1E")
    private fun mutedColor(): Int = if (isNightChrome()) Color.parseColor("#9A9AA3") else Color.parseColor("#6B6B73")
    private fun accentColor(): Int = Color.parseColor("#5B8CFF")


    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (isNightChrome()) Color.parseColor("#1E1E22") else Color.WHITE)
            setStroke(
                dp(1),
                if (isNightChrome()) Color.parseColor("#2C2C32") else Color.parseColor("#ECECF0"),
            )
        }
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(mutedColor())
        setPadding(dp(4), dp(18), 0, dp(6))
    }

    private fun sectionParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }

    private fun actionChip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(accentColor())
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (isNightChrome()) Color.parseColor("#1E2A44") else Color.parseColor("#EDF2FF"))
        }
        setPadding(dp(12), dp(11), dp(12), dp(11))
        setOnClickListener { onClick() }
    }

    private fun textButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(mutedColor())
        setPadding(0, dp(14), 0, 0)
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
