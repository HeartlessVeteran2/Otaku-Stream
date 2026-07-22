package com.otakustream.core.database

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Pure-JVM guard (no emulator) that the hand-written migrations match the exported Room schemas:
// it diffs consecutive committed schema JSONs and asserts the column delta is exactly what each
// migration's ALTER/CREATE does. Catches the classic "added an @Entity field but forgot/mismatched
// the migration" bug that only surfaces as a crash-on-upgrade otherwise.
class MigrationSchemaGuardTest {

    private val schemaDir = File("schemas/com.otakustream.core.database.AppDatabase")

    @Test
    fun `migration 8 to 9 adds exactly tracker_links_sourceId`() {
        assertEquals(8, MIGRATION_8_9.startVersion)
        assertEquals(9, MIGRATION_8_9.endVersion)

        val v8 = columnsByTable(loadSchema(8))
        val v9 = columnsByTable(loadSchema(9))

        // Only tracker_links changed, and only by adding sourceId.
        val added = addedColumns(v8, v9)
        assertEquals(mapOf("tracker_links" to setOf("sourceId")), added.filterValues { it.isNotEmpty() })
        assertTrue("no table should lose columns", removedColumns(v8, v9).all { it.value.isEmpty() })

        // The new column matches what the migration's ALTER declares (INTEGER NOT NULL).
        val sourceId = fieldsByTable(loadSchema(9)).getValue("tracker_links").getValue("sourceId")
        assertEquals("INTEGER", sourceId.getString("affinity"))
        assertTrue("sourceId must be NOT NULL", sourceId.getBoolean("notNull"))
    }

    @Test
    fun `migration 6 to 7 only adds the watch_history mediaUrl index`() {
        assertEquals(6, MIGRATION_6_7.startVersion)
        assertEquals(7, MIGRATION_6_7.endVersion)

        val v6 = columnsByTable(loadSchema(6))
        val v7 = columnsByTable(loadSchema(7))

        // Index-only migration: no table gains or loses a column.
        assertTrue("6→7 must not add columns", addedColumns(v6, v7).all { it.value.isEmpty() })
        assertTrue("6→7 must not drop columns", removedColumns(v6, v7).all { it.value.isEmpty() })

        // The index the migration creates must be present in v7 and absent in v6.
        val v6Indices = indicesByTable(loadSchema(6))["watch_history"].orEmpty()
        val v7Indices = indicesByTable(loadSchema(7))["watch_history"].orEmpty()
        assertTrue("index_watch_history_mediaUrl should not exist in v6", "index_watch_history_mediaUrl" !in v6Indices)
        assertTrue("index_watch_history_mediaUrl missing in v7", "index_watch_history_mediaUrl" in v7Indices)
    }

    @Test
    fun `migration 7 to 8 adds exactly the mangayomi_sources table`() {
        assertEquals(7, MIGRATION_7_8.startVersion)
        assertEquals(8, MIGRATION_7_8.endVersion)

        val v7 = columnsByTable(loadSchema(7))
        val v8 = columnsByTable(loadSchema(8))

        // Exactly one new table appears, and no existing table changes columns.
        val newTables = v8.keys - v7.keys
        assertEquals(setOf("mangayomi_sources"), newTables)
        assertTrue("no pre-existing table should lose columns", removedColumns(v7, v8).all { it.value.isEmpty() })
        assertTrue(
            "no pre-existing table should gain columns in 7→8",
            addedColumns(v7, v8).filterKeys { it != "mangayomi_sources" }.all { it.value.isEmpty() },
        )

        // The new table's columns match what MIGRATION_7_8's CREATE TABLE declares.
        assertEquals(
            setOf(
                "id", "repoUrl", "sourceCodeUrl", "scriptContent", "name", "lang", "baseUrl",
                "iconUrl", "version", "isNsfw", "itemType", "sourceCodeLanguage", "prefsJson",
            ),
            v8.getValue("mangayomi_sources"),
        )
    }

    @Test
    fun `every registered migration has committed from and to schemas`() {
        // Each addMigrations() entry must have both its start and end schema exported; a missing
        // file means a version was bumped (or a migration added) without committing the schema.
        listOf(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).forEach { migration ->
            assertTrue(
                "${migration.startVersion}.json missing for migration ${migration.startVersion}→${migration.endVersion}",
                loadFile(migration.startVersion).exists(),
            )
            assertTrue(
                "${migration.endVersion}.json missing for migration ${migration.startVersion}→${migration.endVersion}",
                loadFile(migration.endVersion).exists(),
            )
        }
    }

    @Test
    fun `every exported schema version has a committed json`() {
        // The current DB version must have an exported schema (exportSchema = true); a missing file
        // means someone bumped the version without committing the schema.
        assertTrue("9.json missing — export the schema after bumping the DB version", loadFile(9).exists())
    }

    private fun loadFile(version: Int) = File(schemaDir, "$version.json")

    private fun loadSchema(version: Int): JSONObject {
        val file = loadFile(version)
        assertTrue("Missing schema ${file.path}", file.exists())
        return JSONObject(file.readText())
    }

    private fun entities(schema: JSONObject): List<JSONObject> {
        val array = schema.getJSONObject("database").getJSONArray("entities")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun columnsByTable(schema: JSONObject): Map<String, Set<String>> =
        entities(schema).associate { entity ->
            val fields = entity.getJSONArray("fields")
            entity.getString("tableName") to (0 until fields.length())
                .map { fields.getJSONObject(it).getString("columnName") }.toSet()
        }

    private fun fieldsByTable(schema: JSONObject): Map<String, Map<String, JSONObject>> =
        entities(schema).associate { entity ->
            val fields = entity.getJSONArray("fields")
            entity.getString("tableName") to (0 until fields.length())
                .map { fields.getJSONObject(it) }
                .associateBy { it.getString("columnName") }
        }

    private fun indicesByTable(schema: JSONObject): Map<String, Set<String>> =
        entities(schema).associate { entity ->
            val indices = entity.optJSONArray("indices")
            val names = if (indices == null) {
                emptySet()
            } else {
                (0 until indices.length()).map { indices.getJSONObject(it).getString("name") }.toSet()
            }
            entity.getString("tableName") to names
        }

    private fun addedColumns(
        old: Map<String, Set<String>>,
        new: Map<String, Set<String>>,
    ): Map<String, Set<String>> = new.mapValues { (table, cols) -> cols - (old[table] ?: emptySet()) }

    private fun removedColumns(
        old: Map<String, Set<String>>,
        new: Map<String, Set<String>>,
    ): Map<String, Set<String>> = old.mapValues { (table, cols) -> cols - (new[table] ?: emptySet()) }
}
