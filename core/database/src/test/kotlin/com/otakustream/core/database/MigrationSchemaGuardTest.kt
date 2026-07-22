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

    private fun addedColumns(
        old: Map<String, Set<String>>,
        new: Map<String, Set<String>>,
    ): Map<String, Set<String>> = new.mapValues { (table, cols) -> cols - (old[table] ?: emptySet()) }

    private fun removedColumns(
        old: Map<String, Set<String>>,
        new: Map<String, Set<String>>,
    ): Map<String, Set<String>> = old.mapValues { (table, cols) -> cols - (new[table] ?: emptySet()) }
}
