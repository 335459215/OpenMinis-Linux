package com.openminis.app.a2a

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Control surface for the embedded Agent2API gateway: status, start/stop,
 * restart, panel entry, cookie reset and gateway log.
 */
class A2aSettingsActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var busy: ProgressBar
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
            text = "Agent2API Gateway"
            textSize = 22f
            setTextColor(Color.parseColor("#222222"))
            gravity = Gravity.START
        })
        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 24, 0, 0)
            setTextColor(Color.DKGRAY)
        }
        root.addView(statusView)
        detailView = TextView(this).apply {
            textSize = 13f
            setPadding(0, 8, 0, 0)
            setTextColor(Color.GRAY)
        }
        root.addView(detailView)
        busy = ProgressBar(this).apply { visibility = ProgressBar.GONE }
        root.addView(busy)

        root.addView(button("Start gateway") { action { A2aGateway.ensureStarted(applicationContext) } })
        root.addView(button("Stop gateway") {
            A2aGateway.stop()
            refresh()
        })
        root.addView(button("Restart gateway") { action { A2aGateway.restart(applicationContext) } })
        root.addView(button("Open panel") {
            startActivity(android.content.Intent(this, A2aPanelActivity::class.java))
        })
        root.addView(button("Clear panel cookies") { clearCookies() })
        root.addView(button("View gateway log") { showLog() })

        root.addView(TextView(this).apply {
            text = "OpenAI-compatible API:\nhttp://127.0.0.1:18901/v1"
            textSize = 13f
            setPadding(0, 24, 0, 0)
            setTextColor(Color.GRAY)
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
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 12 }
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
                        result.exceptionOrNull()?.message ?: "Agent2API action failed",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                refresh()
            }
        }
    }

    private fun refresh() {
        thread(name = "a2a-refresh") {
            val running = A2aGateway.isRunning()
            val summary = StringBuilder()
            summary.append("Status: ").append(if (running) "Running" else "Stopped")
            summary.append("\nEndpoint: http://127.0.0.1:18901/v1")
            if (running) {
                runCatching {
                    val connection = URL("http://127.0.0.1:18901/health").openConnection() as HttpURLConnection
                    connection.connectTimeout = 1000
                    connection.readTimeout = 1000
                    val body = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()
                    val json = JSONObject(body)
                    val models = json.optInt("models", 0)
                    val login = json.optJSONObject("login")
                    val loggedIn = login?.optBoolean("loggedIn", false) ?: false
                    summary.append("\nModels: ").append(models)
                    summary.append("\nUpstream login: ").append(if (loggedIn) "yes" else "no")
                }
            }
            val text = summary.toString()
            handler.post { statusView.text = text }
        }
    }

    private fun clearCookies() {
        CookieManager.getInstance().removeAllCookies {
            handler.post { Toast.makeText(this, "Panel cookies cleared", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showLog() {
        val file: File = A2aGateway.logFile(this)
        val text = runCatching {
            if (!file.isFile) "No gateway log yet." else file.readLines().takeLast(300).joinToString("\n")
        }.getOrElse { "Cannot read log: ${it.message}" }
        val view = TextView(this).apply {
            this.text = text.takeLast(30_000)
            textSize = 11f
            setPadding(32, 16, 32, 16)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Gateway log")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Close", null)
            .show()
    }
}
