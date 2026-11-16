package com.openminis.app.data.db

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level replay of the ProviderDatabase migration chain against the newest
 * exported schema — the SchemaMigrationDriftTest approach scaled to `provider.db`,
 * whose migrations all live in ProviderDatabase.kt. Room's MigrationTestHelper
 * needs an instrumented device (room-testing is androidTest-only here), so this
 * test replays the hand-written DDL from source and compares the resulting
 * column set with the exported schema JSON.
 *
 * [T-codex-client-fingerprint] Bumped 8 → 9: the newest migration is now
 * 8→9 (provider_instances.codex_client_fingerprint). The 7→8 assertion for
 * provider_model_entries.compat_json is KEPT as a separate case rather than
 * retargeted — a historical migration that silently disappears is exactly what
 * this suite exists to catch, and retargeting would have let that slip through.
 *
 * Scope note (mirrors SchemaMigrationDriftTest limitations): column comparison
 * is name-only; affinity / notNull / defaults are verified by the instrumented
 * ProviderDatabaseCompatJsonMigrationTest on devices.
 */
class ProviderDatabaseCompatSchemaTest {

    private val dbDir: File = resolveRepoFile(
        "src/main/java/com/openminis/app/data/db",
        "src/android/app/src/main/java/com/openminis/app/data/db",
    )
    private val schemaDir: File = resolveRepoFile(
        "schemas/com.openminis.app.data.db.ProviderDatabase",
        "src/android/app/schemas/com.openminis.app.data.db.ProviderDatabase",
    )

    /** Gradle runs unit tests from the module dir; local runs from the repo root. */
    private fun resolveRepoFile(vararg candidates: String): File {
        candidates.firstOrNull { File(it).isDirectory || File(it).isFile }?.let { return File(it) }
        return File(candidates.first())
    }

    private val migrationRegex = Regex(
        """\bMIGRATION_(\d+)_(\d+)\s*=\s*object\s*:\s*Migration\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*\{""",
    )
    private val addColumnRegex = Regex(
        """addColumnIfMissing\("([A-Za-z_][A-Za-z0-9_]*)",\s*"([A-Za-z_][A-Za-z0-9_]*)"\s*,\s*"([A-Za-z_][A-Za-z0-9_ ]*)"\)""",
    )

    /** Version this build declares; must equal the newest exported schema. */
    private val currentVersion = 9

    /** Column + migration under test for the CURRENT version. */
    private val currentTable = "provider_instances"
    private val currentColumn = "codex_client_fingerprint"
    private val currentMigration = 8 to 9

    /** The previous version's delta, pinned so it cannot be silently dropped. */
    private val legacyTable = "provider_model_entries"
    private val legacyColumn = "compat_json"
    private val legacyMigration = 7 to 8

    private fun source(): String = File(dbDir, "ProviderDatabase.kt").readText()

    /** All addColumnIfMissing calls inside the body of one MIGRATION_x_y. */
    private fun columnsAddedBy(migration: Pair<Int, Int>): List<List<String>> {
        val src = source()
        val match = migrationRegex.findAll(src)
            .firstOrNull { (it.groupValues[1].toInt() to it.groupValues[2].toInt()) == migration }
            ?: return emptyList()
        val bodyStart = match.range.last + 1
        val segment = src.substring(bodyStart, src.indexOf('}', bodyStart))
        return addColumnRegex.findAll(segment).map { it.groupValues }.toList()
    }

    private fun exportedSchemas(): List<Pair<Int, File>> =
        (schemaDir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f ->
                val db = JSONObject(f.readText()).getJSONObject("database")
                db.getInt("version") to f
            }
            .sortedBy { it.first }

    private fun columnsOf(schemaFile: File, table: String): List<JSONObject> {
        val json = JSONObject(schemaFile.readText()).getJSONObject("database")
        val arr = json.getJSONArray("entities")
        val entity = (0 until arr.length()).map { arr.getJSONObject(it) }
            .firstOrNull { it.getString("tableName") == table }
        assertNotNull("exported schema must describe $table", entity)
        val fields = entity!!.getJSONArray("fields")
        return (0 until fields.length()).map { fields.getJSONObject(it) }
    }

    @Test
    fun `database version is 9`() {
        assertTrue(
            "ProviderDatabase must declare version = 9",
            source().contains(Regex("""version\s*=\s*9,""")),
        )
    }

    @Test
    fun `MIGRATION_8_9 adds codex_client_fingerprint as NOT NULL DEFAULT 0`() {
        val added = columnsAddedBy(currentMigration)
            .filter { it[1] == currentTable && it[2] == currentColumn }
        assertEquals(
            "MIGRATION_8_9 must addColumnIfMissing($currentTable, $currentColumn, ...) exactly once",
            1,
            added.size,
        )
        // 存量行必须回填为关：DEFAULT 0 缺了的话 NOT NULL 列加不上去，迁移直接崩。
        val type = added[0][3]
        assertTrue("column type must be INTEGER NOT NULL DEFAULT 0, got '$type'", type.startsWith("INTEGER"))
        assertTrue("column must be NOT NULL", type.contains("NOT NULL"))
        assertTrue("column must default to 0 so existing rows read as off", type.contains("DEFAULT 0"))
    }

    @Test
    fun `historical MIGRATION_7_8 still adds compat_json`() {
        val added = columnsAddedBy(legacyMigration)
            .filter { it[1] == legacyTable && it[2] == legacyColumn }
        assertEquals(
            "MIGRATION_7_8 must still addColumnIfMissing($legacyTable, $legacyColumn, TEXT)",
            1,
            added.size,
        )
        assertEquals("compat_json must stay nullable TEXT", "TEXT", added[0][3])
    }

    @Test
    fun `newest exported schema is version 9`() {
        val schemas = exportedSchemas()
        assertTrue("at least two schemas must be exported", schemas.size >= 2)
        assertEquals(
            "newest exported ProviderDatabase schema must be version $currentVersion",
            currentVersion,
            schemas.last().first,
        )
        // 版本号必须与源码声明一致，否则 Room 在运行时做身份校验会直接抛
        // IllegalStateException（"Room cannot verify the data integrity"）。
        assertTrue(
            "ProviderDatabase.kt version must equal the newest exported schema version",
            source().contains(Regex("""version\s*=\s*${schemas.last().first},""")),
        )
    }

    @Test
    fun `schema 9 carries codex_client_fingerprint on provider_instances`() {
        val newest = exportedSchemas().last().second
        val fields = columnsOf(newest, currentTable)
        val col = fields.firstOrNull { it.getString("columnName") == currentColumn }
        assertNotNull("exported schema must carry $currentTable.$currentColumn", col)
        assertEquals("INTEGER", col!!.getString("affinity"))
        assertTrue("$currentColumn must be NOT NULL (entity default 0 backfills)", col.getBoolean("notNull"))
    }

    @Test
    fun `schema 9 keeps the full provider_instances column set`() {
        val newest = exportedSchemas().last().second
        val names = columnsOf(newest, currentTable).map { it.getString("columnName") }.toSet()
        // 逐列 pin 住：历史迁移被静默丢掉时，这里会立刻炸，而不是被"重新加一遍"
        // 掩盖过去。顺序不 pin（SQLite 列序不影响读写），集合必须完全相等。
        assertEquals(
            setOf(
                "id", "label", "provider_type", "credential_type", "custom_base_url",
                "append_v1_suffix", "use_responses_api", "azure_mode", "force_thinking",
                "image_endpoint_mode", "image_endpoint_resolved", "custom_user_agent",
                "codex_client_fingerprint",
                "is_enabled", "pinned", "sort_order", "created_at",
            ),
            names,
        )
    }

    @Test
    fun `schema 9 keeps provider_model_entries shape`() {
        val newest = exportedSchemas().last().second
        val fields = columnsOf(newest, legacyTable)
        val names = fields.map { it.getString("columnName") }.toSet()
        assertEquals(
            setOf(
                "id", "provider_instance_id", "base_model_json", "overrides_json",
                "is_custom", "is_hidden", "sort_order", "user_modified_at", "compat_json",
            ),
            names,
        )
        val compat = fields.firstOrNull { it.getString("columnName") == legacyColumn }
        assertNotNull("$legacyTable.$legacyColumn must survive into schema 9", compat)
        assertFalse("compat_json must stay nullable", compat!!.getBoolean("notNull"))
    }

    @Test
    fun `schema 9 describes every entity the database declares`() {
        val newest = exportedSchemas().last().second
        val json = JSONObject(newest.readText()).getJSONObject("database")
        val arr = json.getJSONArray("entities")
        val tables = (0 until arr.length()).map { arr.getJSONObject(it).getString("tableName") }.toSet()
        assertEquals(
            setOf(
                "provider_instances", "provider_model_entries", "provider_model_groups",
                "provider_agent_loop_ids", "provider_config_meta", "provider_thinking_rules",
            ),
            tables,
        )
    }
}
