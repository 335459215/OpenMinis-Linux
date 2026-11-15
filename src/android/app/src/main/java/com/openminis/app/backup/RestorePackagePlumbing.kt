package com.openminis.app.backup

import java.io.File

/**
 * [T-arch-extract-restore-plumbing] Small pure helpers lifted out of
 * `BackupImporter` so that class stays under its architecture ceiling. No
 * behaviour change: each one was moved verbatim.
 */
internal object RestorePackagePlumbing {

    /** `secrets.json`, or null when the package has none / it will not decode. */
    fun readSecrets(root: File): BackupSecrets? {
        val f = File(root, "secrets.json")
        if (!f.isFile) return null
        return runCatching {
            BackupFormat.json.decodeFromString(BackupSecrets.serializer(), f.readText())
        }.getOrNull()
    }

    /** Fold one file-tree result into the per-category report counters. */
    fun applyFileResult(
        report: BackupImporter.CategoryReport,
        files: BackupRestoreFiles.Result,
    ) {
        report.filesWritten += files.written
        report.bytesWritten += files.bytes
        report.missingBlobs += files.missingBlobs
        report.sizeSkippedInPackage += files.sizeSkippedInPackage
        report.notDownloadedInPackage += files.notDownloadedInPackage
        report.rejectedPaths += files.rejectedPaths
    }
}
