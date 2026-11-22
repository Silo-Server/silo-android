package org.siloserver.silo.tv.ui.screens.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.playback.CommittedSubtitle
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A pre-playback subtitle choice is applied by replanning the instant playback
 * starts, and a fresh stream reports READY seconds before it publishes its text
 * tracks. The mount deadline must not expire during that gap, or the choice is
 * failed against a player that had nothing to mount — which surfaced as
 * subtitles refusing to turn on when enabled before playback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvSubtitleMountDeadlineTest {

    @Test
    fun `deadline does not fail the mount while the player has no text tracks`() = runTest {
        var tracksPublished = false
        val port = DeadlineStagedPort()
        val adapter = TvSubtitleTransactionAdapter(
            scope = backgroundScope,
            stagedPort = port,
            persistencePort = DeadlinePersistence(),
            durablePersistenceScope = backgroundScope,
            onCommittedPlayback = { TvSubtitleAdoptionResult.Adopted },
            hasMountableTracks = { tracksPublished },
        )
        adapter.resetContent(deadlineContext(), committedIdentity = SubtitleIdentity.Off)

        val target = SubtitleIdentity.ServerSidecar(serverIndex = 3)
        adapter.select(target)
        runCurrent()
        port.completeStage(deadlineCandidate(index = 3))
        runCurrent()
        assertEquals(target, adapter.snapshot.localMountIdentity)

        // Well past the 5s deadline, but the stream still has no text tracks.
        advanceTimeBy(20_000)
        runCurrent()
        assertNotNull(
            adapter.snapshot.localMountIdentity,
            "mount was failed while the player had nothing to mount",
        )

        // Tracks finally publish and the mount resolves normally.
        tracksPublished = true
        adapter.reportMountedSelection(
            identity = target,
            selected = true,
            snapshotKey = "tracks-arrived",
            settled = true,
        )
        runCurrent()
        assertNull(adapter.snapshot.localMountIdentity)
    }

    @Test
    fun `tracks published just before a check still get a whole window to confirm`() = runTest {
        var tracksPublished = false
        val port = DeadlineStagedPort()
        val adapter = TvSubtitleTransactionAdapter(
            scope = backgroundScope,
            stagedPort = port,
            persistencePort = DeadlinePersistence(),
            durablePersistenceScope = backgroundScope,
            onCommittedPlayback = { TvSubtitleAdoptionResult.Adopted },
            hasMountableTracks = { tracksPublished },
        )
        adapter.resetContent(deadlineContext(), committedIdentity = SubtitleIdentity.Off)
        val target = SubtitleIdentity.ServerSidecar(serverIndex = 3)
        adapter.select(target)
        runCurrent()
        port.completeStage(deadlineCandidate(index = 3))
        runCurrent()

        // Published 100 ms before the 10 s check: the window starts there, so
        // the selection has five seconds to confirm, not 100 ms.
        advanceTimeBy(9_900)
        runCurrent()
        tracksPublished = true
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(target, adapter.snapshot.localMountIdentity)
        advanceTimeBy(100)
        runCurrent()
        assertNull(adapter.snapshot.localMountIdentity)
    }

    @Test
    fun `deadline still fails a stream that never publishes text tracks`() = runTest {
        val port = DeadlineStagedPort()
        val adapter = TvSubtitleTransactionAdapter(
            scope = backgroundScope,
            stagedPort = port,
            persistencePort = DeadlinePersistence(),
            durablePersistenceScope = backgroundScope,
            onCommittedPlayback = { TvSubtitleAdoptionResult.Adopted },
            hasMountableTracks = { false },
        )
        adapter.resetContent(deadlineContext(), committedIdentity = SubtitleIdentity.Off)

        adapter.select(SubtitleIdentity.ServerSidecar(serverIndex = 3))
        runCurrent()
        port.completeStage(deadlineCandidate(index = 3))
        runCurrent()

        // The overall cap still bounds the wait, so the HUD cannot sit in
        // "Applying" forever on a stream with no text tracks at all.
        advanceTimeBy(60_000) // beyond the adapter's overall mount-wait cap
        runCurrent()

        assertNull(adapter.snapshot.localMountIdentity)
    }

    @Test
    fun `a new plan for the same file does not reset an in-flight selection`() = runTest {
        // versionId is "<fileId>:<planId>", and every replan mints a new planId.
        // The subtitle transaction's own replan therefore changes versionId --
        // if that counts as a content change, the transaction resets itself and
        // the selection is lost the instant its mount matches.
        val port = DeadlineStagedPort()
        val adapter = TvSubtitleTransactionAdapter(
            scope = backgroundScope,
            stagedPort = port,
            persistencePort = DeadlinePersistence(),
            durablePersistenceScope = backgroundScope,
            onCommittedPlayback = { TvSubtitleAdoptionResult.Adopted },
            hasMountableTracks = { true },
        )
        adapter.resetContent(deadlineContext(), committedIdentity = SubtitleIdentity.Off)

        val target = SubtitleIdentity.ServerSidecar(serverIndex = 3)
        adapter.select(target)
        runCurrent()
        assertEquals(target, adapter.snapshot.pendingIdentity)

        // Same episode, same file — only the plan hash moved on.
        adapter.updatePlaybackContext(
            deadlineContext().copy(versionId = "11:plan:a-completely-new-plan"),
        )
        runCurrent()

        assertEquals(
            target,
            adapter.snapshot.pendingIdentity,
            "a replan of the same file must not discard the selection in flight",
        )
    }

    private fun deadlineContext(): TvSubtitlePlaybackContext = TvSubtitlePlaybackContext(
        contentId = "movie",
        mediaFileId = 11,
        versionId = "version-11",
        sessionId = "session-a",
        positionSeconds = 42.0,
        audioTrackIndex = 1,
        qualityPreference = "auto",
        subtitleTracks = emptyList(),
        writeScope = tvTestPlaybackWriteScope,
    )

    private fun deadlineCandidate(index: Int): TvStagedSubtitleCandidate =
        TvStagedSubtitleCandidate(
            id = "candidate-$index",
            sessionId = "session-candidate-$index",
            selectedAudioIndex = 1,
            selectedSubtitleIndex = index,
            subtitleMode = PlaybackSubtitleModeV3.RENDER,
            hasSidecar = true,
            subtitleTracks = emptyList(),
            qualityPreference = "auto",
        )
}

private class DeadlineStagedPort : TvSubtitleStagedReplanPort {
    private val outcomes = Channel<TvStagedSubtitleCandidate>(Channel.UNLIMITED)

    override suspend fun stage(
        request: TvSubtitleStageRequest,
    ): ApiResult<TvStagedSubtitleCandidate> = ApiResult.Success(outcomes.receive())

    override suspend fun commit(
        candidate: TvStagedSubtitleCandidate,
    ): ApiResult<TvSubtitleCommittedPlayback> = ApiResult.Success(
        TvSubtitleCommittedPlayback(
            sessionId = candidate.sessionId,
            subtitleTracks = candidate.subtitleTracks,
            outputRouteGeneration = candidate.outputRouteGeneration,
        ),
    )

    override suspend fun discard(candidate: TvStagedSubtitleCandidate) = Unit

    override suspend fun abandonCommitted(playback: TvSubtitleCommittedPlayback) = Unit

    suspend fun completeStage(candidate: TvStagedSubtitleCandidate) {
        outcomes.send(candidate)
    }
}

private class DeadlinePersistence : TvSubtitlePersistencePort {
    override suspend fun persist(
        committed: CommittedSubtitle,
        context: TvSubtitlePlaybackContext,
    ) = true
}
