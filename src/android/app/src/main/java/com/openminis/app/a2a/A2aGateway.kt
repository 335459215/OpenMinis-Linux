package com.openminis.app.a2a

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Local Agent2API gateway lifecycle for the in-app A2A panel.
 *
 * The gateway binary is shipped as `liba2a_server.so` (bionic PIE) and is
 * started as a child of the app, matching the official integration: data and
 * UI live under files/builtin-plugins/a2a, loopback only, no panel captcha,
 * no self-update.
 */
object A2aGateway {
    const val PORT = 18901
    private const val HOST = "127.0.0.1"

    @Volatile
    private var process: Process? = null
    private val lock = Any()

    fun isRunning(): Boolean {
        return try {
            val connection = URL("http://$HOST:$PORT/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 800
            connection.readTimeout = 800
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        } catch (_: Throwable) {
            false
        }
    }

    fun ensureStarted(context: Context): Result<Unit> = synchronized(lock) {
        if (isRunning()) return Result.success(Unit)

        val dataDir = File(context.filesDir, "builtin-plugins/a2a/data")
        val uiDir = File(context.filesDir, "builtin-plugins/a2a/ui")
        dataDir.mkdirs()
        File(dataDir, "tmp").mkdirs()

        if (!File(uiDir, "index.html").isFile) {
            runCatching { deployUi(context, uiDir) }
                .onFailure { return Result.failure(it) }
        }
        runCatching { installBundledPlugins(context, dataDir) }

        val binary = File(context.applicationInfo.nativeLibraryDir, "liba2a_server.so")
        if (!binary.isFile) {
            return Result.failure(IllegalStateException("liba2a_server.so missing"))
        }

        val builder = ProcessBuilder(binary.absolutePath)
        builder.redirectErrorStream(true)
        builder.redirectOutput(File(dataDir, "gateway.log"))
        builder.environment().apply {
            put("AGENT2API_HOST", HOST)
            put("AGENT2API_PROXY_PORT", PORT.toString())
            put("AGENT2API_PROXY_HOME", dataDir.absolutePath)
            put("AGENT2API_UI_DIR", uiDir.absolutePath)
            put("AGENT2API_ALLOW_NO_KEY", "1")
            put("AGENT2API_SELF_UPDATE", "0")
            put("AGENT2API_CAPTCHA_ENABLED", "0")
            put("HOME", dataDir.absolutePath)
            put("TMPDIR", File(dataDir, "tmp").absolutePath)
        }

        val started = try {
            builder.start()
        } catch (error: Throwable) {
            return Result.failure(error)
        }
        process = started

        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) return Result.success(Unit)
            if (!started.isAlive) break
            Thread.sleep(250)
        }
        started.destroy()
        process = null
        Result.failure(IllegalStateException("health timeout; see ${logFile(context)}"))
    }

    fun restart(context: Context): Result<Unit> {
        stop()
        Thread.sleep(400)
        return ensureStarted(context)
    }

    fun stop() {
        synchronized(lock) {
            process?.destroy()
            process = null
        }
    }

    fun logFile(context: Context): File =
        File(context.filesDir, "builtin-plugins/a2a/data/gateway.log")

    private fun deployUi(context: Context, uiDir: File) {
        val parent = uiDir.parentFile ?: context.filesDir
        val staging = File(parent, ".staging")
        staging.deleteRecursively()
        staging.mkdirs()
        context.assets.open("a2a/a2a-ui.zip").use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(staging, entry.name)
                    if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        zip.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> zip.copyTo(output) }
                    }
                    zip.closeEntry()
                }
            }
        }
        uiDir.deleteRecursively()
        if (!staging.renameTo(uiDir)) {
            throw IllegalStateException("cannot deploy a2a UI")
        }
    }

    /**
     * 新版 Agent2API 把 provider 拆成插件。APK 里带着官方 pack 的清单/UI/图标和
     * bionic 入口二进制；这里只把它们铺到配置目录，注册表交给网关自己扫描，
     * 避免手写 plugins.json 与官方验签逻辑打架。
     */
    private fun installBundledPlugins(context: Context, dataDir: File) {
        installPack(context, dataDir, "agent2api-providers", "3.4.1", "liba2a_providers.so")
        installPack(context, dataDir, "agent2api-web-providers", "3.4.2", "liba2a_web_providers.so")
    }

    private fun installPack(
        context: Context,
        dataDir: File,
        id: String,
        version: String,
        nativeName: String,
    ) {
        val target = File(dataDir, "plugins/$id/$version")
        if (!File(target, "pack.json").isFile) {
            copyAssetTree(context, "a2a/packs/$id", target)
        }
        val binary = File(File(target, "bin"), id)
        if (!binary.isFile) {
            val source = File(context.applicationInfo.nativeLibraryDir, nativeName)
            if (source.isFile) {
                binary.parentFile?.mkdirs()
                source.copyTo(binary, overwrite = true)
                binary.setExecutable(true, false)
            }
        }
    }

    private fun copyAssetTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath)
        if (children == null || children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { child ->
            copyAssetTree(context, "$assetPath/$child", File(target, child))
        }
    }
}
