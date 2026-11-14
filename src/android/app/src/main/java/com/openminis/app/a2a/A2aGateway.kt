package com.openminis.app.a2a

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.openminis.app.MinisApp
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
    private const val UI_VERSION = "3.4.2-sw-mobile-editVJ-loginbridge-sms"

    /** UI 版本戳的短哈希，作为 URL 参数破缓存（Service Worker 也会按 URL 区分）。 */
    fun uiRevision(): String = UI_VERSION.hashCode().toUInt().toString(16)

    /** 本次 ensureStarted 是否重铺了 UI（面板据此清 HTTP 缓存）。 */
    @Volatile
    var uiDeployed: Boolean = false
        private set

    @Volatile
    private var process: Process? = null

    @Volatile
    var isStarting: Boolean = false
        private set

    /** 正在启动中（进程已起但 health 未就绪），供面板判断是否应踢保活服务。 */
    fun isStartingNow(): Boolean = isStarting || process != null

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

    /**
     * 最近一次真实探活的结果。**只由后台线程写，主线程只读**。
     *
     * 存在的理由：`isRunning()` 会发起同步 HTTP，而 Android 对 targetSdk ≥ 11
     * 的应用在主线程联网一律抛 `NetworkOnMainThreadException`。悬浮球门禁、
     * 面板 onResume 的保活判断、服务的 onStartCommand 全在主线程，一旦直接
     * 调 `isRunning()`，异常被 `catch (_: Throwable)` 吞成 false —— 网关明明
     * 活着也判成没活：悬浮球永远不出现、前台服务永远拉不起来，而且因为
     * 异常发生在写日志之前，连一行诊断都不会留下。
     */
    @Volatile
    private var probeOk: Boolean = false

    /** 同步探活。会联网，**只能在后台线程调用**。 */
    fun isRunning(): Boolean {
        val ok = try {
            val connection = URL("http://$HOST:$PORT/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 800
            connection.readTimeout = 800
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        } catch (_: Throwable) {
            false
        }
        probeOk = ok
        return ok
    }

    /**
     * 主线程安全的存活判断：只看进程句柄与探活缓存，**绝不联网**。
     *
     * 句柄存在时它是权威的 —— 进程死了就是死了，watchdog 会重拉并换新句柄。
     * 句柄为空说明网关可能是上个 App 进程遗留的，这时只能信后台线程最近
     * 一次探活的结果。
     */
    fun isRunningFast(): Boolean {
        val handle = process
        if (handle != null) return handle.isAlive
        return probeOk
    }

    /** 是否正在后台探活（防止主线程连发时开一堆线程）。 */
    @Volatile
    private var probeInFlight: Boolean = false

    /**
     * 踢一次后台探活，刷新 [probeOk]。
     *
     * 句柄为空时（网关是上个 App 进程遗留的）主线程无从得知真实状态，只能
     * 先信缓存。调一次这个，下一次生命周期回调就能拿到准确值。
     */
    fun refreshProbeAsync() {
        if (process != null) return
        if (probeInFlight) return
        probeInFlight = true
        Thread({
            try {
                isRunning()
            } finally {
                probeInFlight = false
            }
        }, "a2a-probe").start()
    }

    fun ensureStarted(context: Context): Result<Unit> = synchronized(lock) {
        if (stopRequested) {
            return Result.failure(IllegalStateException("stopRequested"))
        }
        if (isRunning()) return Result.success(Unit)
        isStarting = true
        try {
            if (stopRequested) {
                return Result.failure(IllegalStateException("stopRequested"))
            }
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
            // 内嵌手机端不提供面板账号体系：启动前删掉管理员记录，让网关按
            // “尚未初始化”放行 /m。只保留对外请求用的 API Key。
            runCatching { clearPanelAdmin(dataDir) }

            // [T-a2a-dns-probe] 双栈探针：只在解析不到 IPv4 时留痕，不阻塞启动。
            // Android 的 DNS 走 netd（bionic），KSU 那套改 resolv.conf 的路径在这里
            // 不存在；能做的等价兜底就是把「只有 IPv6」这件事记进网关日志。
            runCatching {
                Thread {
                    runCatching {
                        val addrs = java.net.InetAddress.getAllByName("api.github.com")
                        val hasV4 = addrs.any { it is java.net.Inet4Address }
                        if (!hasV4) {
                            File(dataDir, "gateway.log").appendText(
                                "[dns] ipv4 probe failed: " +
                                    addrs.joinToString { it.hostAddress ?: "?" } + "\n",
                            )
                        }
                    }
                }.apply { isDaemon = true }.start()
            }

            val binary = File(context.applicationInfo.nativeLibraryDir, "liba2a_server.so")
            if (!binary.isFile) {
                return Result.failure(IllegalStateException("liba2a_server.so missing"))
            }

            val builder = ProcessBuilder(binary.absolutePath)
            builder.redirectErrorStream(true)
            builder.redirectOutput(File(dataDir, "gateway.log"))
            val panelPassword = A2aPrefs.panelAdminPassword(context)
            builder.environment().apply {
                put("AGENT2API_HOST", HOST)
                put("AGENT2API_PROXY_PORT", PORT.toString())
                put("AGENT2API_PROXY_HOME", dataDir.absolutePath)
                put("AGENT2API_UI_DIR", uiDir.absolutePath)
                put("AGENT2API_ALLOW_NO_KEY", "1")
                // 手机壳直接打开 /m：启动时预置一个只属于本机的管理员，随后
                // A2aPanelAuth 会静默换取会话 Cookie。不要再把 panelAdmin 删掉，
                // 否则网页端会进入“首次注册管理员”的登录页。
                put("AGENT2API_ADMIN_USER", A2aPrefs.PANEL_ADMIN_USER)
                put("AGENT2API_ADMIN_PASSWORD", panelPassword)
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
                if (stopRequested) {
                    started.destroy()
                    process = null
                    return Result.failure(IllegalStateException("stopRequested"))
                }
                if (isRunning()) {
                    // 网关就绪 → 把它接成 Minis 的一个 AI 服务商，用户在
                    // 「AI 服务商」里直接看到 Agent2API 及其模型，不必手动填地址。
                    // 失败不能影响网关本身，所以整段吞异常（内部已记日志）。
                    if (A2aPrefs.providerSyncEnabled(context)) {
                        runCatching {
                            val app: MinisApp? = context.applicationContext as? MinisApp
                            val repository = app?.providerRepositoryOrNull
                            if (repository != null) {
                                A2aProviderBridge.sync(repository, context)
                            }
                        }
                    }
                    stopRequested = false
                    // [T-a2a-snapshot] 启动成功留一份数据快照，供设置页回滚。
                    runCatching { A2aSnapshot.snapshot(context) }
                    return Result.success(Unit)
                }
                if (!started.isAlive) break
                Thread.sleep(250)
            }
            started.destroy()
            process = null
            Result.failure(IllegalStateException("health timeout; see ${logFile(context)}"))
        } finally {
            isStarting = false
        }
    }

    /**
     * 删除 kv 里的 `panelAdmin` 记录。手机端由外壳直接打开面板，不做账号密码
     * 登录；管理员记录只存在于 app 私有 sqlite，必须在原生层、网关启动前删。
     * 只在网关停止时调用。
     */
    private fun clearPanelAdmin(dataDir: File) {
        val dbFile = File(dataDir, "agent2api.db")
        if (!dbFile.isFile) return
        runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                db.delete("kv", "key = ?", arrayOf("panelAdmin"))
            }
        }
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
        // stop() 置 stopRequested=true；重启意图明确，先清位否则 ensureStarted
        // 开头的 stopRequested 守卫会直接拒绝，导致重启自锁。
        stopRequested = false
        return ensureStarted(context)
    }

    fun stop() {
        stopRequested = true
        A2aBubble.hide()
        synchronized(lock) {
            process?.destroy()
            process = null
        }
    }

    /** 用户主动停止网关后，面板离场时不得再把悬浮花加回来。 */
    @Volatile
    var stopRequested: Boolean = false

    /**
     * 悬浮球是否允许显示：只有网关进程真实活着且用户没有点停止时才允许。
     *
     * 仅靠各处调用 `hide()` 不够：设置页的开关、面板的 onStop、保活服务的
     * 广播都会在各自时机调 A2aBubble.show()。只要有一条路在网关已停时
     * 绕过 hide()，悬浮花朵就会在点停止后重新冒出来。所以把这条判断
     * 收敛在这里——所有入口都查到同一个运行状态，而不是每个调用方
     * 自己拼一次弱守卫。
     */
    fun bubbleAllowed(): Boolean = !stopRequested && isRunningFast()

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
                    // 手机壳自己完成本机认证，不部署网页端管理员登录页。
                    // 服务端若仍把 /login 重定向过来，这里也不会留下可进入的页面。
                    val baseName = entry.name.substringAfterLast('/')
                    if (baseName == "login.html" || baseName == "sms-login.html" || baseName == "web-login.html") {
                        zip.closeEntry()
                        continue
                    }
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
        // 旧缓存目录可能已在 uiDir 里残留登录页（升级 UI_VERSION 强制重铺会整目录
        // 删掉，但防御性再删一次，覆盖 staging 修复/异常恢复路径）。
        runCatching {
            File(uiDir, "login.html").delete()
            File(uiDir, "sms-login.html").delete()
            File(uiDir, "web-login.html").delete()
        }
        runCatching {
            File(parent, "ui.version").writeText(UI_VERSION)
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
