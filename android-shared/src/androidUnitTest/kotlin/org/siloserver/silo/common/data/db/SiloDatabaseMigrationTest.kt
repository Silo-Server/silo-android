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
    @Test
    fun migration14To15PreservesDownloadsWithUnknownArtworkState() {
        val name = "migration-14-to-15"
        migrationHelper.createDatabase(name, 14).use { database ->
            database.execSQL(
                "INSERT INTO downloads (serverId, profileId, mediaFileId, recordId, contentId, title, mediaType, status, kind, " +
                    "fileSize, bytesSent, createdAt, updatedAtMs) VALUES ('s', 'p', 42, 'row', 'episode', 'Episode', 'tv', " +
                    "'completed', 'queued', 1024, 1024, '2026-10-01T00:00:00Z', 123)",
            )
            database.execSQL("INSERT INTO content_item_state (serverId, profileId, contentId, watched, clientUpdatedAtMs) " +
                "VALUES ('s', 'p', 'episode', 1, 1000)")
            database.execSQL("INSERT INTO user_item_state (serverId, profileId, contentId, fileId, positionSeconds, audioFingerprint, clientUpdatedAtMs) " +
                "VALUES ('s', 'p', 'episode', 42, 30, 'audio', 1000)")
        }
        migrationHelper.runMigrationsAndValidate(name, 15, true).use { database ->
            database.query("SELECT watched, watchedUpdatedAtMs FROM content_item_state").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(true, cursor.isNull(1))
            }
            database.query("SELECT positionSeconds, audioFingerprint, positionUpdatedAtMs FROM user_item_state").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(30.0, cursor.getDouble(0))
                assertEquals("audio", cursor.getString(1))
                assertEquals(true, cursor.isNull(2))
            }
            database.query("SELECT recordId, status, posterIsEpisodeStill, episodeUserDataJson FROM downloads").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("row", cursor.getString(0))
                assertEquals("completed", cursor.getString(1))
                assertEquals(true, cursor.isNull(2))
                assertEquals(true, cursor.isNull(3))
                assertEquals(false, cursor.moveToNext())
            }
        }
    }

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

    @Test
    fun migration8To9PreservesExistingQueueRowsAndRetries() {
        val name = "migration-8-to-9"
        migrationHelper.createDatabase(name, 8).use { database ->
            database.execSQL("INSERT INTO dirty_operations (id, opKind, serverId, profileId, targetContentId, targetFileId, coalesceKey, idempotencyKey, opVersion, payloadJson, state, createdAtMs, attemptCount, lastAttemptAtMs, nextAttemptAtMs, lastError) VALUES (7, 'SET_FAVORITE', 's', 'p', 'item', NULL, 'key', 'command', 1, 'true', 'in_flight', 10, 3, 20, 30, 'retry')")
        }
        migrationHelper.runMigrationsAndValidate(name, 9, true).use { database ->
            database.query("SELECT id, payloadJson, state, attemptCount, nextAttemptAtMs, lastError, membershipAuthority, membershipClaim, membershipOwner FROM dirty_operations").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0)); assertEquals("true", cursor.getString(1))
                assertEquals("in_flight", cursor.getString(2)); assertEquals(3, cursor.getInt(3))
                assertEquals(30L, cursor.getLong(4)); assertEquals("retry", cursor.getString(5))
                assertNull(cursor.getString(6)); assertNull(cursor.getString(7)); assertNull(cursor.getString(8))
                assertEquals(false, cursor.moveToNext())
            }
        }
    }

    @Test
    fun migration10To11PreservesUnownedDeletionIntent() {
        val name = "migration-10-to-11"
        migrationHelper.createDatabase(name, 10).use { database ->
            database.execSQL("INSERT INTO download_deletions (serverId, profileId, recordId, mediaFileId, enqueuedAtMs) VALUES ('s', 'p', 'row', 42, 123)")
        }
        migrationHelper.runMigrationsAndValidate(name, 11, true).use { database ->
            database.query("SELECT recordId, mediaFileId, enqueuedAtMs, loginId, origin, deviceId FROM download_deletions").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("row", cursor.getString(0)); assertEquals(42, cursor.getInt(1))
                assertEquals(123L, cursor.getLong(2))
                assertNull(cursor.getString(3)); assertNull(cursor.getString(4)); assertNull(cursor.getString(5))
                assertEquals(false, cursor.moveToNext())
            }
        }
    }

    @Test
    fun migration11To12KeepsDownloadsWithAnUnknownRevision() {
        val name = "migration-11-to-12"
        migrationHelper.createDatabase(name, 11).use { database ->
            database.execSQL(
                "INSERT INTO downloads (serverId, profileId, mediaFileId, recordId, contentId, title, mediaType, status, kind, " +
                    "fileSize, bytesSent, createdAt, updatedAtMs) VALUES ('s', 'p', 42, 'row', 'movie', 'Movie', 'movie', " +
                    "'completed', 'queued', 1024, 1024, '2026-09-29T00:00:00Z', 123)",
            )
        }
        migrationHelper.runMigrationsAndValidate(name, 12, true).use { database ->
            database.query("SELECT recordId, status, revision FROM downloads").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("row", cursor.getString(0)); assertEquals("completed", cursor.getString(1))
                assertEquals(true, cursor.isNull(2))
                assertEquals(false, cursor.moveToNext())
            }
        }
    }

    @Test
    fun migration12To13KeepsDownloadRevisionsAndAddsNullableOfflineTracks() {
        val name = "migration-12-to-13"
        migrationHelper.createDatabase(name, 12).use { database ->
            database.execSQL(
                "INSERT INTO downloads (serverId, profileId, mediaFileId, recordId, contentId, title, mediaType, " +
                    "status, kind, fileSize, bytesSent, createdAt, updatedAtMs, revision) VALUES " +
                    "('s', 'p', 42, 'dl_1', 'mv_1', 'Example', 'movie', 'completed', 'queued', 1024, 1024, " +
                    "'2026-09-29T00:00:00Z', 123, 7)",
            )
        }
        migrationHelper.runMigrationsAndValidate(name, 13, true).use { database ->
            database.query("SELECT recordId, status, bytesSent, revision, offlineTracksJson FROM downloads").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("dl_1", cursor.getString(0))
                assertEquals("completed", cursor.getString(1))
                assertEquals(1024L, cursor.getLong(2))
                assertEquals(7, cursor.getInt(3))
                assertNull(cursor.getString(4))
                assertEquals(false, cursor.moveToNext())
            }
        }
    }

    @Test
    fun migration13To14KeepsOfflineTracksAndAddsNullableArtworkColumns() {
        val name = "migration-13-to-14"
        migrationHelper.createDatabase(name, 13).use { database ->
            database.execSQL(
                "INSERT INTO downloads (serverId, profileId, mediaFileId, recordId, contentId, title, posterThumbhash, " +
                    "mediaType, status, kind, fileSize, bytesSent, createdAt, updatedAtMs, revision, offlineTracksJson) VALUES " +
                    "('s', 'p', 42, 'dl_1', 'ep_1', 'Example', 'THUMB', 'tv', 'completed', 'queued', 1024, 1024, " +
                    "'2026-09-29T00:00:00Z', 123, 7, '{}')",
            )
        }
        migrationHelper.runMigrationsAndValidate(name, 14, true).use { database ->
            database.query(
                "SELECT recordId, posterThumbhash, revision, offlineTracksJson, offlinePosterPath, " +
                    "offlineSeriesPosterPath, seriesPosterThumbhash FROM downloads",
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("dl_1", cursor.getString(0))
                assertEquals("THUMB", cursor.getString(1))
                assertEquals(7, cursor.getInt(2))
                assertEquals("{}", cursor.getString(3))
                assertNull(cursor.getString(4))
                assertNull(cursor.getString(5))
                assertNull(cursor.getString(6))
                assertEquals(false, cursor.moveToNext())
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "migration-7-to-8"
    }
}
