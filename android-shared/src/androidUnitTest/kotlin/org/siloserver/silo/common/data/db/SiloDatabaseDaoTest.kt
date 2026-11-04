package org.siloserver.silo.common.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.siloserver.silo.common.data.db.entity.ContentItemStateEntity
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity
import org.siloserver.silo.common.data.db.entity.DownloadEntity
import org.siloserver.silo.common.data.db.entity.LegacyImportEntity
import org.siloserver.silo.common.data.db.entity.UserItemStateEntity
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class SiloDatabaseDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        SiloDatabase::class.java,
    ).allowMainThreadQueries().build()

    @AfterTest
    fun tearDown() = db.close()

    private fun userState(
        serverId: String = "s1",
        profileId: String = "p1",
        contentId: String = "c1",
        fileId: Int = 7,
        positionSeconds: Double = 120.0,
        clientUpdatedAtMs: Long = 1000L,
    ) = UserItemStateEntity(
        serverId = serverId,
        accountId = "a1",
        profileId = profileId,
        contentId = contentId,
        fileId = fileId,
        positionSeconds = positionSeconds,
        durationSeconds = 3600.0,
        audioFingerprint = null,
        subtitleFingerprint = null,
        cfi = null,
        readProgress = null,
        clientUpdatedAtMs = clientUpdatedAtMs,
        serverUpdatedAtMs = null,
    )

    @Test
    fun userItemStateUpsertAndReadByScope() = runTest {
        val dao = db.userItemStateDao()
        dao.upsert(userState())
        assertEquals(120.0, dao.get("s1", "a1", "p1", "c1", 7)?.positionSeconds)
        // Different server is a different scope — must not collide.
        assertNull(dao.get("s2", "a1", "p1", "c1", 7))
    }

    @Test
    fun userItemStateReplaceOnConflict() = runTest {
        val dao = db.userItemStateDao()
        dao.upsert(userState(positionSeconds = 10.0))
        dao.upsert(userState(positionSeconds = 99.0))
        assertEquals(99.0, dao.get("s1", "a1", "p1", "c1", 7)?.positionSeconds)
    }

    @Test
    fun recentlyPlayedOrdersByClientUpdatedDescending() = runTest {
        val dao = db.userItemStateDao()
        dao.upsert(userState(fileId = 1, clientUpdatedAtMs = 100L))
        dao.upsert(userState(fileId = 2, clientUpdatedAtMs = 300L))
        dao.upsert(userState(fileId = 3, clientUpdatedAtMs = 200L))
        val resume = dao.recentlyPlayed("s1", "a1", "p1", limit = 10)
        assertEquals(listOf(2, 3, 1), resume.map { it.fileId })
    }

    @Test
    fun contentItemStateUpsertReadAndFavoritesScan() = runTest {
        val dao = db.contentItemStateDao()
        dao.upsert(
            ContentItemStateEntity(
                serverId = "s1", accountId = "a1", profileId = "p1", contentId = "c1",
                watched = true, ratingValue = 4, favorite = true,
                clientUpdatedAtMs = 5L, serverUpdatedAtMs = null,
            ),
        )
        dao.upsert(
            ContentItemStateEntity(
                serverId = "s1", accountId = "a1", profileId = "p1", contentId = "c2",
                watched = null, ratingValue = null, favorite = false,
                clientUpdatedAtMs = 6L, serverUpdatedAtMs = null,
            ),
        )
        assertEquals(4, dao.get("s1", "a1", "p1", "c1")?.ratingValue)
        assertEquals(listOf("c1"), dao.favorites("s1", "a1", "p1").map { it.contentId })
    }

    @Test
    fun dirtyOperationCoalesceReplacesPendingSameKey() = runTest {
        val dao = db.dirtyOperationDao()
        dao.enqueueCoalescing(op(coalesceKey = "k1", payload = "1", idempotencyKey = "i1"))
        dao.enqueueCoalescing(op(coalesceKey = "k1", payload = "2", idempotencyKey = "i2"))
        // Older pending op with the same coalesce key was dropped.
        assertEquals(1, dao.count())
        val due = dao.dueBatch("s1", "a1", "p1", nowMs = 10L, limit = 10)
        assertEquals("2", due.single().payloadJson)
    }

    @Test
    fun dirtyOperationDueBatchExcludesNotYetDue() = runTest {
        val dao = db.dirtyOperationDao()
        dao.enqueueCoalescing(op(coalesceKey = "a", idempotencyKey = "ia", nextAttemptAtMs = 0L))
        dao.enqueueCoalescing(op(coalesceKey = "b", idempotencyKey = "ib", nextAttemptAtMs = 5_000L))
        assertEquals(1, dao.dueBatch("s1", "a1", "p1", nowMs = 1_000L, limit = 10).size)
    }

    @Test
    fun dirtyOperationClaimIsAtomic() = runTest {
        val dao = db.dirtyOperationDao()
        val id = dao.enqueueCoalescing(op(coalesceKey = "a", idempotencyKey = "ia"))
        // First claim wins (1 row updated); a second claim sees it already
        // in-flight and updates nothing.
        assertEquals(1, dao.claim(id))
        assertEquals(0, dao.claim(id))
    }

    @Test
    fun dirtyOperationDeleteOnAck() = runTest {
        val dao = db.dirtyOperationDao()
        val id = dao.enqueueCoalescing(op(coalesceKey = "a", idempotencyKey = "ia"))
        dao.deleteById(id)
        assertEquals(0, dao.count())
    }

    @Test
    fun downloadProjectionRoundTripAndStatusScan() = runTest {
        val dao = db.downloadDao()
        dao.upsert(download(mediaFileId = 1, recordId = "r1", status = "completed"))
        dao.upsert(download(mediaFileId = 2, recordId = "r2", status = "downloading"))
        assertEquals("r1", dao.get("s1", "a1", "p1", 1)?.recordId)
        assertEquals(2, dao.getAll("s1", "a1", "p1").size)
        assertEquals(1, dao.getByStatus("s1", "a1", "p1", "downloading").size)
        assertNotNull(dao.getByRecordId("r2"))
    }

    @Test
    fun legacyImportUpsertIsIdempotentPerKindAndPath() = runTest {
        val dao = db.legacyImportDao()
        dao.upsert(legacyImport(hash = "h1"))
        dao.upsert(legacyImport(hash = "h2")) // same (kind, path) → replace, not duplicate
        assertEquals(1, dao.count())
        assertEquals("h2", dao.get("download_sidecar", "/path/7.record.json")?.sourceHash)
    }

    private fun op(
        coalesceKey: String,
        idempotencyKey: String,
        payload: String = "{}",
        nextAttemptAtMs: Long = 0L,
    ) = DirtyOperationEntity(
        opKind = "SET_POSITION",
        serverId = "s1",
        accountId = "a1",
        profileId = "p1",
        targetContentId = "c1",
        targetFileId = 7,
        coalesceKey = coalesceKey,
        idempotencyKey = idempotencyKey,
        payloadJson = payload,
        createdAtMs = 1L,
        nextAttemptAtMs = nextAttemptAtMs,
    )

    private fun download(
        mediaFileId: Int,
        recordId: String,
        status: String,
    ) = DownloadEntity(
        serverId = "s1",
        accountId = "a1",
        profileId = "p1",
        mediaFileId = mediaFileId,
        recordId = recordId,
        contentId = "c1",
        title = "Title",
        subtitle = null,
        posterUrl = null,
        posterThumbhash = null,
        year = null,
        seriesTitle = null,
        seasonNumber = null,
        episodeNumber = null,
        fileName = "file.mkv",
        container = "mkv",
        localUri = null,
        mediaType = "movie",
        overview = null,
        author = null,
        narrator = null,
        durationSeconds = null,
        chaptersJson = null,
        status = status,
        kind = "queued",
        fileSize = 0L,
        bytesSent = 0L,
        createdAt = "2026-06-16T00:00:00Z",
        completedAt = null,
        updatedAtMs = 1L,
    )

    private fun legacyImport(hash: String) = LegacyImportEntity(
        sourceKind = "download_sidecar",
        sourcePath = "/path/7.record.json",
        sourceHash = hash,
        sourceMtimeMs = 1L,
        importedAtMs = 2L,
        importVersion = 1,
        status = "imported",
        error = null,
    )
}
