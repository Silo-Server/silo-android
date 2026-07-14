package org.siloserver.silo.common.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.sync.OutboxOperation
import org.siloserver.silo.common.data.sync.OutboxSyncScheduler
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.port.OutboxHandle
import org.siloserver.silo.repository.port.WriteOutcome
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class RoomUserItemStateRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        SiloDatabase::class.java,
    ).allowMainThreadQueries().build()

    private var nextId = 0
    private val repo = RoomUserItemStateRepository(
        db = db,
        snapshotProvider = { AuthScopeSnapshot("s1", "p1", "https://s1.example", "pt") },
        now = { 1000L },
        idGenerator = { "id-${nextId++}" },
    )

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun recordWatchedWritesProjectionAndContentScopedOutboxOp() = runTest {
        val handle = repo.recordWatched("c1", watched = true)
        assertTrue(handle.opId >= 0)

        assertEquals(true, db.contentItemStateDao().get("s1", "p1", "c1")?.watched)

        val op = db.dirtyOperationDao().dueBatch("s1", "p1", nowMs = 2000L, limit = 10).single()
        assertEquals(OutboxOperation.SET_WATCHED, op.opKind)
        assertNull(op.targetFileId)
        assertEquals("s1|p1|c1|${OutboxOperation.SET_WATCHED}", op.coalesceKey)
    }

    @Test
    fun watchedChangesClearResumeButPreserveFilePreferencesAndReadingState() = runTest {
        val location = "epubcfi(/6/4!/4)"
        repo.recordPosition("c1", fileId = 7, positionSeconds = 456.0, durationSeconds = 3600.0)
        repo.recordAudioTrackSelection("c1", fileId = 7, audioFingerprint = "audio")
        repo.recordSubtitleTrackSelection("c1", fileId = 7, subtitleFingerprint = "subtitle")
        repo.recordEbookProgress("c1", fileId = 7, location = location, progress = 0.42)

        repo.recordWatched("c1", watched = true)

        var row = db.userItemStateDao().get("s1", "p1", "c1", 7)
        assertEquals(0.0, row?.positionSeconds)
        assertEquals(3600.0, row?.durationSeconds)
        assertEquals("audio", row?.audioFingerprint)
        assertEquals("subtitle", row?.subtitleFingerprint)
        assertEquals(location, row?.cfi)
        assertEquals(0.42, row?.readProgress)
        assertNull(repo.localPlaybackProgress("c1"))

        repo.recordPosition("c1", fileId = 7, positionSeconds = 789.0, durationSeconds = 3600.0)
        repo.recordWatched("c1", watched = false)

        row = db.userItemStateDao().get("s1", "p1", "c1", 7)
        assertEquals(0.0, row?.positionSeconds)
        assertNull(repo.localPlaybackProgress("c1"))
    }

    @Test
    fun watchedChangeSupersedesAnOlderPendingPosition() = runTest {
        repo.recordPosition("c1", fileId = 7, positionSeconds = 456.0, durationSeconds = 3600.0)

        repo.recordWatched("c1", watched = true)

        val op = db.dirtyOperationDao().dueBatch("s1", "p1", nowMs = 2000L, limit = 10).single()
        assertEquals(OutboxOperation.SET_WATCHED, op.opKind)
    }

    @Test
    fun terminalWatchedFailureRestoresClearedResumeUnlessPlaybackMovedOn() = runTest {
        repo.recordPosition("c1", fileId = 7, positionSeconds = 456.0, durationSeconds = 3600.0)
        val rejected = repo.recordWatched("c1", watched = true)

        repo.resolve(rejected, WriteOutcome.TERMINAL)

        assertEquals(456.0, repo.localPosition("c1", fileId = 7))

        val secondRejected = repo.recordWatched("c1", watched = true)
        repo.recordPosition("c1", fileId = 7, positionSeconds = 789.0, durationSeconds = 3600.0)
        repo.resolve(secondRejected, WriteOutcome.TERMINAL)
        assertEquals(789.0, repo.localPosition("c1", fileId = 7))
    }

    @Test
    fun repeatedWatchedChangesKeepTheOriginalResumeRollback() = runTest {
        var currentTime = 1000L
        val timedRepo = RoomUserItemStateRepository(
            db = db,
            snapshotProvider = { AuthScopeSnapshot("s1", "p1", "https://s1.example", "pt") },
            now = { currentTime },
            idGenerator = { "timed-${nextId++}" },
        )
        timedRepo.recordPosition("c1", fileId = 7, positionSeconds = 456.0, durationSeconds = 3600.0)
        timedRepo.recordWatched("c1", watched = true)

        currentTime += 1
        val latest = timedRepo.recordWatched("c1", watched = false)
        timedRepo.resolve(latest, WriteOutcome.TERMINAL)

        assertEquals(456.0, timedRepo.localPosition("c1", fileId = 7))
    }

    @Test
    fun newerWatchedChangeInheritsRollbackFromAnInFlightChange() = runTest {
        repo.recordPosition("c1", fileId = 7, positionSeconds = 456.0, durationSeconds = 3600.0)
        val older = repo.recordWatched("c1", watched = true)
        assertEquals(1, db.dirtyOperationDao().claim(older.opId))

        val newer = repo.recordWatched("c1", watched = false)
        repo.resolve(older, WriteOutcome.TERMINAL)
        repo.resolve(newer, WriteOutcome.TERMINAL)

        assertEquals(456.0, repo.localPosition("c1", fileId = 7))
    }

    @Test
    fun syncedWatchedChangeReplaysAfterAnInFlightPosition() = runTest {
        repo.recordPosition("c1", fileId = 7, positionSeconds = 456.0, durationSeconds = 3600.0)
        val position = db.dirtyOperationDao().dueBatch("s1", "p1", nowMs = 2000L, limit = 10).single()
        assertEquals(1, db.dirtyOperationDao().claim(position.id))
        var syncRequests = 0
        val guardedRepo = RoomUserItemStateRepository(
            db = db,
            snapshotProvider = { AuthScopeSnapshot("s1", "p1", "https://s1.example", "pt") },
            syncScheduler = OutboxSyncScheduler { syncRequests += 1 },
            now = { 1000L },
            idGenerator = { "guarded-${nextId++}" },
        )

        val watched = guardedRepo.recordWatched("c1", watched = true)
        guardedRepo.resolve(watched, WriteOutcome.SYNCED)

        assertNotNull(db.dirtyOperationDao().getById(watched.opId))
        assertEquals(1, syncRequests)
    }

    @Test
    fun favoriteToggleDoesNotClobberExistingRating() = runTest {
        repo.recordRating("c1", rating = 5)
        repo.recordFavorite("c1", favorite = true)
        val row = db.contentItemStateDao().get("s1", "p1", "c1")
        assertEquals(5, row?.ratingValue)
        assertEquals(true, row?.favorite)
        // Distinct kinds do not coalesce against each other.
        assertEquals(2, db.dirtyOperationDao().count())
    }

    @Test
    fun repeatedSameKindCoalescesToLatest() = runTest {
        repo.recordWatched("c1", watched = true)
        repo.recordWatched("c1", watched = false)
        assertEquals(1, db.dirtyOperationDao().count())
        val op = db.dirtyOperationDao().dueBatch("s1", "p1", nowMs = 2000L, limit = 10).single()
        assertEquals(false, OutboxOperation.decodeBooleanPayload(op.payloadJson))
    }

    @Test
    fun resolveSyncedDeletesOp() = runTest {
        val handle = repo.recordFavorite("c1", favorite = true)
        repo.resolve(handle, WriteOutcome.SYNCED)
        assertEquals(0, db.dirtyOperationDao().count())
    }

    @Test
    fun resolveTerminalDropsOpAndRevertsProjection() = runTest {
        val handle = repo.recordFavorite("c1", favorite = true)
        assertEquals(true, db.contentItemStateDao().get("s1", "p1", "c1")?.favorite)
        repo.resolve(handle, WriteOutcome.TERMINAL)
        assertEquals(0, db.dirtyOperationDao().count())
        // Optimistic favorite reverted to null so the card overlay defers to server.
        assertNull(db.contentItemStateDao().get("s1", "p1", "c1")?.favorite)
    }

    @Test
    fun localContentStatesReturnsOptimisticWatchedAndFavorite() = runTest {
        repo.recordWatched("c1", watched = true)
        repo.recordFavorite("c2", favorite = true)
        val states = repo.localContentStates(listOf("c1", "c2", "c3"))
        assertEquals(true, states["c1"]?.watched)
        assertEquals(true, states["c2"]?.favorite)
        assertNull(states["c3"]) // no local opinion
    }

    @Test
    fun resolveRetriableKeepsOpPending() = runTest {
        val handle = repo.recordFavorite("c1", favorite = true)
        repo.resolve(handle, WriteOutcome.RETRIABLE)
        assertEquals(1, db.dirtyOperationDao().count())
    }

    @Test
    fun missingScopeRecordsNothing() = runTest {
        val scopeless = RoomUserItemStateRepository(
            db = db,
            snapshotProvider = { null },
            now = { 1000L },
            idGenerator = { "id-x" },
        )
        val handle = scopeless.recordWatched("c1", watched = true)
        assertEquals(OutboxHandle.NONE, handle)
        assertEquals(0, db.dirtyOperationDao().count())
    }

    @Test
    fun recordPositionWritesFileProjectionAndContentCoalescedOp() = runTest {
        repo.recordPosition("c1", fileId = 7, positionSeconds = 123.0, durationSeconds = 3600.0)

        // File-level local projection for resume.
        val row = db.userItemStateDao().get("s1", "p1", "c1", 7)
        assertEquals(123.0, row?.positionSeconds)
        assertEquals(3600.0, row?.durationSeconds)

        // Single content-level position op (coalesce key omits fileId).
        val op = db.dirtyOperationDao().dueBatch("s1", "p1", nowMs = 2000L, limit = 10).single()
        assertEquals(OutboxOperation.SET_POSITION, op.opKind)
        assertEquals("s1|p1|c1|${OutboxOperation.SET_POSITION}", op.coalesceKey)
        assertEquals(7, op.targetFileId)
    }

    @Test
    fun recordPositionCoalescesToLatestPerContent() = runTest {
        repo.recordPosition("c1", fileId = 7, positionSeconds = 10.0, durationSeconds = 3600.0)
        repo.recordPosition("c1", fileId = 7, positionSeconds = 99.0, durationSeconds = 3600.0)
        assertEquals(1, db.dirtyOperationDao().count())
        val op = db.dirtyOperationDao().dueBatch("s1", "p1", nowMs = 2000L, limit = 10).single()
        assertEquals(99.0, OutboxOperation.decodePositionPayload(op.payloadJson).first)
    }

    @Test
    fun localPositionReadsBackTheRecordedResumePoint() = runTest {
        assertNull(repo.localPosition("c1", fileId = 7))
        repo.recordPosition("c1", fileId = 7, positionSeconds = 456.0, durationSeconds = 3600.0)
        assertEquals(456.0, repo.localPosition("c1", fileId = 7))
    }

    @Test
    fun localPlaybackProgressForContentReturnsFurthestPositionWithFileId() = runTest {
        repo.recordPosition("c1", fileId = 7, positionSeconds = 120.0, durationSeconds = 3600.0)
        repo.recordPosition("c1", fileId = 8, positionSeconds = 480.0, durationSeconds = 3700.0)
        repo.recordPosition("c2", fileId = 9, positionSeconds = 90.0, durationSeconds = null)

        val progress = repo.localPlaybackProgressForContent(listOf("c1", "c2", "missing"))

        assertEquals(2, progress.size)
        assertEquals(8, progress["c1"]?.fileId)
        assertEquals(480.0, progress["c1"]?.positionSeconds)
        assertEquals(3700.0, progress["c1"]?.durationSeconds)
        assertEquals(9, progress["c2"]?.fileId)
        assertEquals(90.0, progress["c2"]?.positionSeconds)
        assertEquals(null, progress["c2"]?.durationSeconds)
    }

    @Test
    fun recordTrackSelectionsWritesFileProjectionWithoutOutbox() = runTest {
        repo.recordPosition("c1", fileId = 7, positionSeconds = 456.0, durationSeconds = 3600.0)
        repo.recordAudioTrackSelection("c1", fileId = 7, audioFingerprint = "1|eng|eac3|5.1|false")
        repo.recordSubtitleTrackSelection("c1", fileId = 7, subtitleFingerprint = "off")

        val row = db.userItemStateDao().get("s1", "p1", "c1", 7)
        assertEquals(456.0, row?.positionSeconds)
        assertEquals("1|eng|eac3|5.1|false", row?.audioFingerprint)
        assertEquals("off", row?.subtitleFingerprint)
        assertEquals(
            org.siloserver.silo.repository.port.LocalTrackSelection(
                audioFingerprint = "1|eng|eac3|5.1|false",
                subtitleFingerprint = "off",
            ),
            repo.localTrackSelection("c1", fileId = 7),
        )
        assertEquals(1, db.dirtyOperationDao().count(), "track selections are local-only; position op is unchanged")
    }

    @Test
    fun recordEbookProgressWritesProjectionOpAndReadsBack() = runTest {
        repo.recordEbookProgress("c1", fileId = 7, location = "epubcfi(/6/4!/4)", progress = 0.42)
        val back = repo.localEbookProgress("c1", fileId = 7)
        assertEquals("epubcfi(/6/4!/4)", back?.location)
        assertEquals(0.42, back?.progress)
        val op = db.dirtyOperationDao().dueBatch("s1", "p1", nowMs = 2000L, limit = 10).single()
        assertEquals(OutboxOperation.SET_EBOOK_PROGRESS, op.opKind)
        assertEquals("s1|p1|c1|${OutboxOperation.SET_EBOOK_PROGRESS}", op.coalesceKey)
    }

    @Test
    fun recordPositionRejectsNonFinitePosition() = runTest {
        repo.recordPosition("c1", fileId = 7, positionSeconds = Double.NaN, durationSeconds = 3600.0)
        repo.recordPosition("c1", fileId = 7, positionSeconds = -5.0, durationSeconds = 3600.0)
        // No projection row, no outbox op — invalid values can't poison the drain.
        assertNull(db.userItemStateDao().get("s1", "p1", "c1", 7))
        assertEquals(0, db.dirtyOperationDao().count())
    }

    @Test
    fun recordPositionZeroDoesNotClobberAnExistingResume() = runTest {
        // A good resume point is recorded during playback...
        repo.recordPosition("c1", fileId = 7, positionSeconds = 1200.0, durationSeconds = 3600.0)
        // ...then a player-teardown race snapshots position as 0. That stale 0 must
        // NOT overwrite the resume locally or sync SET_POSITION=0 to the server,
        // else re-entering the detail would show "Play" instead of "Resume".
        repo.recordPosition("c1", fileId = 7, positionSeconds = 0.0, durationSeconds = 3600.0)

        assertEquals(1200.0, repo.localPosition("c1", fileId = 7))
        // Only the original 1200s op is queued; the 0 produced no new/overwriting op.
        val op = db.dirtyOperationDao().dueBatch("s1", "p1", nowMs = 2000L, limit = 10).single()
        assertEquals(1200.0, OutboxOperation.decodePositionPayload(op.payloadJson).first)
    }

    @Test
    fun handleCarriesScopeForInlinePinning() = runTest {
        val handle = repo.recordFavorite("c1", favorite = true)
        assertEquals("s1", handle.scope?.serverId)
        assertEquals("p1", handle.scope?.profileId)
        assertEquals("https://s1.example", handle.scope?.serverUrl)
    }
}
