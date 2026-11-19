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
 * [T-codex-client-fingerprint] Bumped 8 → 9: provider_instances.codex_client_fingerprint.
 * [G-P3] Bumped 9 → 10: the newest migration is now 9→10, which CREATES the four
 * gateway tables (gateway_channels / gateway_accounts / gateway_models /
 * gateway_mappings) instead of adding a column. Every historical addColumn
 * migration (7→8 compat_json, 8→9 codex_client_fingerprint) is KEPT as its own
 * pinned case — a historical migration that silently disappears is exactly what
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

    /** Version this build declares; must equal the newest exported schema. */
    private val currentVersion = 10

    /** The previous version's addColumn delta, pinned so it cannot be silently dropped. */
    private val legacyTable = "provider_instances"
    private val legacyColumn = "codex_client_fingerprint"
    private val legacyMigration = 8 to 9

    private fun source(): String = File(dbDir, "ProviderDatabase.kt").readText()

    /** Full body (source text) of one MIGRATION_x_y, empty string when absent. */
    private fun migrationBody(migration: Pair<Int, Int>): String {
        val src = source()
        val match = migrationRegex.findAll(src)
            .firstOrNull { (it.groupValues[1].toInt() to it.groupValues[2].toInt()) == migration }
            ?: return ""
        val bodyStart = match.range.last + 1
        // The migration body ends at the first '{'-balanced close of the object;
        // it contains nested blocks, so scan braces instead of indexOf('}').
        var depth = 1
        var i = bodyStart
        while (i < src.length && depth > 0) {
            when (src[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return src.substring(bodyStart, i - 1)
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
    fun `database version is 10`() {
        assertTrue(
            "ProviderDatabase must declare version = 10",
            source().contains(Regex("""version\s*=\s*10,""")),
        )
    }

    @Test
    fun `MIGRATION_9_10 creates the four gateway tables`() {
        val body = migrationBody(9 to 10)
        assertTrue("MIGRATION_9_10 must exist in ProviderDatabase.kt", body.isNotEmpty())
        // 四张网关表 + 三个 channelId 索引，一个都不能少。
        val statements = body.split("db.execSQL(")
        for (table in listOf(
            "gateway_channels", "gateway_accounts", "gateway_models", "gateway_mappings",
        )) {
            assertEquals(
                "MIGRATION_9_10 must CREATE TABLE $table exactly once",
                1,
                statements.count { it.contains("CREATE TABLE IF NOT EXISTS $table (") },
            )
        }
        for (index in listOf(
            "index_gateway_accounts_channelId",
            "index_gateway_models_channelId",
            "index_gateway_mappings_channelId",
        )) {
            assertEquals(
                "MIGRATION_9_10 must CREATE INDEX $index exactly once",
                1,
                statements.count { it.contains("CREATE INDEX IF NOT EXISTS $index") },
            )
        }
        // 网关表是新增实体：迁移里绝不允许出现 DROP，否则升级即数据丢失。
        assertFalse("MIGRATION_9_10 must not DROP anything", body.contains("DROP"))
    }

    @Test
    fun `historical MIGRATION_8_9 still adds codex_client_fingerprint`() {
        val body = migrationBody(legacyMigration)
        assertTrue("MIGRATION_8_9 must exist in ProviderDatabase.kt", body.isNotEmpty())
        assertEquals(
            "MIGRATION_8_9 must addColumnIfMissing($legacyTable, $legacyColumn, ...) exactly once",
            1,
            Regex(
                """addColumnIfMissing\("$legacyTable",\s*"$legacyColumn",\s*"INTEGER NOT NULL DEFAULT 0"\)""",
            ).findAll(body).count(),
        )
    }

    @Test
    fun `newest exported schema is version 10`() {
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
    fun `schema 10 carries the four gateway tables`() {
        val newest = exportedSchemas().last().second
        val json = JSONObject(newest.readText()).getJSONObject("database")
        val arr = json.getJSONArray("entities")
        val tables = (0 until arr.length()).map { arr.getJSONObject(it).getString("tableName") }.toSet()
        assertEquals(
            setOf(
                "provider_instances", "provider_model_entries", "provider_model_groups",
                "provider_agent_loop_ids", "provider_config_meta", "provider_thinking_rules",
                "gateway_channels", "gateway_accounts", "gateway_models", "gateway_mappings",
            ),
            tables,
        )
        // 逐表 pin 关键列：gateway 表是 G-P3 的核心资产，列集变了这里立刻炸。
        assertEquals(
            setOf(
                "id", "name", "protocol", "baseUrl", "urlSuffix", "headersJson",
                "sortOrder", "enabled", "createdAt",
            ),
            columnsOf(newest, "gateway_channels").map { it.getString("columnName") }.toSet(),
        )
        assertEquals(
            setOf(
                "id", "channelId", "name", "keyRef", "keyTail", "priority",
                "enabled", "maxConcurrent", "extraJson", "createdAt",
            ),
            columnsOf(newest, "gateway_accounts").map { it.getString("columnName") }.toSet(),
        )
        assertEquals(
            setOf("id", "channelId", "modelId", "enabled", "capabilitiesJson"),
            columnsOf(newest, "gateway_models").map { it.getString("columnName") }.toSet(),
        )
        assertEquals(
            setOf("id", "channelId", "alias", "target", "enabled", "reasoning"),
            columnsOf(newest, "gateway_mappings").map { it.getString("columnName") }.toSet(),
        )
    }

    @Test
    fun `schema 10 carries codex_client_fingerprint on provider_instances`() {
        val newest = exportedSchemas().last().second
        val fields = columnsOf(newest, "provider_instances")
        val col = fields.firstOrNull { it.getString("columnName") == "codex_client_fingerprint" }
        assertNotNull("exported schema must carry provider_instances.codex_client_fingerprint", col)
        assertEquals("INTEGER", col!!.getString("affinity"))
        assertTrue(
            "codex_client_fingerprint must be NOT NULL (entity default 0 backfills)",
            col.getBoolean("notNull"),
        )
    }

    @Test
    fun `schema 10 keeps provider_model_entries shape`() {
        val newest = exportedSchemas().last().second
        val fields = columnsOf(newest, "provider_model_entries")
        val names = fields.map { it.getString("columnName") }.toSet()
        assertEquals(
            setOf(
                "id", "provider_instance_id", "base_model_json", "overrides_json",
                "is_custom", "is_hidden", "sort_order", "user_modified_at", "compat_json",
            ),
            names,
        )
        val compat = fields.firstOrNull { it.getString("columnName") == "compat_json" }
        assertNotNull("provider_model_entries.compat_json must survive into schema 10", compat)
        assertFalse("compat_json must stay nullable", compat!!.getBoolean("notNull"))
    }
}
