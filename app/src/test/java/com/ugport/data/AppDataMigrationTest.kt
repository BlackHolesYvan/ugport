package com.ugport.data

import com.ugport.data.model.AppData
import com.ugport.data.model.AppDataMigrations
import com.ugport.data.model.Shortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDataMigrationTest {

    @Test
    fun schemaOneDisablesBackgroundForAllShortcuts() {
        val data = AppData(
            schema = 1,
            shortcuts = listOf(
                Shortcut(
                    id = "s1",
                    nasId = "n1",
                    name = "Ting Reader",
                    port = 20260,
                    allowBackground = true,
                ),
                Shortcut(
                    id = "s2",
                    nasId = "n1",
                    name = "qBittorrent",
                    port = 20180,
                    allowBackground = true,
                ),
            ),
        )

        val migrated = AppDataMigrations.migrate(data)

        assertEquals(2, migrated.schema)
        assertTrue(migrated.shortcuts.none { it.allowBackground })
    }

    @Test
    fun schemaTwoKeepsUserChoice() {
        val data = AppData(
            schema = 2,
            shortcuts = listOf(
                Shortcut(
                    id = "s1",
                    nasId = "n1",
                    name = "Ting Reader",
                    port = 20260,
                    allowBackground = true,
                ),
            ),
        )

        val migrated = AppDataMigrations.migrate(data)

        assertEquals(2, migrated.schema)
        assertTrue(migrated.shortcuts.single().allowBackground)
    }

    @Test
    fun currentSchemaIsIdempotent() {
        val data = AppData(
            schema = 2,
            shortcuts = listOf(
                Shortcut(id = "s1", nasId = "n1", name = "qBittorrent", port = 20180),
            ),
        )

        assertEquals(data, AppDataMigrations.migrate(AppDataMigrations.migrate(data)))
        assertFalse(data.shortcuts.single().allowBackground)
    }
}
