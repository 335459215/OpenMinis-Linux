package com.openminis.app.backup

import android.content.Context
import java.io.File

/**
 * [T-android-arch-split] Category exporters moved out of BackupExporter so the
 * class stays under its architecture ceiling. Pure file walks: they take the
 * app context explicitly and never touch exporter state.
 */

internal fun exportAgent2ApiCategory(context: Context, trees: BackupFileTreeExporter): BackupManifest.CategoryStat {
    var files = 0
    var bytes = 0L
    val roots = listOf(
        File(context.filesDir, "ubuntu-rootfs/opt/agent2api") to "agent2api/opt",
        File(context.filesDir, "ubuntu-rootfs/var/lib/agent2api") to "agent2api/var/lib/agent2api",
        File(context.filesDir, "ubuntu-rootfs/usr/local/bin") to "agent2api/usr/local/bin",
    )
    for ((root, prefix) in roots) {
        if (!root.exists()) continue
        val r = trees.export(root, prefix, BackupCategory.AGENT2API)
        files += r.filesIncluded
        bytes += r.bytesIncluded
    }
    return BackupManifest.CategoryStat(files, bytes, encrypted = false)
    }

internal fun exportSharedFilesCategory(context: Context, trees: BackupFileTreeExporter): BackupManifest.CategoryStat {
    val r = trees.export(
        File(context.filesDir, "minis-global/shared"), "shared", BackupCategory.SHARED_FILES
    )
    return BackupManifest.CategoryStat(r.filesIncluded, r.bytesIncluded, encrypted = false)
    }

internal fun exportSkillsCategory(context: Context, trees: BackupFileTreeExporter): BackupManifest.CategoryStat {
    val root = File(context.filesDir, "minis-global/skills")
    val r = trees.export(root, "skills", BackupCategory.SKILLS)
    return BackupManifest.CategoryStat(
        entries = BackupExporter.skillCount(root),
        bytes = r.bytesIncluded,
        encrypted = false,
        files = r.filesIncluded,
    )
    }
