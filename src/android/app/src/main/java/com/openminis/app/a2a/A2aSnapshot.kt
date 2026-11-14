package com.openminis.app.a2a

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-a2a-snapshot] 网关数据快照与回滚。
 *
 * App 内嵌模型的二进制在 APK 的 `nativeLibraryDir` 里，只读，无法像 KSU 模块
 * 那样用 `server.prev` 换二进制。可写且会坏的是网关数据目录：`plugins.json`、
 * 数据库、插件目录 —— 面板升级或插件装坏时，正是这几样让网关起不来。
 *
 * 所以这里回滚的是**数据**：每次网关成功启动后留一份快照，设置页可以一键退回。
 * 只保留最近 3 份；插件目录超过 80MB 时只备配置，不备大二进制。
 */
object A2aSnapshot {
    private const val TAG = "A2aSnapshot"
    private const val DIR = "snapshots"
    private const val KEEP = 3
    private const val MAX_BYTES = 80L * 1024 * 1024

    private fun root(context: Context): File = File(A2aGateway.rootDir(context), DIR)

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun sizeOf(file: File): Long {
        if (file.isFile) return file.length()
        var total = 0L
        file.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    private fun copyInto(src: File, dst: File) {
        if (!src.exists()) return
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child -> copyInto(child, File(dst, child.name)) }
        } else {
            dst.parentFile?.mkdirs()
            runCatching { src.copyTo(dst, overwrite = true) }
        }
    }

    /** 备份一次。返回快照目录；被跳过或失败时返回 null。 */
    fun snapshot(context: Context): File? = runCatching {
        val dataDir = A2aGateway.rootDir(context)
        if (!dataDir.isDirectory) return@runCatching null
        val target = File(root(context), stamp())
        target.mkdirs()

        // 配置与小库必备。
        listOf(
            "plugins.json",
            "agent2api.db",
            "agent2api.db-wal",
            "agent2api.db-shm",
            "config.json",
        ).forEach { name ->
            val f = File(dataDir, name)
            if (f.isFile) copyInto(f, File(target, name))
        }

        // 插件目录：小就整份备，大就只备注册表（上面已经备了 plugins.json）。
        val plugins = File(dataDir, "plugins")
        if (plugins.isDirectory && sizeOf(plugins) <= MAX_BYTES) {
            copyInto(plugins, File(target, "plugins"))
        }

        prune(context)
        target
    }.getOrElse {
        AppLogger.warning(TAG, "snapshot failed: ${it.javaClass.simpleName} ${it.message}")
        null
    }

    fun list(context: Context): List<File> =
        root(context).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
            ?: emptyList()

    fun latest(context: Context): File? = list(context).firstOrNull()

    /** 把最新快照里的配置与数据库覆盖回数据目录。 */
    fun rollback(context: Context): Result<Unit> = runCatching {
        val snap = latest(context) ?: error("没有可用快照")
        val dataDir = A2aGateway.rootDir(context)
        var restored = 0
        snap.listFiles()?.forEach { item ->
            if (item.name == "plugins") {
                copyInto(item, File(dataDir, "plugins"))
                restored++
            } else {
                copyInto(item, File(dataDir, item.name))
                restored++
            }
        }
        if (restored == 0) error("快照为空")
        AppLogger.info(TAG, "rollback restored $restored item(s) from ${snap.name}")
    }

    private fun prune(context: Context) {
        list(context).drop(KEEP).forEach { stale ->
            runCatching { stale.deleteRecursively() }
        }
    }
}
