package org.siloserver.silo.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SiloControlMessageCodecTest {

    private fun roundTrip(msg: SiloControlMessage): SiloControlMessage =
        SiloControlMessageCodec.decode(SiloControlMessageCodec.encode(msg))

    private val fullState = SiloControlPlaybackState(
        contentId = "movie-1",
        sessionId = "sess-9",
        title = "Blade Runner",
        subtitle = "S1 · E2",
        isPlaying = true,
        isLoading = false,
        isBuffering = false,
        currentTime = 63.5,
        duration = 7200.0,
        audioTracks = listOf(
            SiloControlTrack(kind = "audio", trackId = 0L, title = "English 5.1", detail = "en"),
            SiloControlTrack(kind = "audio", trackId = 1L, title = "Français", detail = null),
        ),
        subtitleTracks = listOf(
            SiloControlTrack(kind = "subtitle", trackId = 0L, title = "English", detail = "en"),
        ),
        selectedAudioTrackId = 0L,
        selectedSubtitleTrackId = null,
        qualityOptions = listOf(
            SiloControlOption(id = "-1", label = "Auto", detail = null),
            SiloControlOption(id = "0:1", label = "1080p · 8.0 Mbps", detail = "1080p"),
        ),
        activeQualityId = "-1",
        isQualitySwitching = false,
        playbackSpeed = 1.25,
        videoGravity = "fit",
        hdrEnabled = true,
        supportsVideoGravity = true,
        supportsHDRToggle = true,
        subtitleSyncMs = -150,
        subtitlePosition = null,
        supportsSubtitleDelay = true,
        supportsSubtitlePosition = false,
        volume = 0.8,
        isMuted = false,
        hasNextEpisode = true,
        nextEpisodeTitle = "The Next One",
        error = null,
    )

    // ---- Round trips for every message type ------------------------------------

    @Test
    fun helloRoundTrips() {
        val msg = SiloControlMessage.Hello(
            SiloControlHello(
                role = SiloControlPeerRole.Tv,
                deviceName = "Living Room TV",
                deviceId = "abc123",
                serverId = "srv-1",
                serverName = "Home",
                supportedVersions = listOf(1),
            ),
        )
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun helloWithNullServerRoundTrips() {
        val msg = SiloControlMessage.Hello(
            SiloControlHello(
                role = SiloControlPeerRole.Phone,
                deviceName = "Pixel",
                deviceId = "id",
                serverId = null,
                serverName = null,
                supportedVersions = listOf(1, 2),
            ),
        )
        assertEquals(msg, roundTrip(msg))
        // Null optionals are omitted from the wire, not encoded as null.
        val encoded = SiloControlMessageCodec.encode(msg)
        assertTrue("serverId" !in encoded)
        assertTrue("serverName" !in encoded)
    }

    @Test
    fun launchRoundTrips() {
        val msg = SiloControlMessage.Launch(
            SiloControlLaunchRequest(
                serverId = "srv-1",
                playback = SiloControlPlaybackRequest(
                    contentId = "movie-1",
                    fileId = 42,
                    audioTrackIndex = 0,
                    subtitleTrackIndex = 1,
                    startFromBeginning = true,
                    resumePosition = 12.5,
                ),
            ),
        )
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun launchWithMinimalPlaybackRoundTrips() {
        val msg = SiloControlMessage.Launch(
            SiloControlLaunchRequest(
                serverId = "srv-1",
                playback = SiloControlPlaybackRequest(contentId = "movie-1"),
            ),
        )
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun controlRoundTrips() {
        val msg = SiloControlMessage.Control(
            SiloControlCommand(name = SiloControlCommandName.Seek, seconds = 42.5),
        )
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun stateRoundTrips() {
        val msg = SiloControlMessage.State(fullState)
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun errorRoundTrips() {
        val msg = SiloControlMessage.Error(
            SiloControlErrorMessage(code = "server_mismatch", message = "Wrong server."),
        )
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun pingPongCloseRoundTrip() {
        assertEquals(SiloControlMessage.Ping, roundTrip(SiloControlMessage.Ping))
        assertEquals(SiloControlMessage.Pong, roundTrip(SiloControlMessage.Pong))
        assertEquals(SiloControlMessage.Close, roundTrip(SiloControlMessage.Close))
    }

    // ---- Exact wire shapes -------------------------------------------------------

    @Test
    fun helloEncodesNestedPayloadExactly() {
        val msg = SiloControlMessage.Hello(
            SiloControlHello(
                role = SiloControlPeerRole.Tv,
                deviceName = "TV",
                deviceId = "d1",
                serverId = "s1",
                serverName = "Home",
                supportedVersions = listOf(1),
            ),
        )
        assertEquals(
            """{"v":1,"type":"hello","hello":{"role":"tv","deviceName":"TV","deviceId":"d1",""" +
                """"serverId":"s1","serverName":"Home","supportedVersions":[1]}}""",
            SiloControlMessageCodec.encode(msg),
        )
    }

    @Test
    fun controlEncodesSnakeCaseNameExactly() {
        val msg = SiloControlMessage.Control(
            SiloControlCommand(name = SiloControlCommandName.SelectAudioTrack, trackId = 3L),
        )
        assertEquals(
            """{"v":1,"type":"control","control":{"name":"select_audio_track","trackId":3}}""",
            SiloControlMessageCodec.encode(msg),
        )
    }

    @Test
    fun pingEncodesWithoutPayloadKey() {
        assertEquals("""{"v":1,"type":"ping"}""", SiloControlMessageCodec.encode(SiloControlMessage.Ping))
    }

    @Test
    fun allCommandNamesUseExpectedWireValues() {
        val expected = mapOf(
            SiloControlCommandName.Play to "play",
            SiloControlCommandName.Pause to "pause",
            SiloControlCommandName.PlayPause to "play_pause",
            SiloControlCommandName.Seek to "seek",
            SiloControlCommandName.Stop to "stop",
            SiloControlCommandName.SelectAudioTrack to "select_audio_track",
            SiloControlCommandName.SelectSubtitleTrack to "select_subtitle_track",
            SiloControlCommandName.SetPlaybackSpeed to "set_playback_speed",
            SiloControlCommandName.SetQuality to "set_quality",
            SiloControlCommandName.SetVideoGravity to "set_video_gravity",
            SiloControlCommandName.SetHdrEnabled to "set_hdr_enabled",
            SiloControlCommandName.SetSubtitleSyncMs to "set_subtitle_sync_ms",
            SiloControlCommandName.SetSubtitlePosition to "set_subtitle_position",
            SiloControlCommandName.SetVolume to "set_volume",
            SiloControlCommandName.SetMuted to "set_muted",
            SiloControlCommandName.PlayNext to "play_next",
        )
        assertEquals(SiloControlCommandName.entries.toSet(), expected.keys)
        for ((name, wire) in expected) {
            assertEquals(wire, name.wire)
            assertEquals(name, SiloControlCommandName.fromWire(wire))
        }
    }

    // ---- Command optional-argument coverage --------------------------------------

    @Test
    fun commandOptionalFieldsEachRoundTrip() {
        val commands = listOf(
            SiloControlCommand(name = SiloControlCommandName.Seek, seconds = 12.25),
            SiloControlCommand(name = SiloControlCommandName.SelectSubtitleTrack, trackId = 7L),
            SiloControlCommand(name = SiloControlCommandName.SetPlaybackSpeed, speed = 1.5),
            SiloControlCommand(name = SiloControlCommandName.SetVolume, volume = 0.4),
            SiloControlCommand(name = SiloControlCommandName.SetQuality, value = "0:1"),
            SiloControlCommand(name = SiloControlCommandName.SetHdrEnabled, enabled = false),
            SiloControlCommand(name = SiloControlCommandName.SetSubtitleSyncMs, milliseconds = -250),
            // No-argument command: all optionals stay null on the wire.
            SiloControlCommand(name = SiloControlCommandName.PlayNext),
        )
        for (command in commands) {
            val decoded = roundTrip(SiloControlMessage.Control(command))
            assertIs<SiloControlMessage.Control>(decoded)
            assertEquals(command, decoded.command)
        }
    }

    @Test
    fun subtitleDisableCommandOmitsTrackId() {
        val encoded = SiloControlMessageCodec.encode(
            SiloControlMessage.Control(
                SiloControlCommand(name = SiloControlCommandName.SelectSubtitleTrack, trackId = null),
            ),
        )
        assertTrue("trackId" !in encoded)
        val decoded = SiloControlMessageCodec.decode(encoded)
        assertIs<SiloControlMessage.Control>(decoded)
        assertNull(decoded.command.trackId)
    }

    // ---- Decode tolerance ----------------------------------------------------------

    @Test
    fun stateDecodesWithOptionalSubtitleFieldsAbsent() {
        // A state emitted by a peer that predates the subtitle-tuning fields.
        val json = """
            {"type":"state","v":1,"state":{
                "title":"Ready","isPlaying":false,"isLoading":false,"isBuffering":false,
                "currentTime":0,"duration":0,
                "audioTracks":[],"subtitleTracks":[],"qualityOptions":[],
                "activeQualityId":"-1","isQualitySwitching":false,
                "playbackSpeed":1,"videoGravity":"fit","hdrEnabled":true,
                "supportsVideoGravity":false,"supportsHDRToggle":false,
                "volume":1,"isMuted":false,"hasNextEpisode":false
            }}
        """.trimIndent()
        val decoded = SiloControlMessageCodec.decode(json)
        assertIs<SiloControlMessage.State>(decoded)
        val state = decoded.state
        assertNull(state.subtitleSyncMs)
        assertNull(state.subtitlePosition)
        assertNull(state.supportsSubtitleDelay)
        assertNull(state.supportsSubtitlePosition)
        assertNull(state.contentId)
        assertEquals("Ready", state.title)
        assertEquals(0.0, state.currentTime)
        assertEquals(1.0, state.playbackSpeed)
    }

    @Test
    fun decodeToleratesUnknownKeys() {
        val json = """{"type":"ping","v":1,"futureField":{"nested":true}}"""
        assertEquals(SiloControlMessage.Ping, SiloControlMessageCodec.decode(json))
    }

    @Test
    fun decodeFailsOnUnknownType() {
        assertFailsWith<SiloControlMessageException> {
            SiloControlMessageCodec.decode("""{"type":"teleport","v":1}""")
        }
    }

    @Test
    fun decodeFailsOnNonObject() {
        assertFailsWith<SiloControlMessageException> {
            SiloControlMessageCodec.decode("""["ping"]""")
        }
    }
}
