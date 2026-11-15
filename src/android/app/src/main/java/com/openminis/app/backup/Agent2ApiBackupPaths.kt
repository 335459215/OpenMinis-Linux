package com.openminis.app.backup

import android.content.Context
import java.io.File

/**
 * [T-backup-a2a-real-paths] Agent2API (embedded gateway) category: export roots
 * and the matching restore targets.
 *
 * Why this file exists
 * -------------------
 * The category used to point at `ubuntu-rootfs/opt/agent2api` and
 * `ubuntu-rootfs/var/lib/agent2api`. Those are NOT where this build keeps the
 * gateway: `A2aGateway.dataDir()` is `files/a2a-gateway/data`, and that is where
 * `agent2api.db` lives — accounts, panel admin, plugin state. So a "successful"
 * backup carried the binaries and the UI but **zero account data**, and a restore
 * brought the gateway up with no credentials at all. That is the gap the user hit
 * ("备份到底有没有包含我的 Agent2 API").
 *
 * The legacy roots are kept in both lists so an older device that still has them
 * is backed up too, and so a package made by an older build still restores.
 */
internal object Agent2ApiBackupPaths {

    /** Logical prefix the live gateway data is filed under inside the package. */
    const val LIVE_PREFIX = "agent2api/gateway/data"

    /**
     * `(absolute source root, logical prefix)` pairs, in restore-relevant order.
     * The FIRST entry is the one that carries credentials.
     */
    fun exportRoots(context: Context): List<Pair<File, String>> = listOf(
        com.openminis.app.a2a.A2aGateway.dataDir(context) to LIVE_PREFIX,
        File(context.filesDir, "ubuntu-rootfs/opt/agent2api") to "agent2api/opt",
        File(context.filesDir, "ubuntu-rootfs/var/lib/agent2api") to "agent2api/var/lib/agent2api",
        File(context.filesDir, "ubuntu-rootfs/usr/local/bin") to "agent2api/usr/local/bin",
    )

    /**
     * `(restore destination root, package prefix)` pairs. Must mirror
     * [exportRoots] one-for-one — a prefix with no destination is a silent drop
     * on restore, which is how the accounts went missing in the first place.
     */
    fun restoreTargets(context: Context): List<Pair<File, String>> = listOf(
        com.openminis.app.a2a.A2aGateway.dataDir(context) to "$LIVE_PREFIX/",
        File(context.filesDir, "ubuntu-rootfs") to "agent2api/opt/",
        File(context.filesDir, "ubuntu-rootfs/var/lib/agent2api") to "agent2api/var/lib/agent2api/",
        File(context.filesDir, "ubuntu-rootfs/usr/local/bin") to "agent2api/usr/local/bin/",
    )
}
