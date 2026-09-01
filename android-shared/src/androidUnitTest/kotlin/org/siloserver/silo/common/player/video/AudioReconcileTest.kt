package org.siloserver.silo.common.player.video

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlaybackDelivery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decision half of desired-audio reconciliation.
 *
 * Every review round of this area produced a case that neither reasoning nor
 * the pure-matcher tests caught — a discarded intent, a false confirmation, a
 * cross-file ordinal — so the cases below are those failures written down.
 */
class AudioReconcileTest {

    private val english = AudioTrack(
        codec = "dts", channels = 6, language = "en", title = "English DTS 5.1", isDefault = true,
    )
    private val dutch = AudioTrack(
        codec = "aac", channels = 2, language = "nl", title = "Dutch AAC Stereo",
    )
    private val catalog = listOf(english, dutch)

    /** Mounted order is the REVERSE of catalog order, as on the device. */
    private val mounted = listOf(
        MountedAudioTrack(0, "nl", "audio/mp4a-latm", 2, "Dutch AAC Stereo"),
        MountedAudioTrack(1, "en", "audio/vnd.dts", 6, "English DTS 5.1"),
    )

    private fun desire(ordinal: Int, fileId: Int? = 1, confirmed: Boolean = false) =
        DesiredAudio(
            generation = 1L,
            catalogOrdinal = ordinal,
            explicit = true,
            fileId = fileId,
            confirmed = confirmed,
        )

    private fun reconcile(
        desired: DesiredAudio?,
        mountedTracks: List<MountedAudioTrack> = mounted,
        selectedOrdinal: Int? = 1,
        activeFileId: Int? = 1,
        planAudioOrdinal: Int? = null,
        requiresMountedIdentity: Boolean = false,
    ) = reconcileDesiredAudioAction(
        desired = desired,
        activeFileId = activeFileId,
        catalog = catalog,
        mounted = mountedTracks,
        selectedOrdinal = selectedOrdinal,
        planAudioOrdinal = planAudioOrdinal,
        requiresMountedIdentity = requiresMountedIdentity,
    )

    @Test
    fun wantedTrackPresentButUnselectedIsApplied() {
        // Dutch is catalog 1 and mounted 0; a positional answer would say 1.
        assertEquals(AudioReconcileAction.Apply(0), reconcile(desire(1)))
    }

    @Test
    fun wantedTrackAlreadySelectedConfirms() {
        assertEquals(AudioReconcileAction.Confirm, reconcile(desire(1), selectedOrdinal = 0))
        assertEquals(AudioReconcileAction.Confirm, reconcile(desire(0), selectedOrdinal = 1))
    }

    /**
     * The bug that made a launch pick silently fail: an empty or partial first
     * callback must not be treated as evidence, and must not consume the intent.
     */
    @Test
    fun emptySnapshotDecidesNothing() {
        assertEquals(AudioReconcileAction.None, reconcile(desire(1), mountedTracks = emptyList()))
    }

    @Test
    fun noIntentDecidesNothing() {
        assertEquals(AudioReconcileAction.None, reconcile(null))
    }

    /** Ordinals are per-file; an intent from the outgoing version is abandoned. */
    @Test
    fun intentFromAnotherFileIsDropped() {
        assertEquals(
            AudioReconcileAction.DropForeignFile,
            reconcile(desire(1, fileId = 7), activeFileId = 9),
        )
    }

    @Test
    fun intentWithoutAFileIsNotTreatedAsForeign() {
        assertEquals(AudioReconcileAction.Apply(0), reconcile(desire(1, fileId = null)))
    }

    /**
     * A transcode delivers a recoded representation that cannot identity-match
     * its source. The server saying it delivered the row is what satisfies it —
     * otherwise the intent retries an impossible match forever.
     */
    @Test
    fun absentTrackIsSatisfiedOnlyWhenThePlanNamesIt() {
        val transcoded = listOf(MountedAudioTrack(0, null, "audio/mp4a-latm", 2, null))

        assertEquals(
            AudioReconcileAction.Confirm,
            reconcile(desire(0), mountedTracks = transcoded, selectedOrdinal = 0, planAudioOrdinal = 0),
        )
        assertEquals(
            AudioReconcileAction.None,
            reconcile(desire(0), mountedTracks = transcoded, selectedOrdinal = 0, planAudioOrdinal = 1),
        )
        assertEquals(
            AudioReconcileAction.None,
            reconcile(desire(0), mountedTracks = transcoded, selectedOrdinal = 0, planAudioOrdinal = null),
        )
    }

    @Test
    fun originalFileRequiresMountedIdentityBeforeConfirmingThePlanSelection() {
        val transcodedLookingSnapshot = listOf(
            MountedAudioTrack(0, null, "audio/mp4a-latm", 2, null),
        )
        val desired = desire(0)
        val action = reconcile(
            desired = desired,
            mountedTracks = transcodedLookingSnapshot,
            selectedOrdinal = 0,
            planAudioOrdinal = 0,
            requiresMountedIdentity = true,
        )

        assertEquals(AudioReconcileAction.None, action)
        assertTrue(
            shouldVerifyOriginalAudioSelection(
                desired = desired,
                delivery = PlaybackDelivery.ORIGINAL_HTTP,
                planAudioOrdinal = 0,
                mounted = transcodedLookingSnapshot,
                action = action,
            ),
        )
        assertFalse(
            shouldVerifyOriginalAudioSelection(
                desired = desired,
                delivery = PlaybackDelivery.SERVER_REMUX_HLS,
                planAudioOrdinal = 0,
                mounted = transcodedLookingSnapshot,
                action = action,
            ),
        )
    }

    /**
     * Media3 normalizes every mounted language to ISO 639-1 while the catalog
     * carries ffprobe's 639-2 code. A byte-identical original mount must not
     * look like it is missing its own track just because the alias table did
     * not know the language.
     */
    @Test
    fun originalFileConfirmsAnIso6392CatalogRowAgainstMedia3sIso6391Mount() {
        val catalog = listOf(
            AudioTrack(codec = "aac", channels = 2, language = "eng", title = "English"),
            AudioTrack(codec = "eac3", channels = 6, language = "ita", title = "Italiano"),
        )
        val mountedTracks = listOf(
            MountedAudioTrack(0, "en", "audio/mp4a-latm", 2, "English"),
            MountedAudioTrack(1, "it", "audio/eac3", 6, "Italiano"),
        )
        val action = reconcileDesiredAudioAction(
            desired = desire(1),
            activeFileId = 1,
            catalog = catalog,
            mounted = mountedTracks,
            selectedOrdinal = 1,
            planAudioOrdinal = 1,
            requiresMountedIdentity = true,
        )
        assertEquals(AudioReconcileAction.Confirm, action)
    }

    /** Untitled main mix and commentary: identity ties, but an original mount is positional. */
    @Test
    fun originalFileFallsBackToPositionWhenIdentityTiesOnAFullInventory() {
        val catalog = listOf(
            AudioTrack(codec = "aac", channels = 2, language = "eng"),
            AudioTrack(codec = "aac", channels = 2, language = "eng"),
        )
        val mountedTracks = listOf(
            MountedAudioTrack(0, "en", "audio/mp4a-latm", 2, null),
            MountedAudioTrack(1, "en", "audio/mp4a-latm", 2, null),
        )
        val confirmed = reconcileDesiredAudioAction(
            desired = desire(1),
            activeFileId = 1,
            catalog = catalog,
            mounted = mountedTracks,
            selectedOrdinal = 1,
            planAudioOrdinal = 1,
            requiresMountedIdentity = true,
        )
        assertEquals(AudioReconcileAction.Confirm, confirmed)

        val apply = reconcileDesiredAudioAction(
            desired = desire(1),
            activeFileId = 1,
            catalog = catalog,
            mounted = mountedTracks,
            selectedOrdinal = 0,
            planAudioOrdinal = 1,
            requiresMountedIdentity = true,
        )
        assertEquals(AudioReconcileAction.Apply(1), apply)
    }

    /** A JOC mount belongs to the E-AC-3 family the catalog names. */
    @Test
    fun originalFileMatchesJocMountAgainstAnEac3CatalogRow() {
        val catalog = listOf(
            AudioTrack(codec = "aac", channels = 2, language = "eng", title = "Stereo"),
            AudioTrack(codec = "eac3", channels = 8, language = "eng", title = "Atmos"),
        )
        val mountedTracks = listOf(
            MountedAudioTrack(0, "en", "audio/mp4a-latm", 2, "Stereo"),
            MountedAudioTrack(1, "en", "audio/eac3-joc", 8, "Atmos"),
        )
        val action = reconcileDesiredAudioAction(
            desired = desire(1),
            activeFileId = 1,
            catalog = catalog,
            mounted = mountedTracks,
            selectedOrdinal = 1,
            planAudioOrdinal = 1,
            requiresMountedIdentity = true,
        )
        assertEquals(AudioReconcileAction.Confirm, action)
    }

    /** Position is not evidence when the inventories differ or a stated field disagrees. */
    @Test
    fun positionalFallbackRefusesPartialOrContradictoryMounts() {
        val catalog = listOf(
            AudioTrack(codec = "aac", channels = 2, language = "eng"),
            AudioTrack(codec = "dts", channels = 6, language = "ita"),
        )
        val partial = listOf(MountedAudioTrack(0, "en", "audio/mp4a-latm", 2, null))
        assertEquals(
            AudioReconcileAction.None,
            reconcileDesiredAudioAction(
                desired = desire(1), activeFileId = 1, catalog = catalog, mounted = partial,
                selectedOrdinal = 0, planAudioOrdinal = 1, requiresMountedIdentity = true,
            ),
        )
        val contradictory = listOf(
            MountedAudioTrack(0, "en", "audio/mp4a-latm", 2, null),
            MountedAudioTrack(1, "en", "audio/mp4a-latm", 2, null),
        )
        assertEquals(
            AudioReconcileAction.None,
            reconcileDesiredAudioAction(
                desired = desire(1), activeFileId = 1, catalog = catalog, mounted = contradictory,
                selectedOrdinal = 1, planAudioOrdinal = 1, requiresMountedIdentity = true,
            ),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun verificationDeadlineDoesNotSlideOrRearmAfterExpiry() = runTest {
        val expired = mutableListOf<Long>()
        val watchdog = AudioSelectionWatchdog(
            scope = this,
            timeoutMs = 1_000,
            onExpired = expired::add,
        )

        watchdog.arm(7)
        advanceTimeBy(500)
        watchdog.arm(7)
        advanceTimeBy(499)
        runCurrent()
        assertEquals(emptyList(), expired)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(7L), expired)

        watchdog.arm(7)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(7L), expired)
        watchdog.reset()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun resolvedVerificationNeverExpires() = runTest {
        val expired = mutableListOf<Long>()
        val watchdog = AudioSelectionWatchdog(
            scope = this,
            timeoutMs = 1_000,
            onExpired = expired::add,
        )

        watchdog.arm(9)
        advanceTimeBy(500)
        watchdog.resolve(9)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(emptyList(), expired)
        watchdog.reset()
    }

    /**
     * Main mix and commentary share language and codec. Resolving against a
     * one-element list let them confirm each other, because the matcher stops
     * as soon as one candidate remains.
     */
    @Test
    fun commentaryDoesNotConfirmTheMainMix() {
        val withCommentary = listOf(
            AudioTrack(codec = "aac", channels = 2, language = "en", title = "Main", isDefault = true),
            AudioTrack(codec = "aac", channels = 2, language = "en", title = "Director Commentary"),
        )
        val mountedBoth = listOf(
            MountedAudioTrack(0, "en", "audio/mp4a-latm", 2, "Director Commentary"),
            MountedAudioTrack(1, "en", "audio/mp4a-latm", 2, "Main"),
        )

        // Wanting Main while Commentary is selected must not confirm.
        val action = reconcileDesiredAudioAction(
            desired = DesiredAudio(1L, catalogOrdinal = 0, explicit = true, fileId = 1),
            activeFileId = 1,
            catalog = withCommentary,
            mounted = mountedBoth,
            selectedOrdinal = 0,
            planAudioOrdinal = null,
        )
        assertEquals(AudioReconcileAction.Apply(1), action)
    }

    /**
     * A remount can reorder the groups. The same intent must resolve to the new
     * ordinal, and the ordinal that used to be right must not confirm.
     */
    @Test
    fun aReorderMovesTheTargetAndInvalidatesTheOldOrdinal() {
        val reordered = listOf(
            MountedAudioTrack(0, "en", "audio/vnd.dts", 6, "English DTS 5.1"),
            MountedAudioTrack(1, "nl", "audio/mp4a-latm", 2, "Dutch AAC Stereo"),
        )
        // Dutch was mounted 0 before the remount, is mounted 1 after.
        assertEquals(
            AudioReconcileAction.Apply(1),
            reconcile(desire(1), mountedTracks = reordered, selectedOrdinal = 0),
        )
    }

    /** A confirmed choice is re-applied after a remount, not assumed to hold. */
    @Test
    fun aConfirmedChoiceIsReappliedWhenThePlayerIsNoLongerOnIt() {
        assertEquals(
            AudioReconcileAction.Apply(0),
            reconcile(desire(1, confirmed = true), selectedOrdinal = 1),
        )
    }

    @Test
    fun anOrdinalOutsideTheCatalogDecidesNothing() {
        assertEquals(AudioReconcileAction.None, reconcile(desire(9)))
    }
}
