package org.siloserver.silo.common.player.video

import org.siloserver.silo.model.catalog.AudioTrack
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Downloaded files described by their offline manifest: the catalog lists the
 * file's own audio tracks in file order, so selection goes by position.
 */
class AudioReconcilePositionalTest {

    /** Two English stereo AAC tracks that only a title could tell apart. */
    private val catalog = listOf(
        AudioTrack(index = 0, codec = "aac", channels = 2, language = "eng", title = "Main"),
        AudioTrack(index = 1, codec = "aac", channels = 2, language = "jpn"),
        AudioTrack(index = 2, codec = "aac", channels = 2, language = "eng", title = "Commentary"),
    )

    /** Media3 reports no labels for the MP4's tracks. */
    private val mounted = listOf(
        MountedAudioTrack(0, "eng", "audio/mp4a-latm", 2),
        MountedAudioTrack(1, "jpn", "audio/mp4a-latm", 2),
        MountedAudioTrack(2, "eng", "audio/mp4a-latm", 2),
    )

    private fun desire(ordinal: Int) = DesiredAudio(
        generation = 1L,
        catalogOrdinal = ordinal,
        explicit = true,
        fileId = 7,
    )

    private fun reconcile(
        ordinal: Int,
        selectedOrdinal: Int?,
        mountedTracks: List<MountedAudioTrack> = mounted,
        catalogTracks: List<AudioTrack> = catalog,
        positional: Boolean = true,
    ) = reconcileDesiredAudioAction(
        desired = desire(ordinal),
        activeFileId = 7,
        catalog = catalogTracks,
        mounted = mountedTracks,
        selectedOrdinal = selectedOrdinal,
        planAudioOrdinal = null,
        positionalCatalog = positional,
    )

    @Test
    fun selectsTheMountedGroupAtTheSamePosition() {
        assertEquals(AudioReconcileAction.Apply(2), reconcile(ordinal = 2, selectedOrdinal = 0))
        assertEquals(AudioReconcileAction.Confirm, reconcile(ordinal = 2, selectedOrdinal = 2))
    }

    @Test
    fun identityMatchingCannotTellTheSameLanguageTracksApart() {
        // Without the positional mode the untitled mount is ambiguous, so the
        // pick silently goes nowhere: the failure this mode exists for.
        assertEquals(
            AudioReconcileAction.None,
            reconcile(ordinal = 2, selectedOrdinal = 0, positional = false),
        )
    }

    @Test
    fun reEncodedTracksAreSelectedEvenWhenTheCatalogNamesTheSourceCodec() {
        val sourceDescribed = listOf(
            AudioTrack(index = 0, codec = "truehd", channels = 8, language = "eng"),
            AudioTrack(index = 1, codec = "ac3", channels = 6, language = "fre"),
        )
        val remux = listOf(
            MountedAudioTrack(0, "eng", "audio/mp4a-latm", 2),
            MountedAudioTrack(1, "fre", "audio/ac3", 6),
        )

        assertEquals(
            AudioReconcileAction.Apply(0),
            reconcile(ordinal = 0, selectedOrdinal = 1, mountedTracks = remux, catalogTracks = sourceDescribed),
        )
    }

    @Test
    fun aSnapshotOfADifferentSizeFallsBackToIdentityMatching() {
        val legacySingleTrack = listOf(MountedAudioTrack(0, "jpn", "audio/mp4a-latm", 2))

        // Identity matching finds the Japanese track even though the file does
        // not have the three tracks the catalog lists.
        assertEquals(
            AudioReconcileAction.Confirm,
            reconcile(ordinal = 1, selectedOrdinal = 0, mountedTracks = legacySingleTrack),
        )
    }
}
