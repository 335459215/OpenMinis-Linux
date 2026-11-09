package com.openminis.app.a2a

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 手机壳的内嵌面板会话。
 *
 * 网页端的 `/login.html` 是给浏览器用户注册/登录管理员用的；手机壳不需要
 * 让用户再看见一层登录页。网关由 [A2aGateway] 用 App 私有的随机管理员
 * 凭据预置，启动成功后这里直接调用本机 `/api/panel/login`，把 Set-Cookie
 * 放进 WebView 的 CookieManager。这样 `/m` 打开时已经是已登录状态。
 *
 * 所有请求只发往 127.0.0.1，且只在网关启动成功后调用；不会把密码写日志。
 */
object A2aPanelAuth {
    private val lock = Any()

    /** 登录并写入 WebView Cookie；成功返回 true。 */
    fun ensureSession(context: Context): Boolean = synchronized(lock) {
        val base = "http://127.0.0.1:${A2aGateway.PORT}/"
        val body = JSONObject()
            .put("username", A2aPrefs.PANEL_ADMIN_USER)
            .put("password", A2aPrefs.panelAdminPassword(context))
            .toString()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("${base}api/panel/login").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3_000
                readTimeout = 5_000
                doInput = true
                doOutput = true
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                A2aLog.write(context, "panel auto-login HTTP $code")
                return false
            }

            val cookies = connection.headerFields
                .filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
                .values
                .flatten()
            if (cookies.isEmpty()) {
                A2aLog.write(context, "panel auto-login no Set-Cookie")
                return false
            }
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            var wrote = false
            cookies.forEach { raw ->
                val value = raw.substringBefore(';').trim()
                if (value.contains('=') && !value.startsWith("=")) {
                    manager.setCookie(base, value)
                    wrote = true
                }
            }
            manager.flush()
            if (!wrote) A2aLog.write(context, "panel auto-login cookie parse empty")
            wrote
        } catch (error: Throwable) {
            A2aLog.write(context, "panel auto-login failed: ${error.javaClass.simpleName}: ${error.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }
}
