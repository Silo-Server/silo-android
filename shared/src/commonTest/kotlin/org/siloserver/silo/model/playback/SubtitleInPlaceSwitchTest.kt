package org.siloserver.silo.model.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleInPlaceSwitchTest {
    private fun row(
        index: Int,
        codec: String = "subrip",
        containerTrackId: String? = "${index + 3}",
        streamIndex: Int? = index + 2,
        nativeSelectable: Boolean? = null,
        rendition: PlaybackSubtitleInventoryHlsRenditionV3? = null,
        source: String = "embedded",
    ) = PlaybackSubtitleInventoryItemV3(
        trackId = "file:42:subtitle:$index", combinedIndex = index, source = source, codec = codec,
        label = "Track $index", delivery = "sidecar", url = "/stream/s/subtitles/$index.vtt",
        streamIndex = streamIndex, containerTrackId = containerTrackId,
        nativeSelectable = nativeSelectable, hlsRendition = rendition,
    )

    private val original = PlaybackPlanV3(
        planId = "plan-0", planAttemptKey = "v3:0", sessionId = "s1",
        delivery = PlaybackDelivery.ORIGINAL_HTTP,
        source = PlaybackSourceDescriptorV3(container = "mkv"),
        stream = PlaybackStreamV3(url = "/api/v2/stream/s1", protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE, container = "mkv"),
        selectedTracks = SelectedPlaybackTracksV3(
            audio = PlaybackTrackIdentityV3("file:42:audio:0", 0),
            subtitle = PlaybackTrackIdentityV3("file:42:subtitle:0", 0),
        ),
        subtitle = PlaybackSubtitleDecisionV3(
            mode = PlaybackSubtitleModeV3.RENDER, trackId = "file:42:subtitle:0",
            embedded = PlaybackEmbeddedSubtitleV3(streamIndex = 2, containerTrackId = "3"),
            inventory = listOf(row(0, nativeSelectable = true), row(1, nativeSelectable = true), row(2, codec = "ass")),
        ),
        decisionReason = "validated_original_playback",
    )

    /** The plan the server returns for a native switch to [index]. */
    private fun nativeSwitch(index: Int) = original.copy(
        planId = "plan-$index", planAttemptKey = "v3:$index",
        selectedTracks = original.selectedTracks.copy(subtitle = PlaybackTrackIdentityV3("file:42:subtitle:$index", index)),
        subtitle = original.subtitle.copy(
            trackId = "file:42:subtitle:$index",
            embedded = PlaybackEmbeddedSubtitleV3(streamIndex = index + 2, containerTrackId = "${index + 3}"),
        ),
    )

    private val off = original.copy(
        planId = "plan-off", planAttemptKey = "v3:off",
        selectedTracks = original.selectedTracks.copy(subtitle = null),
        subtitle = original.subtitle.copy(mode = PlaybackSubtitleModeV3.OFF, trackId = null, embedded = null),
    )

    private fun rendition(index: Int, name: String = "Track $index") =
        PlaybackSubtitleInventoryHlsRenditionV3(groupId = "subs", name = name, index = index + 1)

    private val transcode = PlaybackPlanV3(
        planId = "hls-0", planAttemptKey = "v3:hls-0", sessionId = "s1",
        delivery = PlaybackDelivery.SERVER_TRANSCODE_HLS,
        stream = PlaybackStreamV3(url = "/playback/transcode/s1/master.m3u8", protocol = PlaybackStreamProtocol.HLS),
        selectedTracks = SelectedPlaybackTracksV3(subtitle = PlaybackTrackIdentityV3("file:42:subtitle:0", 0)),
        effectiveRecipe = PlaybackEffectiveRecipeV3(videoCodec = "h264", height = 720),
        subtitle = PlaybackSubtitleDecisionV3(
            mode = PlaybackSubtitleModeV3.RENDER, trackId = "file:42:subtitle:0",
            hlsRendition = PlaybackSubtitleHlsRenditionV3("subs", "Track 0", "vtt", "text/vtt", index = 1),
            inventory = listOf(row(0, rendition = rendition(0)), row(1, rendition = rendition(1))),
        ),
        decisionReason = "quality_fixed_rung",
    )

    private fun renditionSwitch(index: Int) = transcode.copy(
        planId = "hls-$index", planAttemptKey = "v3:hls-$index",
        selectedTracks = transcode.selectedTracks.copy(subtitle = PlaybackTrackIdentityV3("file:42:subtitle:$index", index)),
        subtitle = transcode.subtitle.copy(
            trackId = "file:42:subtitle:$index",
            hlsRendition = PlaybackSubtitleHlsRenditionV3("subs", "Track $index", "vtt", "text/vtt", index = index + 1),
        ),
    )

    @Test fun nativeSwitchFollowsTheServersNativeSelectableFlag() {
        assertEquals("4", original.nativeSwitchTrackId(original.subtitle.inventory[1]))
        // A server that sends stream_index omits native_selectable exactly when it is false.
        assertNull(original.nativeSwitchTrackId(original.subtitle.inventory[2]))
        assertNull(original.nativeSwitchTrackId(row(1, nativeSelectable = false)))
    }

    @Test fun withoutTheC1FieldsTheClientEvaluatesTheRowItself() {
        fun preC1(codec: String) = original.copy(
            subtitle = original.subtitle.copy(inventory = listOf(row(0, codec = codec, streamIndex = null))),
        )
        assertEquals("3", preC1("subrip").let { it.nativeSwitchTrackId(it.subtitle.inventory.single()) })
        // ASS depends on fidelity and libass, which only the server's answer reflects.
        assertNull(preC1("ass").let { it.nativeSwitchTrackId(it.subtitle.inventory.single()) })
        assertNull(preC1("webvtt").let { it.nativeSwitchTrackId(it.subtitle.inventory.single()) })
        val noId = original.copy(subtitle = original.subtitle.copy(
            inventory = listOf(row(0, containerTrackId = null, streamIndex = null)),
        ))
        assertNull(noId.nativeSwitchTrackId(noId.subtitle.inventory.single()))
    }

    @Test fun nativeSwitchNeedsAnExactUniqueTrackOfTheOriginalFile() {
        listOf("0", "04", "-1", "x").forEach { id ->
            val candidate = row(1, containerTrackId = id, nativeSelectable = true)
            assertNull(original.nativeSwitchTrackId(candidate), id)
        }
        val duplicated = original.copy(subtitle = original.subtitle.copy(
            inventory = listOf(row(0, nativeSelectable = true), row(1, containerTrackId = "3", nativeSelectable = true)),
        ))
        assertNull(duplicated.nativeSwitchTrackId(duplicated.subtitle.inventory[1]))
        assertNull(original.nativeSwitchTrackId(row(1, nativeSelectable = true, source = "external")))
        assertNull(transcode.nativeSwitchTrackId(row(1, nativeSelectable = true)))
    }

    @Test fun renditionSwitchNeedsAWellFormedUniqueRenditionOnAnHlsPlan() {
        assertEquals(rendition(1), transcode.switchableHlsRendition(transcode.subtitle.inventory[1]))
        assertNull(transcode.switchableHlsRendition(row(1, rendition = rendition(1).copy(index = 0))))
        assertNull(transcode.switchableHlsRendition(row(1, rendition = rendition(1).copy(name = " "))))
        val duplicated = transcode.copy(subtitle = transcode.subtitle.copy(
            inventory = listOf(row(0, rendition = rendition(0, "Same")), row(1, rendition = rendition(1, "Same"))),
        ))
        assertNull(duplicated.switchableHlsRendition(duplicated.subtitle.inventory[1]))
        assertNull(transcode.copy(subtitle = transcode.subtitle.copy(mode = PlaybackSubtitleModeV3.BURN_IN, hlsRendition = null))
            .switchableHlsRendition(transcode.subtitle.inventory[1]))
        assertNull(original.switchableHlsRendition(row(1, rendition = rendition(1))))
    }

    @Test fun aNativeSwitchOrOffKeepsTheOriginalTransport() {
        assertTrue(nativeSwitch(1).appliesInPlaceOver(original, "s1", "s1"))
        assertTrue(off.appliesInPlaceOver(original, "s1", "s1"))
        assertTrue(nativeSwitch(0).appliesInPlaceOver(off, "s1", "s1"))
    }

    @Test fun anythingThatChangesTheMountedMediaNeedsARemount() {
        val switch = nativeSwitch(1)
        listOf(
            switch.copy(stream = switch.stream.copy(url = "/api/v2/stream/s1?v=2")),
            switch.copy(stream = switch.stream.copy(headers = mapOf("X-Token" to "new"))),
            switch.copy(effectiveMediaFileId = 43),
            switch.copy(effectiveRecipe = PlaybackEffectiveRecipeV3(videoCodec = "hevc")),
            switch.copy(timeline = switch.timeline.copy(timelineOffsetSeconds = 4.0)),
            switch.copy(selectedTracks = switch.selectedTracks.copy(audio = PlaybackTrackIdentityV3("file:42:audio:1", 1))),
            switch.copy(transformations = listOf(PlaybackTransformationV3(CLIENT_DV7_TO_DV81, PlaybackTransformationExecutor.CLIENT))),
            switch.copy(decisionReason = DECISION_REASON_CLIENT_DV8_BASE_LAYER),
            // The server fell back to a sidecar: the player must mount it.
            switch.copy(subtitle = switch.subtitle.copy(
                embedded = null,
                artifact = PlaybackSubtitleArtifactV3("/stream/s1/subtitles/1.vtt", "text/vtt", "vtt"),
            )),
            switch.copy(delivery = PlaybackDelivery.SERVER_REMUX_PROGRESSIVE),
        ).forEach { candidate -> assertFalse(candidate.appliesInPlaceOver(original, "s1", "s1"), candidate.toString()) }
        assertFalse(switch.appliesInPlaceOver(original, "s2", "s1"))
        assertFalse(switch.appliesInPlaceOver(original, null, null))
    }

    @Test fun audioThePlayerSwitchedOnItsOwnCountsAsPlaying() {
        val commentary = PlaybackTrackIdentityV3("file:42:audio:1", 1)
        val switch = nativeSwitch(1).copy(
            selectedTracks = nativeSwitch(1).selectedTracks.copy(audio = commentary),
            claims = PlaybackValidationClaims(audio = AudioValidationClaims(codec = "aac")),
            effectiveRecipe = PlaybackEffectiveRecipeV3(audioCodec = "aac", audioChannels = 2),
        )
        val playing = original.copy(
            claims = PlaybackValidationClaims(audio = AudioValidationClaims(codec = "eac3", passthrough = true)),
            effectiveRecipe = PlaybackEffectiveRecipeV3(audioCodec = "eac3", audioChannels = 6),
        )
        assertFalse(switch.appliesInPlaceOver(playing, "s1", "s1"))
        assertTrue(switch.appliesInPlaceOver(playing.withPlayerSwitchedAudio(commentary, switch), "s1", "s1"))
        // The plan's own audio, an answer with other audio, or an HLS stream: nothing to stand in.
        assertEquals(playing, playing.withPlayerSwitchedAudio(original.selectedTracks.audio, switch))
        assertEquals(playing, playing.withPlayerSwitchedAudio(PlaybackTrackIdentityV3("file:42:audio:2", 2), switch))
        assertEquals(playing, playing.withPlayerSwitchedAudio(null, switch))
        assertEquals(transcode, transcode.withPlayerSwitchedAudio(commentary, renditionSwitch(1)))
    }

    @Test fun aRenditionSwitchOrOffKeepsTheRunningHlsStream() {
        assertTrue(renditionSwitch(1).appliesInPlaceOver(transcode, "s1", "s1"))
        val hlsOff = transcode.copy(
            planId = "hls-off",
            selectedTracks = transcode.selectedTracks.copy(subtitle = null),
            subtitle = transcode.subtitle.copy(mode = PlaybackSubtitleModeV3.OFF, trackId = null, hlsRendition = null),
        )
        assertTrue(hlsOff.appliesInPlaceOver(transcode, "s1", "s1"))
        assertTrue(renditionSwitch(0).appliesInPlaceOver(hlsOff, "s1", "s1"))
        val remux = renditionSwitch(1).copy(delivery = PlaybackDelivery.SERVER_REMUX_HLS)
        assertTrue(remux.appliesInPlaceOver(transcode.copy(delivery = PlaybackDelivery.SERVER_REMUX_HLS), "s1", "s1"))
    }

    @Test fun anotherRenditionSetOrTheSingleRenditionStartsANewGeneration() {
        val switch = renditionSwitch(1)
        val fewer = switch.copy(subtitle = switch.subtitle.copy(inventory = listOf(row(0, rendition = rendition(0)), row(1, rendition = rendition(1, "Renamed")))))
        assertFalse(fewer.appliesInPlaceOver(transcode, "s1", "s1"))
        // The single rendition lists no inventory renditions.
        val single = transcode.copy(subtitle = transcode.subtitle.copy(
            hlsRendition = transcode.subtitle.hlsRendition?.copy(index = null),
            inventory = listOf(row(0), row(1)),
        ))
        assertFalse(switch.appliesInPlaceOver(single, "s1", "s1"))
        assertFalse(single.copy(planId = "other").appliesInPlaceOver(single, "s1", "s1"))
        // A rendition the playing stream does not list, or a burned-in subtitle.
        val unknown = switch.copy(subtitle = switch.subtitle.copy(
            hlsRendition = PlaybackSubtitleHlsRenditionV3("subs", "Elsewhere", "vtt", "text/vtt", 9),
        ))
        assertFalse(unknown.appliesInPlaceOver(transcode, "s1", "s1"))
        val burned = switch.copy(subtitle = switch.subtitle.copy(mode = PlaybackSubtitleModeV3.BURN_IN, hlsRendition = null))
        assertFalse(burned.appliesInPlaceOver(transcode, "s1", "s1"))
    }
}
