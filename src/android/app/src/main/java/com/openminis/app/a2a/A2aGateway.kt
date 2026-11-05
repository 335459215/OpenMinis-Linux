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
 * 数据目录**不能**放在 `files/builtin-plugins/a2a` 下面：Minis 每次启动都会执行
 * `ProviderConfigStore.purgeRetiredBuiltInLaneFiles()`，把那个目录整个
 * `deleteRecursively()`（连同 agent2api.db、account.key、插件包）。
 * 所以这里换到 `files/a2a-gateway/`，那是我们自己独占、不会被上游清理的路径。
 */
object A2aGateway {
    const val PORT = 18901
    private const val HOST = "127.0.0.1"
    private const val ROOT = "a2a-gateway"

    /**
     * 面板 UI 版本戳。改这个字符串（或换 a2a-ui.zip）就会在下次启动时重铺 UI。
     * 原实现只在 `index.html` 不存在时铺一次，导致 APK 里的 UI 更新永远
     * 到不了设备 —— 用户看到的还是旧页面。
     */
    private const val UI_VERSION = "3.4.2-sw-mobile-parity"

    /** UI 版本戳的短哈希，作为 URL 参数破缓存（Service Worker 也会按 URL 区分）。 */
    fun uiRevision(): String = UI_VERSION.hashCode().toUInt().toString(16)

    /** 本次 ensureStarted 是否重铺了 UI（面板据此清 HTTP 缓存）。 */
    @Volatile
    var uiDeployed: Boolean = false
        private set

    @Volatile
    private var process: Process? = null
    private val lock = Any()

    /** 网关根目录；服务与日志也用它。 */
    fun rootDir(context: Context): File = File(context.filesDir, ROOT)
    fun dataDir(context: Context): File = File(rootDir(context), "data")
    fun uiDir(context: Context): File = File(rootDir(context), "ui")

    /**
     * 旧路径（`builtin-plugins/a2a`）如果还在，把数据搬过来。
     * 只在目标不存在时搬，避免覆盖用户当前数据。
     */
    private fun migrateLegacyData(context: Context) {
        val legacy = File(context.filesDir, "builtin-plugins/a2a/data")
        val target = dataDir(context)
        if (!legacy.isDirectory || target.isDirectory) return
        runCatching {
            target.parentFile?.mkdirs()
            legacy.copyRecursively(target, overwrite = false)
        }
    }

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

        migrateLegacyData(context)
        val dataDir = dataDir(context)
        val uiDir = uiDir(context)
        dataDir.mkdirs()
        File(dataDir, "tmp").mkdirs()

        val stamp = File(rootDir(context), "ui.version")
        val needUi = !File(uiDir, "index.html").isFile ||
            !stamp.isFile ||
            stamp.readText().trim() != UI_VERSION
        if (needUi) {
            runCatching { deployUi(context, uiDir) }
                .onFailure { return Result.failure(it) }
            uiDeployed = true
            runCatching { stamp.writeText(UI_VERSION) }
        }
        runCatching { installBundledPlugins(context, dataDir) }
        // 清掉历史插件安装失败记录。网关把 operations 持久化在 kv 里
        // （键 `pluginOperation:<id>`），启动时读回内存 —— 之前尝试「在面板里
        // 升级插件」必然失败（它把二进制解到 app 私有目录再 exec，被 noexec 拒），
        // 那条红字会一直挂在插件页上，让人以为插件坏了。此刻网关还没起，独占
        // 写库是安全的。
        runCatching { clearStalePluginOperations(dataDir) }

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
            if (isRunning()) {
                // 网关就绪 → 把它接成 Minis 的一个 AI 服务商，用户在
                // 「AI 服务商」里直接看到 Agent2API 及其模型，不必手动填地址。
                // 失败不能影响网关本身，所以整段吞异常（内部已记日志）。
                if (A2aPrefs.providerSyncEnabled(context)) {
                    runCatching {
                        val app = context.applicationContext as? MinisApp
                        val repository = app?.providerRepositoryOrNull
                        if (repository != null) {
                            A2aProviderBridge.sync(repository, context)
                        }
                    }
                }
                return Result.success(Unit)
            }
            if (!started.isAlive) break
            Thread.sleep(250)
        }
        started.destroy()
        process = null
        Result.failure(IllegalStateException("health timeout; see ${logFile(context)}"))
    }

    /**
     * 删除 kv 里的 `pluginOperation:*` 记录（插件页那条「启动失败」的来源）。
     * 只在网关停止时调用。
     */
    private fun clearStalePluginOperations(dataDir: File) {
        val dbFile = File(dataDir, "agent2api.db")
        if (!dbFile.isFile) return
        runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                db.delete("kv", "key LIKE ?", arrayOf("pluginOperation:%"))
            }
        }
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

    fun logFile(context: Context): File = File(dataDir(context), "gateway.log")

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
        installPack(context, dataDir, "agent2api-providers", "3.4.2", "liba2a_providers.so")
        installPack(context, dataDir, "agent2api-web-providers", "3.4.3", "liba2a_web_providers.so")
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
        val packJson = File(dataDir, "plugins/$id/$version/pack.json")
        val manifest = runCatching { JSONObject(packJson.readText()) }.getOrElse { JSONObject() }
        // manifest_providers 是「这个包提供哪些 provider」的缓存，隐藏账号的
        // 判定（account_store::hidden_accounts）就靠它 —— 少了这个字段，账号
        // 会一直显示为 not_installed，即使插件已经装好。
        val manifestProviders = manifest.optJSONArray("providers") ?: JSONArray()

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
                    pack.put("manifest_providers", manifestProviders)
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
                    .put("installed_at", System.currentTimeMillis())
                    .put("manifest_providers", manifestProviders),
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
