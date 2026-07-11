package org.siloserver.silo.cast

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Wire-parity tests against silo-apple's SiloControlMessage encoder
 * (iosApp/Control/SiloControlProtocol.swift). Apple cannot change, so every
 * golden fixture here is the byte-semantics Apple actually produces/expects:
 * camelCase payload fields, the `{type, v, <kind>}` envelope, payloadless
 * ping/pong/close, Int64 track ids, and the Name enum's exact strings.
 * Fixtures are compared as parsed JSON so key order is irrelevant.
 */
class SiloCastMessageTest {
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    private fun assertWireEquals(expected: String, message: SiloCastMessage) {
        assertEquals(
            Json.parseToJsonElement(expected),
            Json.parseToJsonElement(json.encodeToString(SiloCastMessage.serializer(), message)),
        )
    }

    @Test
    fun helloMatchesAppleEnvelopeAndFields() {
        assertWireEquals(
            """
            {"type":"hello","v":2,"hello":{
                "role":"phone",
                "deviceName":"Pixel 9",
                "deviceId":"android-abc",
                "serverId":"srv-1",
                "serverName":"Home",
                "supportedVersions":[2]
            }}
            """,
            SiloCastMessage.Hello(
                SiloCastHello(
                    role = SiloCastPeerRole.Phone,
                    deviceName = "Pixel 9",
                    deviceId = "android-abc",
                    serverId = "srv-1",
                    serverName = "Home",
                    supportedVersions = listOf(SiloCastProtocol.version),
                ),
            ),
        )
    }

    @Test
    fun decodesAppleTvHello() {
        val decoded = json.decodeFromString(
            SiloCastMessage.serializer(),
            """{"type":"hello","v":2,"hello":{"role":"tv","deviceName":"Living Room",""" +
                """"deviceId":"atv-1","serverId":"srv-1","serverName":"Home","supportedVersions":[2]}}""",
        )
        val hello = assertIs<SiloCastMessage.Hello>(decoded).hello
        assertEquals(SiloCastPeerRole.Tv, hello.role)
        assertEquals("srv-1", hello.serverId)
    }

    @Test
    fun launchNestsPlaybackRequestLikeApple() {
        assertWireEquals(
            """
            {"type":"launch","v":2,"launch":{
                "serverId":"srv-1",
                "playback":{
                    "contentId":"movie-42",
                    "fileId":7,
                    "audioTrackIndex":1,
                    "startFromBeginning":false,
                    "resumePosition":120.5
                }
            }}
            """,
            SiloCastMessage.Launch(
                SiloCastLaunchRequest(
                    serverId = "srv-1",
                    playback = SiloCastPlaybackRequest(
                        contentId = "movie-42",
                        fileId = 7,
                        audioTrackIndex = 1,
                        subtitleTrackIndex = null,
                        startFromBeginning = false,
                        resumePosition = 120.5,
                    ),
                ),
            ),
        )
    }

    @Test
    fun controlCommandsUseAppleNamesAndFields() {
        assertWireEquals(
            """{"type":"control","v":2,"control":{"name":"play_pause"}}""",
            SiloCastMessage.Control(SiloCastControlCommand.playPause()),
        )
        assertWireEquals(
            """{"type":"control","v":2,"control":{"name":"set_quality","value":"hd-1080"}}""",
            SiloCastMessage.Control(SiloCastControlCommand.setQuality("hd-1080")),
        )
        assertWireEquals(
            """{"type":"control","v":2,"control":{"name":"select_audio_track","trackId":3}}""",
            SiloCastMessage.Control(SiloCastControlCommand.selectAudioTrack(3L)),
        )
        assertWireEquals(
            """{"type":"control","v":2,"control":{"name":"set_subtitle_sync_ms","milliseconds":-250}}""",
            SiloCastMessage.Control(SiloCastControlCommand.setSubtitleSyncMs(-250)),
        )
        assertWireEquals(
            """{"type":"control","v":2,"control":{"name":"play_next"}}""",
            SiloCastMessage.Control(SiloCastControlCommand.playNext()),
        )
    }

    @Test
    fun subtitleOffOmitsTrackIdLikeApple() {
        val message = SiloCastMessage.Control(SiloCastControlCommand.selectSubtitleTrack(null))
        assertWireEquals(
            """{"type":"control","v":2,"control":{"name":"select_subtitle_track"}}""",
            message,
        )
        val decoded = json.decodeFromString(
            SiloCastMessage.serializer(),
            """{"type":"control","v":2,"control":{"name":"select_subtitle_track"}}""",
        )
        assertNull(assertIs<SiloCastMessage.Control>(decoded).control.trackId)
    }

    @Test
    fun pingPongCloseCarryNoPayload() {
        assertWireEquals("""{"type":"ping","v":2}""", SiloCastMessage.Ping())
        assertWireEquals("""{"type":"pong","v":2}""", SiloCastMessage.Pong())
        assertWireEquals("""{"type":"close","v":2}""", SiloCastMessage.Close())
        // Apple's decoder reads only `type` for these kinds.
        assertIs<SiloCastMessage.Ping>(
            json.decodeFromString(SiloCastMessage.serializer(), """{"type":"ping","v":2}"""),
        )
    }

    @Test
    fun decodesApplePlaybackState() {
        val decoded = json.decodeFromString(
            SiloCastMessage.serializer(),
            """
            {"type":"state","v":2,"state":{
                "contentId":"ep-9","title":"S01E09","isPlaying":true,"isLoading":false,
                "isBuffering":false,"currentTime":42.0,"duration":2700.0,
                "audioTracks":[{"kind":"audio","trackId":1,"title":"English","detail":"EAC3 5.1"}],
                "subtitleTracks":[],
                "selectedAudioTrackId":1,
                "qualityOptions":[{"id":"auto","label":"Auto"}],
                "activeQualityId":"auto","isQualitySwitching":false,
                "playbackSpeed":1.0,"videoGravity":"fit","hdrEnabled":true,
                "supportsVideoGravity":true,"supportsHDRToggle":false,
                "subtitlePosition":"standard",
                "volume":1.0,"isMuted":false,"hasNextEpisode":true,
                "nextEpisodeTitle":"S01E10"
            }}
            """,
        )
        val state = assertIs<SiloCastMessage.State>(decoded).state
        assertEquals("ep-9", state.contentId)
        assertEquals(1L, state.audioTracks.single().trackId)
        assertEquals("EAC3 5.1", state.audioTracks.single().detail)
        assertEquals("auto", state.activeQualityId)
        assertEquals("standard", state.subtitlePosition)
        assertNull(state.subtitleSyncMs)
    }

    @Test
    fun errorMatchesAppleShape() {
        assertWireEquals(
            """{"type":"error","v":2,"error":{"code":"server_mismatch","message":"wrong server"}}""",
            SiloCastMessage.Error(SiloCastError(code = "server_mismatch", message = "wrong server")),
        )
    }

    @Test
    fun protocolConstantsMatchApple() {
        assertEquals(2, SiloCastProtocol.version)
        assertEquals(listOf(2), SiloCastProtocol.supportedVersions)
        assertEquals("_silocast._tcp", SiloCastProtocol.serviceType)
    }

    @Test
    fun v2HandoffMessagesMatchAppleShapes() {
        assertWireEquals(
            """{"type":"handoff_offer","v":2,"handoffOffer":{"requestId":"req-1","serverId":"srv-1","serverURL":"https://silo.example","serverName":"Home","profileId":"profile-1","profileName":"Alex"}}""",
            SiloCastMessage.HandoffOffer(
                SiloCastHandoffOffer(
                    requestId = "req-1",
                    serverId = "srv-1",
                    serverURL = "https://silo.example",
                    serverName = "Home",
                    profileId = "profile-1",
                    profileName = "Alex",
                ),
            ),
        )
        assertWireEquals(
            """{"type":"handoff_challenge","v":2,"handoffChallenge":{"requestId":"req-1","userCode":"ABCD","matchCode":"MATCH","expiresAt":"2099-01-01T00:00:00Z"}}""",
            SiloCastMessage.HandoffChallenge(
                SiloCastHandoffChallenge("req-1", "ABCD", "MATCH", "2099-01-01T00:00:00Z"),
            ),
        )
        assertWireEquals(
            """{"type":"handoff_ready","v":2,"handoffReady":{"requestId":"req-1","serverId":"srv-1","profileId":"profile-1","sessionExpiresAt":"2099-01-02T00:00:00Z","reused":false}}""",
            SiloCastMessage.HandoffReady(
                SiloCastHandoffReady("req-1", "srv-1", "profile-1", "2099-01-02T00:00:00Z", false),
            ),
        )
        assertWireEquals(
            """{"type":"handoff_cancel","v":2,"handoffCancel":{"requestId":"req-1","reason":"denied","message":"No"}}""",
            SiloCastMessage.HandoffCancel(SiloCastHandoffCancel("req-1", "denied", "No")),
        )
    }
}
