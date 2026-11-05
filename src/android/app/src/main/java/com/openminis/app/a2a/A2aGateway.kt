package com.openminis.app.a2a

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
     * 新版 Agent2API 把 provider 拆成插件，启动时只认 plugins.json 注册表，
     * 不会扫描目录。APK 里带着官方 pack 的清单/UI/图标和 bionic 入口二进制，
     * 这里把它们铺到配置目录并写入注册表。
     *
     * 注意：官方 pack 里的 ed25519 签名是签「官方原版内容」的，我们换了 bionic
     * 入口二进制后内容已变，签名必然验不过。网关的验签是三态：
     * Unsigned（允许安装，标黄） / Invalid（拒绝）。所以这里直接去掉签名，
     * 让它以 Unsigned 身份存在，而不是伪造一份验不过的签名。
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
        val source = File(context.applicationInfo.nativeLibraryDir, nativeName)
        if (!source.isFile) return

        /*
         * Android 10+ 的 app 私有目录是 noexec 的：把 bionic 二进制 copy 到
         * files/plugins/.../bin/ 后再 execve 会被 SELinux 拒（Permission denied）。
         * 官方集成能跑起来，是因为它执行的是 nativeLibraryDir 里的 liba2a*.so
         * —— apk 的 lib 目录是 exec 允许的。这里照做：pack 里的 bin/<id> 指向
         * nativeLibraryDir 的符号链接，execve 解析到 apk lib 目录的真实文件。
         */
        if (binary.exists() || runCatching {
                java.nio.file.Files.isSymbolicLink(binary.toPath())
            }.getOrDefault(false)
        ) {
            binary.delete()
        }
        binary.parentFile?.mkdirs()
        val linked = runCatching {
            java.nio.file.Files.createSymbolicLink(
                binary.toPath(),
                source.toPath(),
            )
            true
        }.getOrElse { false }
        if (!linked) {
            // 退路：不支持 symlink 的文件系统才复制（某些设备上仍可能 noexec）。
            runCatching {
                source.copyTo(binary, overwrite = true)
                binary.setExecutable(true, false)
            }.onFailure { return }
        }
        registerPack(dataDir, id, version)
    }

    /** 把包写进 `plugins.json`，字段对齐网关的 InstalledPack。 */
    private fun registerPack(dataDir: File, id: String, version: String) {
        val registryFile = File(dataDir, "plugins/plugins.json")
        val root = if (registryFile.isFile) {
            runCatching { JSONObject(registryFile.readText()) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        val old = root.optJSONArray("packs") ?: JSONArray()
        val packs = JSONArray()
        var found = false
        for (i in 0 until old.length()) {
            val pack = old.optJSONObject(i) ?: continue
            val packId = pack.optString("id")
            if (packId == id) {
                if (pack.optString("version") == version) {
                    pack.put("enabled", true)
                    pack.put("entry", "bin/$id")
                    found = true
                } else {
                    continue // 丢掉旧版本条目
                }
            }
            packs.put(pack)
        }
        if (!found) {
            packs.put(
                JSONObject()
                    .put("id", id)
                    .put("version", version)
                    .put("enabled", true)
                    .put("providers", JSONObject())
                    .put("entry", "bin/$id")
                    .put("sha256", "")
                    .put("signed", false)
                    .put("installed_at", System.currentTimeMillis()),
            )
        }
        root.put("packs", packs)
        registryFile.parentFile?.mkdirs()
        registryFile.writeText(root.toString())
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
