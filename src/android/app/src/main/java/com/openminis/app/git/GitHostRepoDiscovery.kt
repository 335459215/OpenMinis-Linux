package com.openminis.app.git

import java.io.File

/**
 * [T-git-host-discovery] Host 侧仓库发现。
 *
 * 沙盒 find 与 host walk 的深度语义差 1（find 的深度从子项算，walk 从自身算），
 * 两边不一致就会漏掉 workspace 自身。这里统一成「含自身，向下 maxDepth 层」，
 * 与沙盒侧 mindepth1/maxdepth5 覆盖同一集合。
 */
object GitHostRepoDiscovery {
    private val SKIP = setOf("node_modules", ".gradle", "build", "target", "dist", ".git")

    /** 含 [root] 自身在内，向下 [maxDepth] 层内所有 git 工作树。 */
    fun discover(root: File, maxDepth: Int = 4): List<File> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth) return
            if (File(dir, ".git").exists()) out += dir
            if (depth == maxDepth) return
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name !in SKIP) walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return out.distinctBy { it.absolutePath }.sortedBy { it.absolutePath }
    }
}
