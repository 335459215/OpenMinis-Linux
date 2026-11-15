package com.openminis.app.ui.settings.backup

import android.content.SharedPreferences
import com.openminis.app.backup.BackupCategory

/**
 * [T-arch-extract-backup-category-prefs] Selected-category persistence, lifted
 * out of `BackupViewModel` for the per-file line ceiling. Behaviour unchanged.
 */
internal object BackupCategoryPrefs {

    private const val KEY_CATEGORIES = "selectedCategories"

    /**
     * Default selection: everything backupable EXCEPT shared files — that tree is
     * usually several GB and a first-time user should not wait ten minutes for a
     * default they never chose.
     */
    private fun defaults(): Set<BackupCategory> =
        BackupCategory.backupable.toSet() - BackupCategory.SHARED_FILES

    /**
     * [T-backup-empty-selection] `null` (never saved) and "the user unchecked
     * everything" used to collapse into the same value: both produced an empty
     * set, and `ifEmpty { defaults }` then swapped the empty set back to the
     * defaults. So unchecking every category and reopening the screen re-checked
     * them all, and the backup included categories the user had explicitly
     * removed (U22: "我没勾那几项，它为什么备份？").
     *
     * Now only "never saved" means defaults; a saved empty string means the user
     * really wants nothing selected (the UI blocks Start Backup on an empty set).
     */
    fun load(prefs: SharedPreferences): Set<BackupCategory> {
        val raw = prefs.getString(KEY_CATEGORIES, null) ?: return defaults()
        return raw.split(",")
            .mapNotNull(BackupCategory::fromKey)
            .filter { it in BackupCategory.backupable }
            .toSet()
    }

    fun save(prefs: SharedPreferences, selected: Set<BackupCategory>) {
        prefs.edit()
            .putString(KEY_CATEGORIES, selected.joinToString(",") { it.key })
            .apply()
    }
}
