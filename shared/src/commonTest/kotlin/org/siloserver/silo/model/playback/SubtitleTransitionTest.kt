package org.siloserver.silo.model.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleTransitionTest {
    @Test
    fun serverTrackCommitsAsWhatThePlanDelivers() {
        val media = SubtitleMediaIdentity(trackId = "file:11:subtitle:4", label = "Full Subs", codecFamily = "ass")
        val sidecar = SubtitleIdentity.ServerSidecar(4, media)
        val burnedIn = SubtitleIdentity.ServerBurnIn(4, media)

        assertEquals(burnedIn, sidecar.deliveredAs(PlaybackSubtitleModeV3.BURN_IN))
        assertEquals(sidecar, burnedIn.deliveredAs(PlaybackSubtitleModeV3.RENDER))
        assertEquals(sidecar, burnedIn.deliveredAs(PlaybackSubtitleModeV3.CONVERT))
        assertEquals(sidecar, sidecar.deliveredAs(PlaybackSubtitleModeV3.RENDER))
        assertEquals(burnedIn, burnedIn.deliveredAs(PlaybackSubtitleModeV3.OFF))

        assertTrue(sidecar.playsAs(burnedIn))
        assertTrue(!burnedIn.playsAs(sidecar))
        assertTrue(!SubtitleIdentity.ServerSidecar(5, media).playsAs(burnedIn))
    }

    @Test
    fun `A remains committed while B applies then B commits`() {
        val initial = SubtitleTransitionState.committed(serverSidecar(3))
        val applying = reduceSubtitleTransition(initial, SelectSubtitle(serverSidecar(4)))
        assertEquals(serverSidecar(3), applying.state.committed.identity)
        assertEquals(serverSidecar(4), applying.state.pending?.identity)
        assertTrue(applying.effects.single() is StageSubtitleReplan)

        val committed = reduceSubtitleTransition(
            applying.state,
            StagedSubtitleValidated(applying.state.pending!!.generation, candidate("s2")),
        )
        assertEquals(serverSidecar(4), committed.state.committed.identity)
        assertNull(committed.state.pending)
        assertEquals(
            listOf(CommitStagedSubtitleReplan::class, PersistSubtitleSelection::class),
            committed.effects.map { it::class },
        )
    }

    @Test
    fun `A to B to C retains A and discards the superseded B response`() {
        val initial = SubtitleTransitionState.committed(serverSidecar(1))
        val applyingB = reduceSubtitleTransition(initial, SelectSubtitle(serverSidecar(2)))
        val applyingC = reduceSubtitleTransition(applyingB.state, SelectSubtitle(serverSidecar(3)))

        assertEquals(serverSidecar(1), applyingC.state.committed.identity)
        assertEquals(serverSidecar(3), applyingC.state.pending?.identity)
        assertTrue(applyingC.state.pending!!.generation > applyingB.state.pending!!.generation)

        val staleB = reduceSubtitleTransition(
            applyingC.state,
            StagedSubtitleValidated(applyingB.state.pending!!.generation, candidate("s2")),
        )
        assertEquals(applyingC.state, staleB.state)
        assertEquals(candidate("s2"), assertIs<DiscardStagedSubtitleReplan>(staleB.effects.single()).candidate)

        val committedC = reduceSubtitleTransition(
            staleB.state,
            StagedSubtitleValidated(applyingC.state.pending!!.generation, candidate("s3")),
        )
        assertEquals(serverSidecar(3), committedC.state.committed.identity)
        assertNull(committedC.state.pending)
    }

    @Test
    fun `A remains committed while Off applies then Off commits`() {
        val initial = SubtitleTransitionState.committed(serverSidecar(3))
        val applying = reduceSubtitleTransition(initial, SelectSubtitle(SubtitleIdentity.Off))

        assertEquals(serverSidecar(3), applying.state.committed.identity)
        assertEquals(SubtitleIdentity.Off, applying.state.pending?.identity)
        assertIs<StageSubtitleReplan>(applying.effects.single())

        val committed = reduceSubtitleTransition(
            applying.state,
            StagedSubtitleValidated(applying.state.pending!!.generation, candidate("off-session")),
        )
        assertEquals(SubtitleIdentity.Off, committed.state.committed.identity)
        assertNull(committed.state.pending)
    }

    @Test
    fun `matching staged failure clears pending and retains committed`() {
        val initial = SubtitleTransitionState.committed(serverSidecar(3))
        val applying = reduceSubtitleTransition(initial, SelectSubtitle(serverSidecar(4)))

        val failed = reduceSubtitleTransition(
            applying.state,
            StagedSubtitleFailed(applying.state.pending!!.generation, "Subtitle unavailable"),
        )

        assertEquals(serverSidecar(3), failed.state.committed.identity)
        assertNull(failed.state.pending)
        assertEquals(
            "Subtitle unavailable",
            assertIs<ReportSubtitleTransitionFailure>(failed.effects.single()).message,
        )
    }

    @Test
    fun `superseded failure cannot clear the latest pending selection`() {
        val initial = SubtitleTransitionState.committed(serverSidecar(1))
        val applyingB = reduceSubtitleTransition(initial, SelectSubtitle(serverSidecar(2)))
        val applyingC = reduceSubtitleTransition(applyingB.state, SelectSubtitle(serverSidecar(3)))

        val staleFailure = reduceSubtitleTransition(
            applyingC.state,
            StagedSubtitleFailed(applyingB.state.pending!!.generation, "B failed"),
        )

        assertEquals(applyingC.state, staleFailure.state)
        assertTrue(staleFailure.effects.isEmpty())
    }

    @Test
    fun `content reset invalidates pending generations and stale candidates`() {
        val applying = reduceSubtitleTransition(
            SubtitleTransitionState.committed(serverSidecar(3)),
            SelectSubtitle(serverSidecar(4)),
        )
        val oldGeneration = applying.state.pending!!.generation

        val reset = reduceSubtitleTransition(applying.state, SubtitleContentReset(SubtitleIdentity.Off))
        assertEquals(SubtitleIdentity.Off, reset.state.committed.identity)
        assertNull(reset.state.pending)
        assertTrue(reset.state.nextGeneration > applying.state.nextGeneration)

        val stale = reduceSubtitleTransition(
            reset.state,
            StagedSubtitleValidated(oldGeneration, candidate("old-content")),
        )
        assertEquals(reset.state, stale.state)
        assertEquals(candidate("old-content"), assertIs<DiscardStagedSubtitleReplan>(stale.effects.single()).candidate)
    }

    @Test
    fun `local Media3 selection commits synchronously`() {
        val local = SubtitleIdentity.LocalMedia3(media(trackId = "media3-text-7"))

        val selected = reduceSubtitleTransition(
            SubtitleTransitionState.committed(serverSidecar(3)),
            SelectSubtitle(local),
        )

        assertEquals(local, selected.state.committed.identity)
        assertNull(selected.state.pending)
        assertEquals(
            listOf(ApplyLocalSubtitleSelection::class, PersistSubtitleSelection::class),
            selected.effects.map { it::class },
        )
    }

    @Test
    fun `burn-in remains pending until its matching staged validation`() {
        val burnIn = SubtitleIdentity.ServerBurnIn(serverIndex = 8)
        val applying = reduceSubtitleTransition(
            SubtitleTransitionState.committed(serverSidecar(3)),
            SelectSubtitle(burnIn),
        )

        assertEquals(serverSidecar(3), applying.state.committed.identity)
        assertEquals(burnIn, applying.state.pending?.identity)
        assertEquals(burnIn, assertIs<StageSubtitleReplan>(applying.effects.single()).pending.identity)

        val committed = reduceSubtitleTransition(
            applying.state,
            StagedSubtitleValidated(applying.state.pending!!.generation, candidate("burn-in-session")),
        )
        assertEquals(burnIn, committed.state.committed.identity)
    }

    @Test
    fun `downloaded identity keeps its stable id and media fallback when committed locally`() {
        val fallback = media(
            trackId = "downloaded-42",
            label = "English (OpenSubtitles)",
            language = "en",
            codecFamily = "text-vtt",
            hearingImpaired = true,
        )
        val downloaded = SubtitleIdentity.Downloaded(downloadId = 42, media = fallback)

        val selected = reduceSubtitleTransition(
            SubtitleTransitionState.committed(SubtitleIdentity.Off),
            SelectSubtitle(downloaded),
        )

        assertEquals(downloaded, selected.state.committed.identity)
        assertEquals(
            downloaded,
            assertIs<PersistSubtitleSelection>(selected.effects.last()).committed.identity,
        )
    }

    @Test
    fun `audio then downloaded remains one pending server and mount transaction`() {
        val downloaded = SubtitleIdentity.Downloaded(
            downloadId = 42,
            media = media(trackId = "silo-downloaded-subtitle:42"),
        )
        val audio = reduceSubtitleTransition(
            SubtitleTransitionState.committed(serverSidecar(3), audioTrackIndex = 2),
            UpdateAudioPreference(audioTrackIndex = 7),
        )

        val selected = reduceSubtitleTransition(audio.state, SelectSubtitle(downloaded))

        assertEquals(serverSidecar(3), selected.state.committed.identity)
        assertEquals(downloaded, selected.state.pending?.identity)
        assertEquals(7, selected.state.pending?.audioTrackIndex)
        assertTrue(selected.state.pending?.audioPreferenceSpecified == true)
        assertIs<StageSubtitleReplan>(selected.effects.single())
    }

    @Test
    fun `audio then local Media3 remains one pending server and mount transaction`() {
        val local = SubtitleIdentity.LocalMedia3(media(trackId = "decoder-text-7"))
        val audio = reduceSubtitleTransition(
            SubtitleTransitionState.committed(serverSidecar(3), audioTrackIndex = 2),
            UpdateAudioPreference(audioTrackIndex = 7),
        )

        val selected = reduceSubtitleTransition(audio.state, SelectSubtitle(local))

        assertEquals(serverSidecar(3), selected.state.committed.identity)
        assertEquals(local, selected.state.pending?.identity)
        assertEquals(7, selected.state.pending?.audioTrackIndex)
        assertTrue(selected.state.pending?.audioPreferenceSpecified == true)
        assertIs<StageSubtitleReplan>(selected.effects.single())
    }

    @Test
    fun `audio and quality preferences merge independently in either order`() {
        val subtitle = serverSidecar(4)

        fun merged(events: List<SubtitleTransitionEvent>): PendingSubtitle {
            var state = SubtitleTransitionState.committed(serverSidecar(3))
            events.forEach { event -> state = reduceSubtitleTransition(state, event).state }
            return state.pending!!
        }

        val audioThenQuality = merged(
            listOf(
                SelectSubtitle(subtitle),
                UpdateAudioPreference(audioTrackIndex = 2),
                UpdateQualityPreference(qualityPreference = "1080p"),
            ),
        )
        val qualityThenAudio = merged(
            listOf(
                SelectSubtitle(subtitle),
                UpdateQualityPreference(qualityPreference = "1080p"),
                UpdateAudioPreference(audioTrackIndex = 2),
            ),
        )

        assertEquals(subtitle, audioThenQuality.identity)
        assertEquals(2, audioThenQuality.audioTrackIndex)
        assertEquals("1080p", audioThenQuality.qualityPreference)
        assertEquals(
            audioThenQuality.copy(generation = qualityThenAudio.generation),
            qualityThenAudio,
        )
    }

    @Test
    fun `explicit quality clear survives a later audio update`() {
        val initial = stateWithPreferences()
        val qualityCleared = reduceSubtitleTransition(
            initial,
            UpdateQualityPreference(qualityPreference = null),
        )

        val audioUpdated = reduceSubtitleTransition(
            qualityCleared.state,
            UpdateAudioPreference(audioTrackIndex = 2),
        )

        assertEquals(2, audioUpdated.state.pending?.audioTrackIndex)
        assertNull(audioUpdated.state.pending?.qualityPreference)
        assertTrue(audioUpdated.state.pending?.qualityPreferenceSpecified == true)
    }

    @Test
    fun `explicit quality clear survives a later subtitle selection`() {
        val initial = stateWithPreferences()
        val qualityCleared = reduceSubtitleTransition(
            initial,
            UpdateQualityPreference(qualityPreference = null),
        )

        val subtitleSelected = reduceSubtitleTransition(
            qualityCleared.state,
            SelectSubtitle(serverSidecar(4)),
        )

        assertEquals(serverSidecar(4), subtitleSelected.state.pending?.identity)
        assertNull(subtitleSelected.state.pending?.qualityPreference)
        assertTrue(subtitleSelected.state.pending?.qualityPreferenceSpecified == true)
    }

    @Test
    fun `explicit audio clear survives a later quality update`() {
        val initial = stateWithPreferences()
        val audioCleared = reduceSubtitleTransition(
            initial,
            UpdateAudioPreference(audioTrackIndex = null),
        )

        val qualityUpdated = reduceSubtitleTransition(
            audioCleared.state,
            UpdateQualityPreference(qualityPreference = "1080p"),
        )

        assertNull(qualityUpdated.state.pending?.audioTrackIndex)
        assertEquals("1080p", qualityUpdated.state.pending?.qualityPreference)
        assertTrue(qualityUpdated.state.pending?.audioPreferenceSpecified == true)
    }

    @Test
    fun `explicit audio clear survives a later subtitle selection`() {
        val initial = stateWithPreferences()
        val audioCleared = reduceSubtitleTransition(
            initial,
            UpdateAudioPreference(audioTrackIndex = null),
        )

        val subtitleSelected = reduceSubtitleTransition(
            audioCleared.state,
            SelectSubtitle(serverSidecar(4)),
        )

        assertEquals(serverSidecar(4), subtitleSelected.state.pending?.identity)
        assertNull(subtitleSelected.state.pending?.audioTrackIndex)
        assertTrue(subtitleSelected.state.pending?.audioPreferenceSpecified == true)
    }

    private fun serverSidecar(index: Int): SubtitleIdentity =
        SubtitleIdentity.ServerSidecar(serverIndex = index)

    private fun candidate(id: String): StagedSubtitleCandidate =
        StagedSubtitleCandidate(id)

    private fun stateWithPreferences(): SubtitleTransitionState =
        SubtitleTransitionState.committed(
            identity = serverSidecar(3),
            audioTrackIndex = 7,
            qualityPreference = "4k",
        )

    private fun media(
        trackId: String? = null,
        label: String? = null,
        language: String? = null,
        codecFamily: String? = null,
        forced: Boolean? = null,
        hearingImpaired: Boolean? = null,
    ): SubtitleMediaIdentity = SubtitleMediaIdentity(
        trackId = trackId,
        label = label,
        language = language,
        codecFamily = codecFamily,
        forced = forced,
        hearingImpaired = hearingImpaired,
    )
}
