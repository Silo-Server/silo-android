package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.*
import org.siloserver.silo.playback.playbackSubtitleIdentity
import kotlin.test.*

class HlsSubtitleRenditionTest {
    private val selectedRow = PlaybackSubtitleInventoryItemV3(
        trackId = "file:42:subtitle:0", combinedIndex = 0, source = "embedded",
        codec = "subrip", language = "eng", label = "English", delivery = "sidecar",
        url = "/stream/session/subtitles/0.vtt?file_id=42&embedded_stream_index=3",
    )
    private val otherRow = selectedRow.copy(
        trackId = "file:42:subtitle:1", combinedIndex = 1, language = "fra", label = "French",
        url = "/stream/session/subtitles/1.vtt",
    )
    private val plan = PlaybackPlanV3(
        planId = "rendition", planAttemptKey = "v3:rendition", sessionId = "session",
        delivery = PlaybackDelivery.SERVER_TRANSCODE_HLS,
        stream = PlaybackStreamV3(url = "/playback/transcode/session/master.m3u8", protocol = PlaybackStreamProtocol.HLS),
        selectedTracks = SelectedPlaybackTracksV3(subtitle = PlaybackTrackIdentityV3(selectedRow.trackId, 0)),
        subtitle = PlaybackSubtitleDecisionV3(
            mode = PlaybackSubtitleModeV3.RENDER, trackId = selectedRow.trackId,
            hlsRendition = PlaybackSubtitleHlsRenditionV3("subs", "Subtitles", "vtt", "text/vtt"),
            inventory = listOf(selectedRow, otherRow),
        ),
        decisionReason = "rendition",
    )
    private val rendition = SubtitleHlsRendition("subs", "Subtitles")

    private fun track(index: Int, id: String?, label: String? = null, language: String? = null) =
        MountedSubtitleTrack(index, id, label, language, "text/vtt", false, false)

    @Test fun selectedRowCarriesTheRenditionAndNoUrlToMount() {
        val session = plan.toSessionResponse("session", "profile", 42)
        val rows = session.subtitleUrls!!
        val selected = rows.single { it.index == 0 }
        assertEquals(rendition, selected.hlsRendition)
        assertEquals("", selected.url)
        val other = rows.single { it.index == 1 }
        assertNull(other.hlsRendition)
        assertEquals(otherRow.url, other.url)

        val identity = assertIs<SubtitleIdentity.Embedded>(playbackSubtitleIdentity(selected))
        assertEquals(rendition, identity.hlsRendition)
        assertTrue(subtitlesForVideoMediaMount(rows, session.playbackPlan, identity).isEmpty())
        assertTrue(subtitlesForVideoMediaMount(rows, session.playbackPlan, identity, preferMuxedTracks = true).isEmpty())
        // Even a stale sidecar identity for the selected ordinal mounts nothing.
        assertTrue(subtitlesForVideoMediaMount(rows, session.playbackPlan, SubtitleIdentity.ServerSidecar(0)).isEmpty())
    }

    @Test fun copyRemuxRenditionRowMountsNoSidecar() {
        val session = plan.copy(
            delivery = PlaybackDelivery.SERVER_REMUX_HLS,
            stream = plan.stream.copy(url = "/playback/remux/session/master.m3u8"),
        ).toSessionResponse("session", "profile", 42)
        val rows = session.subtitleUrls!!
        val selected = rows.single { it.index == 0 }
        assertEquals(rendition, selected.hlsRendition)
        assertEquals("", selected.url)
        assertEquals(otherRow.url, rows.single { it.index == 1 }.url)
        val identity = assertIs<SubtitleIdentity.Embedded>(playbackSubtitleIdentity(selected))
        assertTrue(subtitlesForVideoMediaMount(rows, session.playbackPlan, identity).isEmpty())
        assertTrue(subtitlesForVideoMediaMount(rows, session.playbackPlan, SubtitleIdentity.ServerSidecar(0)).isEmpty())
        // A progressive remux has no playlist to carry it, so the row keeps its sidecar.
        val progressive = plan.copy(delivery = PlaybackDelivery.SERVER_REMUX_PROGRESSIVE)
            .toSessionResponse("session", "profile", 42).subtitleUrls!!.single { it.index == 0 }
        assertNull(progressive.hlsRendition)
        assertEquals(selectedRow.url, progressive.url)
    }

    @Test fun plansWithoutARenditionKeepTheirSidecar() {
        val sidecarPlan = plan.copy(
            subtitle = plan.subtitle.copy(
                hlsRendition = null,
                artifact = PlaybackSubtitleArtifactV3(selectedRow.url!!, "text/vtt", "vtt"),
            ),
        )
        val session = sidecarPlan.toSessionResponse("session", "profile", 42)
        val selected = session.subtitleUrls!!.single { it.index == 0 }
        assertNull(selected.hlsRendition)
        assertEquals(selectedRow.url, selected.url)
        val identity = assertIs<SubtitleIdentity.ServerSidecar>(playbackSubtitleIdentity(selected))
        assertEquals(listOf(selectedRow.url), subtitlesForVideoMediaMount(session.subtitleUrls!!, session.playbackPlan, identity).map { it.url })

        val off = plan.copy(
            selectedTracks = SelectedPlaybackTracksV3(),
            subtitle = plan.subtitle.copy(mode = PlaybackSubtitleModeV3.OFF, trackId = null, hlsRendition = null),
        ).toSessionResponse("session", "profile", 42)
        assertTrue(off.subtitleUrls!!.all { it.hlsRendition == null && it.url.isNotBlank() })
    }

    @Test fun resolvesTheManifestRenditionByItsMedia3Id() {
        val identity = SubtitleIdentity.Embedded(0, SubtitleMediaIdentity(trackId = selectedRow.trackId, language = "eng"), hlsRendition = rendition)
        val cea608 = track(0, "0:cc:1", language = "eng")
        assertEquals(1, resolveMountedSubtitle(identity, listOf(cea608, track(1, "subs:Subtitles", "Subtitles")))?.track?.index)
        // A merged source prefixes the child index.
        assertEquals(2, resolveMountedSubtitle(identity, listOf(cea608, track(1, "1:silo-subtitle:1", "French"), track(2, "0:subs:Subtitles", "Subtitles")))?.track?.index)
        // Language alone never identifies the rendition.
        assertNull(resolveMountedSubtitle(identity, listOf(cea608, track(1, "1:silo-subtitle:0", "English", "eng"))))
        assertNull(resolveMountedSubtitle(identity, listOf(track(0, "subs:Subtitles"), track(1, "1:subs:Subtitles"))))
    }

    @Test fun fallsBackToTheRenditionNameOnlyWhenItIsUnambiguous() {
        val identity = SubtitleIdentity.Embedded(0, SubtitleMediaIdentity(), hlsRendition = rendition)
        assertEquals(1, resolveMountedSubtitle(identity, listOf(track(0, "0:cc:1"), track(1, "subtitle:0", "Subtitles")))?.track?.index)
        assertNull(resolveMountedSubtitle(identity, listOf(track(0, "a", "Subtitles"), track(1, "b", "Subtitles"))))
        assertNull(resolveMountedSubtitle(identity, listOf(track(0, "silo-subtitle:0", "Subtitles"))))
    }

    @Test fun rowBridgeUsesTheRenditionIdentity() {
        val session = plan.toSessionResponse("session", "profile", 42)
        val selected = session.subtitleUrls!!.single { it.index == 0 }
        assertEquals(3, resolveMountedSubtitle(selected, listOf(track(0, "0:cc:1", language = "eng"), track(3, "subs:Subtitles", "Subtitles")))?.track?.index)
        assertNull(resolveMountedSubtitle(selected, listOf(track(0, "silo-subtitle:0", "English", "eng"))))
    }
}
