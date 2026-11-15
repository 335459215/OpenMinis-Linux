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
    // [T-backup-a2a-real-paths] The gateway's LIVE data lives under
    // `files/a2a-gateway/data` (A2aGateway.dataDir): that is where agent2api.db
    // sits — accounts, panel admin, plugin state. The old root list pointed at
    // `ubuntu-rootfs/opt/agent2api` / `var/lib/agent2api`, which this build no
    // longer uses, so a "successful" backup carried NO account data at all.
    // That is exactly the gap the user hit (U18: "备份到底有没有包含我的
    // Agent2 API").
    //
    // Kept the legacy roots in the list as a migration courtesy: an old device
    // that still has them gets them backed up too, and the importer writes
    // everything back under the same relative layout.
    val roots = listOf(
        // Live data first — this is the one that matters.
        com.openminis.app.a2a.A2aGateway.dataDir(context) to "agent2api/gateway/data",
        // Legacy / secondary locations, kept for older installs.
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
