package org.siloserver.silo.common.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SiloDatabaseMigrationTest {
    @get:Rule
    val migrationHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SiloDatabase::class.java,
        )

    @Test
    fun migration7To8PreservesDownloadsAndAddsNullableQualityColumns() {
        migrationHelper.createDatabase(DATABASE_NAME, 7).use { database ->
            database.execSQL(
                """
                INSERT INTO downloads (
                    serverId, profileId, mediaFileId, recordId, contentId, title,
                    mediaType, status, kind, fileSize, bytesSent, createdAt, updatedAtMs
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "server-a",
                    "profile-a",
                    42L,
                    "record-a",
                    "content-a",
                    "Existing download",
                    "video",
                    "complete",
                    "movie",
                    1024L,
                    1024L,
                    "2026-07-27T00:00:00Z",
                    1234L,
                ),
            )
        }

        migrationHelper.runMigrationsAndValidate(DATABASE_NAME, 8, true).use { database ->
            database.query(
                """
                SELECT serverId, profileId, mediaFileId, recordId, quality, effectiveQuality
                FROM downloads
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("server-a", cursor.getString(0))
                assertEquals("profile-a", cursor.getString(1))
                assertEquals(42L, cursor.getLong(2))
                assertEquals("record-a", cursor.getString(3))
                assertNull(cursor.getString(4))
                assertNull(cursor.getString(5))
                assertEquals(false, cursor.moveToNext())
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "migration-7-to-8"
    }

    @Test
    fun migration8To9AddsWatchedIntentTimestampDefaultingToZero() {
        migrationHelper.createDatabase(DATABASE_NAME, 8).use { database ->
            database.execSQL(
                "INSERT INTO content_item_state (serverId, profileId, contentId, watched, clientUpdatedAtMs) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>("server-a", "profile-a", "content-a", 1, 1234L),
            )
        }

        migrationHelper.runMigrationsAndValidate(DATABASE_NAME, 9, true).use { database ->
            database.query("SELECT watched, watchedIntentAtMs FROM content_item_state").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(0L, cursor.getLong(1))
            }
        }
    }
}
