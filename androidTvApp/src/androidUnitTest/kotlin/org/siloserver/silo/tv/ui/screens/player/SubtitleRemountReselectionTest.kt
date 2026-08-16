package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.player.subtitleArtifactTrackId
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleRemountReselectionTest {
    @Test
    fun `ViewModel does not settle the first nonempty remount snapshot`() {
        val tracker = TvSubtitleSnapshotSettlementTracker()
        val first = listOf(track(index = 1, trackId = "silo-subtitle:4"))

        assertFalse(tracker.observe(first))
        assertTrue(tracker.observe(first))
    }

    @Test
    fun `changed remount snapshot must stabilize again before it is terminal`() {
        val tracker = TvSubtitleSnapshotSettlementTracker()
        val first = listOf(track(index = 1, trackId = "silo-subtitle:4"))
        val changed = listOf(track(index = 2, trackId = "silo-subtitle:4"))

        assertFalse(tracker.observe(first))
        assertFalse(tracker.observe(changed))
        assertTrue(tracker.observe(changed))
        tracker.reset()
        assertFalse(tracker.observe(changed))
    }

    private val viewModelSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt",
    ).readText()

    @Test
    fun `same-label forced and full external rows resolve the exact typed identity`() {
        val latch = SubtitleRemountReselection()
        val forced = track(
            index = 2,
            trackId = "forced",
            label = "English",
            forced = true,
        )
        val full = track(
            index = 3,
            trackId = "full",
            label = "English",
            forced = false,
        )
        val identity = SubtitleIdentity.LocalMedia3(
            media = media(trackId = "forced", label = "English", forced = true),
        )

        latch.arm(identity = identity, generation = 1)

        val event = assertIs<TvSubtitleRemountEvent.Select>(
            latch.consume(listOf(full, forced), snapshotKey = "ready", settled = true),
        )
        assertEquals(2, event.trackIndex)
        assertEquals(identity, event.owner.identity)
    }

    @Test
    fun `duplicate English forced and full PGS rows safely miss without an exact identity`() {
        val latch = SubtitleRemountReselection()
        val ambiguous = SubtitleIdentity.LocalMedia3(
            media = media(label = "English", codec = "pgs", forced = null),
        )
        latch.arm(identity = ambiguous, generation = 1)

        val event = latch.consume(
            subtitleTracks = listOf(
                track(index = 2, trackId = null, label = "English", codec = "pgs", forced = true),
                track(index = 3, trackId = null, label = "English", codec = "pgs", forced = false),
            ),
            snapshotKey = "ready",
            settled = true,
        )

        assertIs<TvSubtitleRemountEvent.Failed>(event)
    }

    @Test
    fun `catalog B followed by embedded C remounts only C`() {
        val latch = SubtitleRemountReselection()
        val b = SubtitleIdentity.ServerSidecar(4, media(trackId = "silo-subtitle:4"))
        val c = SubtitleIdentity.Embedded(8, media(trackId = "embedded-c"))
        latch.arm(b, generation = 1)
        latch.arm(c, generation = 2)

        val event = assertIs<TvSubtitleRemountEvent.Select>(
            latch.consume(
                subtitleTracks = listOf(
                    track(index = 4, trackId = "silo-subtitle:4"),
                    track(index = 8, trackId = "embedded-c"),
                ),
                snapshotKey = "ready",
                settled = true,
            ),
        )

        assertEquals(8, event.trackIndex)
        assertEquals(c, event.owner.identity)
        assertNull(latch.consume(listOf(track(index = 4, trackId = "silo-subtitle:4")), snapshotKey = "late-b", settled = true))
    }

    @Test
    fun `catalog B followed by local C remounts only C`() {
        val latch = SubtitleRemountReselection()
        val c = SubtitleIdentity.LocalMedia3(media(trackId = "local-c"))
        latch.arm(SubtitleIdentity.ServerSidecar(4), generation = 1)
        latch.arm(c, generation = 2)

        val event = assertIs<TvSubtitleRemountEvent.Select>(
            latch.consume(
                subtitleTracks = listOf(
                    track(index = 4, trackId = "silo-subtitle:4"),
                    track(index = 9, trackId = "local-c"),
                ),
                snapshotKey = "ready",
                settled = true,
            ),
        )

        assertEquals(9, event.trackIndex)
        assertEquals(c, event.owner.identity)
    }

    @Test
    fun `remount failure retains the committed typed identity and clears Applying`() {
        val latch = SubtitleRemountReselection()
        val identity = SubtitleIdentity.LocalMedia3(media(trackId = "missing"))
        latch.arm(identity, generation = 7)

        val event = assertIs<TvSubtitleRemountEvent.Failed>(
            latch.consume(
                subtitleTracks = listOf(track(index = 2, trackId = "other")),
                snapshotKey = "settled",
                settled = true,
            ),
        )

        assertEquals(identity, event.owner.identity)
        assertFalse(latch.hasPendingOwner)
    }

    @Test
    fun `remount cancellation cannot select a superseded identity`() {
        val latch = SubtitleRemountReselection()
        latch.arm(SubtitleIdentity.LocalMedia3(media(trackId = "old")), generation = 1)
        latch.clear()

        assertNull(
            latch.consume(
                subtitleTracks = listOf(track(index = 2, trackId = "old")),
                snapshotKey = "late",
                settled = true,
            ),
        )
    }

    @Test
    fun `stale remount callback after new intent emits no selection`() {
        val latch = SubtitleRemountReselection()
        latch.arm(SubtitleIdentity.LocalMedia3(media(trackId = "b")), generation = 1)
        latch.arm(SubtitleIdentity.LocalMedia3(media(trackId = "c")), generation = 2)

        val lateB = latch.consume(
            subtitleTracks = listOf(track(index = 2, trackId = "b")),
            snapshotKey = "late-b",
            settled = false,
        )

        assertNull(lateB)
        assertTrue(latch.hasPendingOwner)
    }

    @Test
    fun `reset while remount is pending invalidates the owner`() {
        val latch = SubtitleRemountReselection()
        latch.arm(SubtitleIdentity.LocalMedia3(media(trackId = "b")), generation = 1)

        latch.clear()

        assertFalse(latch.hasPendingOwner)
        assertNull(latch.consume(listOf(track(index = 2, trackId = "b")), snapshotKey = "late", settled = true))
    }

    @Test
    fun `exit while remount is pending emits no selection`() {
        val latch = SubtitleRemountReselection()
        latch.arm(SubtitleIdentity.ServerSidecar(4), generation = 1)

        latch.clear()

        assertNull(latch.consume(listOf(track(index = 4, trackId = "silo-subtitle:4")), snapshotKey = "late", settled = true))
    }

    @Test
    fun `repeated and empty remount snapshots do not consume the meaningful-snapshot bound`() {
        val latch = SubtitleRemountReselection(maxMeaningfulSnapshots = 2)
        val identity = SubtitleIdentity.LocalMedia3(media(trackId = "target"))
        latch.arm(identity, generation = 1)

        repeat(5) {
            assertNull(latch.consume(emptyList(), snapshotKey = null, settled = false))
            assertNull(
                latch.consume(
                    listOf(track(index = 2, trackId = "other")),
                    snapshotKey = "same-transient",
                    settled = false,
                ),
            )
        }

        assertTrue(latch.hasPendingOwner)
        val event = assertIs<TvSubtitleRemountEvent.Select>(
            latch.consume(listOf(track(index = 8, trackId = "target")), snapshotKey = "ready", settled = true),
        )
        assertEquals(8, event.trackIndex)
    }

    @Test
    fun `merged sidecar carrying the Media3 source prefix still mounts`() {
        // Media3 reports a merged sidecar's Format.id with the MergingMediaSource
        // child index: the id authored as "silo-subtitle:0" comes back as
        // "1:silo-subtitle:0", alongside primary-stream tracks like "0:3".
        // Exact equality never matched, so the mount timed out and the whole
        // subtitle transaction rolled back to Off.
        val latch = SubtitleRemountReselection()
        latch.arm(SubtitleIdentity.ServerSidecar(serverIndex = 0), generation = 1)

        val event = assertIs<TvSubtitleRemountEvent.Select>(
            latch.consume(
                listOf(
                    track(index = 0, trackId = "0:3"),
                    track(index = 19, trackId = "0:22"),
                    track(index = 20, trackId = "1:" + subtitleArtifactTrackId(0)),
                ),
                snapshotKey = "merged",
                settled = true,
            ),
        )
        assertEquals(20, event.trackIndex)
    }

    @Test
    fun `settled empty snapshot never fails the mount before tracks publish`() {
        // A replanned server stream reports READY before it publishes its text
        // tracks. Failing here cleared the pending owner, so the tracks arrived
        // with nobody to match them and the transaction rolled back to Off --
        // subtitles silently refused to turn on.
        val latch = SubtitleRemountReselection()
        val identity = SubtitleIdentity.ServerSidecar(serverIndex = 3)
        latch.arm(identity, generation = 1)

        assertNull(latch.consume(emptyList(), snapshotKey = "ready-no-tracks", settled = true))
        assertTrue(latch.hasPendingOwner)

        val event = assertIs<TvSubtitleRemountEvent.Select>(
            latch.consume(
                listOf(track(index = 4, trackId = subtitleArtifactTrackId(3))),
                snapshotKey = "tracks-arrived",
                settled = true,
            ),
        )
        assertEquals(4, event.trackIndex)
    }

    @Test
    fun `settled unique remount miss rolls back without selecting another row`() {
        val latch = SubtitleRemountReselection()
        latch.arm(
            SubtitleIdentity.LocalMedia3(media(trackId = "target", language = "en")),
            generation = 1,
        )

        val event = assertIs<TvSubtitleRemountEvent.Failed>(
            latch.consume(
                listOf(track(index = 2, trackId = "other", language = "en")),
                snapshotKey = "settled",
                settled = true,
            ),
        )

        assertEquals(TvSubtitleRemountFailure.Missing, event.reason)
    }

    @Test
    fun `settled ambiguous remount snapshot rolls back without label fallback`() {
        val latch = SubtitleRemountReselection()
        latch.arm(
            SubtitleIdentity.LocalMedia3(media(label = "English", language = "en")),
            generation = 1,
        )

        val event = assertIs<TvSubtitleRemountEvent.Failed>(
            latch.consume(
                listOf(
                    track(index = 2, trackId = null, label = "English", language = "en"),
                    track(index = 3, trackId = null, label = "English", language = "en"),
                ),
                snapshotKey = "settled",
                settled = true,
            ),
        )

        assertEquals(TvSubtitleRemountFailure.Ambiguous, event.reason)
    }

    @Test
    fun `downloaded remount resolves the exact unique downloadId`() {
        val latch = SubtitleRemountReselection()
        val identity = SubtitleIdentity.Downloaded(91, media(label = "English"))
        latch.arm(identity, generation = 1)

        val event = assertIs<TvSubtitleRemountEvent.Select>(
            latch.consume(
                listOf(
                    track(index = 2, trackId = "silo-downloaded-subtitle:90", label = "English"),
                    track(index = 3, trackId = "silo-downloaded-subtitle:91", label = "English"),
                ),
                snapshotKey = "ready",
                settled = true,
            ),
        )

        assertEquals(3, event.trackIndex)
    }

    @Test
    fun `server sidecar remount resolves the exact artifact trackId`() {
        val latch = SubtitleRemountReselection()
        latch.arm(SubtitleIdentity.ServerSidecar(7), generation = 1)

        val event = assertIs<TvSubtitleRemountEvent.Select>(
            latch.consume(
                listOf(
                    track(index = 2, trackId = "silo-subtitle:8"),
                    track(index = 3, trackId = "silo-subtitle:7"),
                ),
                snapshotKey = "ready",
                settled = true,
            ),
        )

        assertEquals(3, event.trackIndex)
    }

    @Test
    fun `burn-in commit completes without a mounted Media3 subtitle`() {
        val latch = SubtitleRemountReselection()

        assertFalse(latch.requiresRemount(SubtitleIdentity.ServerBurnIn(8)))
        assertFalse(latch.hasPendingOwner)
    }

    @Test
    fun `Off emits exactly one owned disable request`() {
        val latch = SubtitleRemountReselection()
        latch.arm(SubtitleIdentity.Off, generation = 5)
        // A track is actually selected, so there is something to turn off.
        val event = assertIs<TvSubtitleRemountEvent.Select>(
            latch.consume(emptyList(), snapshotKey = null, settled = false),
        )

        assertEquals(-1, event.trackIndex)
        assertEquals(5, event.owner.generation)
        assertNull(latch.consume(emptyList(), snapshotKey = null, settled = false))
    }

    @Test
    fun everyTransportRemountMustDeclareTheSubtitleSelectionToRestore() {
        assertFalse(
            viewModelSource.contains("nextTransportMountNonce()"),
            "Every mount must explicitly pass the stable subtitle index, or null for the first mount",
        )
    }

    @Test
    fun seekRecoveryReappliesTheSelectedSubtitleAfterItsRemount() {
        val seekRecoveryBlock = viewModelSource
            .substringAfter("private suspend fun adoptSeekRecoveryDecision(")
            .substringBefore("private fun isCurrentSeekRecovery(")

        assertTrue(seekRecoveryBlock.contains("val returnedSubtitleIndex = decision.plan.resolvedSelectedSubtitleIndex()"))
        assertTrue(seekRecoveryBlock.contains("subtitleTrackIndex = returnedSubtitleIndex ?: -1"))
        assertTrue(seekRecoveryBlock.contains("nextTypedSubtitleMountNonce(returnedSubtitleIdentity)"))
    }

    private fun media(
        trackId: String? = null,
        label: String? = "English",
        language: String? = "en",
        codec: String? = "webvtt",
        forced: Boolean? = false,
    ): SubtitleMediaIdentity = SubtitleMediaIdentity(
        trackId = trackId,
        label = label,
        language = language,
        codecFamily = codec,
        forced = forced,
        hearingImpaired = false,
    )

    private fun track(
        index: Int,
        trackId: String?,
        label: String = "English",
        language: String? = "en",
        codec: String? = "webvtt",
        forced: Boolean = false,
        selected: Boolean = false,
    ): PlayerTrackEntry = PlayerTrackEntry(
        index = index,
        trackId = trackId,
        label = label,
        displayLabel = label,
        language = language,
        codecOrMime = codec,
        isSelected = selected,
        isForced = forced,
    )
}
