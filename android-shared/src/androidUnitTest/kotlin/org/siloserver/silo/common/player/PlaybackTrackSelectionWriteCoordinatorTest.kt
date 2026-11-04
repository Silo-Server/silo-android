package org.siloserver.silo.common.player

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.repository.RoomUserItemStateRepository
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.port.PlaybackWriteScope
import org.siloserver.silo.repository.port.TrackSelectionFingerprintUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTrackSelectionWriteCoordinatorTest {

    @Test
    fun delayedRetiredAdapterCannotOverwriteNewerReplacementAdapterSelection() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SiloDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val scope = PlaybackWriteScope(
                serverId = "s1",
                profileId = "p1",
                credentialGenerationId = null,
                identityGeneration = 4L,
            )
            val repository = RoomUserItemStateRepository(
                db = db,
                snapshotProvider = {
                    AuthScopeSnapshot(
                        serverId = "s1",
                        profileId = "p1",
                        serverUrl = "https://s1.example",
                        profileToken = "p1-token",
                        identityGeneration = 4L,
                    )
                },
            )
            val coordinator = PlaybackTrackSelectionWriteCoordinator()
            val retiredAdapter = AdapterWriter(
                coordinator = coordinator,
                repository = repository,
                scope = scope,
                audioFingerprint = "old-audio",
                subtitleFingerprint = "old-subtitle",
            )
            val replacementAdapter = AdapterWriter(
                coordinator = coordinator,
                repository = repository,
                scope = scope,
                audioFingerprint = "new-audio",
                subtitleFingerprint = "new-subtitle",
            )
            val releaseOldAdapter = Channel<Unit>(Channel.CONFLATED)

            val oldWrite = async {
                releaseOldAdapter.receive()
                retiredAdapter.persist()
            }
            runCurrent()

            assertTrue(replacementAdapter.persist())
            releaseOldAdapter.send(Unit)
            assertTrue(oldWrite.await())

            val row = db.userItemStateDao().get("s1", "", "p1", "c1", 7)
            assertEquals("new-audio", row?.audioFingerprint)
            assertEquals("new-subtitle", row?.subtitleFingerprint)
            assertEquals(0, coordinator.activeKeyCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun completedTicketsReleaseCoordinatorStateAcrossManyPlaybackKeys() = runTest {
        val coordinator = PlaybackTrackSelectionWriteCoordinator()
        val scope = PlaybackWriteScope(
            serverId = "s1",
            profileId = "p1",
            credentialGenerationId = null,
            identityGeneration = 4L,
        )

        repeat(2_000) { index ->
            val ticket = coordinator.capture(
                scope = scope,
                contentId = "content-$index",
                fileId = index + 1,
            )
            assertTrue(coordinator.write(ticket) { true })
        }

        assertEquals(0, coordinator.activeKeyCount)
    }

    @Test
    fun abandonedBlockedPrimaryAndFallbackKeepReplacementSerializedUntilOldWritesExit() = runTest {
        val coordinator = PlaybackTrackSelectionWriteCoordinator()
        val scope = PlaybackWriteScope(
            serverId = "s1",
            profileId = "p1",
            credentialGenerationId = null,
            identityGeneration = 4L,
        )
        var durableValue = "initial"
        val oldPersistStarted = CompletableDeferred<Unit>()
        val releaseOldPersist = CompletableDeferred<Unit>()
        val replacementPersistStarted = CompletableDeferred<Unit>()
        val oldTicket = coordinator.capture(scope, contentId = "c1", fileId = 7)

        val oldWrite = async {
            coordinator.write(oldTicket) {
                oldPersistStarted.complete(Unit)
                releaseOldPersist.await()
                durableValue = "A"
                true
            }
        }
        oldPersistStarted.await()

        val fallbackWrite = async {
            coordinator.write(oldTicket) {
                error("An abandoned fallback must not persist.")
            }
        }
        runCurrent()
        coordinator.abandon(oldTicket)
        coordinator.abandon(oldTicket)
        val replacementTicket = coordinator.capture(scope, contentId = "c1", fileId = 7)
        val replacementWrite = async {
            coordinator.write(replacementTicket) {
                replacementPersistStarted.complete(Unit)
                durableValue = "B"
                true
            }
        }
        runCurrent()

        assertFalse(replacementPersistStarted.isCompleted)
        releaseOldPersist.complete(Unit)
        assertTrue(oldWrite.await())
        assertFalse(fallbackWrite.await())
        assertTrue(replacementWrite.await())
        assertEquals("B", durableValue)
        assertEquals(0, coordinator.activeKeyCount)
    }

    private class AdapterWriter(
        private val coordinator: PlaybackTrackSelectionWriteCoordinator,
        private val repository: RoomUserItemStateRepository,
        private val scope: PlaybackWriteScope,
        private val audioFingerprint: String,
        private val subtitleFingerprint: String,
    ) {
        private val ticket = coordinator.capture(scope, contentId = "c1", fileId = 7)

        suspend fun persist(): Boolean = coordinator.write(ticket) {
            repository.recordTrackSelection(
                scope = scope,
                contentId = "c1",
                fileId = 7,
                audioUpdate = TrackSelectionFingerprintUpdate.Set(audioFingerprint),
                subtitleUpdate = TrackSelectionFingerprintUpdate.Set(subtitleFingerprint),
            )
        }
    }
}
